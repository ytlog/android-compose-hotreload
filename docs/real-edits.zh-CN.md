# 真实 Android 连续编辑验证

本页的用例使用 `sample` 的真实 Compose 页面、ViewModel、输入框、列表选择和一次注册的监听器。编译与更新沿用 Kotlin → ClassFile → 主机 D8 → 设备 JVMTI → Compose invalidation 路径。

## 使用本地 ART DCEVM

假设两个仓库在同一父目录。在引擎仓库冻结源码后，发布一个独立的开发版本，避免覆盖公开版本：

```sh
cd ../art-dcevm
ENGINE_VERSION=0.1.0-dev.$(git rev-parse --short=8 HEAD)
./gradlew :artdcevm:publishToMavenLocal -PpublishVersion="$ENGINE_VERSION"
cd ../android-compose-hotreload
export ORG_GRADLE_PROJECT_artDcevmVersion="$ENGINE_VERSION"
./sample/gradlew -p sample hotRunDebug -Pcompose.reload.devToolsHeadless=true
```

设置 `artDcevmVersion` 后，根构建及 sample 构建只从 Maven Local 解析 `io.github.ytlog:artdcevm`，包括缺失时直接失败；其他依赖仍使用原仓库。未设置该属性时沿用版本目录中的公开引擎版本。环境变量保证 composite build 和后续 reload 使用同一版本。也可以对每次 Gradle 调用显式传入 `-PartDcevmVersion=VERSION`。

启动后，在另一个终端选择设备并打开用例页面：

```sh
adb -s DEVICE_SELECTOR shell am start -n dev.compose.hotreload.sample/.RealEditActivity
```

这里的设备选择器是占位符；不要把真实序列号写进代码或提交的证据。

## 自动验证

停止手动运行的 hotRun 后执行：

```sh
python3 tools/real-edit-test.py --serial DEVICE_SELECTOR --device-id local-apiNN \
  --art-dcevm-version "$ENGINE_VERSION" --scenario-group full
```

用例修改工作区源码，结束时恢复；运行前应提交或备份工作。不要同时在同一 checkout 运行两个用例脚本。脚本会拒绝覆盖已有的临时生成夹具文件，并逐次保存 Kotlin／Java ClassFile 及哈希。原始日志、逐步源码差异、UI 快照、截图和报告只保存在已忽略的 `build/evidence/`。

| 分组 | 连续编辑与检查 |
| --- | --- |
| `startup` | 只执行真实输入、详情选择、业务调用和原监听器调用，核对进程、对象身份及精确计数；不提交热更新 |
| `business` | 修改返回值、判断条件、数据处理并提取辅助方法；通过页面按钮调用新逻辑 |
| `rendering` | 更新页面文字、增加组件并交互、删除组件；检查独立 `remember` 状态 |
| `callbacks` | 保留最初监听器；插入无捕获／同捕获的 lambda，修改方法体，新增、重排、删除、恢复引用与基本类型捕获，拒绝歧义插入并重试 |
| `recovery` | 将有活实例的 ViewModel 改为 abstract，同批改变业务方法和捕获布局；拒绝后检查旧调用，撤回非法改动并重试，再追加新类与调用方 |
| `compose-state` | 按 key 重排组件和区块，插入／重排／删除／新增 LazyColumn 行，修改行标签；拒绝与恢复时检查状态归属 |
| `compose-recovery` | 单独运行 `compose-state` 的行标签／拒绝／重试三步，以缩小失败范围 |
| `refactoring` | 修改 Kotlin／Java 参数与返回描述符，追加返回类，跨文件移动辅助方法，重命名 Java 方法；同批拒绝／重试后恢复原签名，持续调用原服务对象 |
| `coroutines` | suspend／协程／Flow 的旧任务、新任务、请求与回调计数、取消、异常及失败恢复 |
| `repositories` | Repository 接口、实现、默认方法、Koin 单例／工厂／构造注入、Java 动态代理及同批拒绝恢复 |
| `properties` | ViewModel／业务属性初始化、getter、新增与改型、委托属性及 data class；比较旧新对象与失败恢复 |
| `full` | 在同一进程执行所有组，最后返回原页面再次调用原监听器 |

脚本通过真正的 TextField 输入草稿并选择详情；随后检查同一 PID、ViewModel 身份、监听器身份、输入内容、页面选择、业务操作次数及精确的工厂／回调调用次数。初始监听器与引用锚点都是不可变字段；单独创建的对照回调不会替换它们。每步更新后请求 GC，并切换到后台再返回原 Activity；不会用重启或重新安装绕过更新失败。AAR、APK 和引擎的 SHA-256 均记录，安装包哈希及 APK 内动态库必须与本地构件匹配。

运行期间设备应保持静置。脚本持续记录内核触摸事件、系统日志和 ADB 操作；`input tap` 的注入与内核触摸输入分别记录。检测到脚本外触摸、原应用进程消失／替换、应用被其他窗口覆盖或监视器退出时，当前运行立即失败，不重发输入、不重新启动应用继续计数。输入事件可能在一次 ADB 命令执行期间到达，脚本会在命令返回后停止；监视器用于判定验收有效性，不会屏蔽设备触摸。原进程日志按启动时 PID 采集，避免系统重启应用后只留下新进程日志。原始记录可能含设备信息，全部留在忽略的证据目录。

缩小前置失败时，先运行 `--scenario-group startup`，明确失败类型并保存原始证据；固定稳定性轮数与通过条件后再运行，不追加成功轮次抵消失败。监视器不可用的设备不能把这一验收记为通过。主机回归：`python3 -m unittest discover -s tools -p test_device_session.py`。

旧监听器和新建监听器是不同的验收对象。特别是新增捕获变量，不能把新注册监听器的成功当成旧对象正确的证据。失败保留原始运行，不追加运行把失败改写成通过。该脚本验证功能，不是性能基准；每步 reload 耗时包括构建和通信，不能当作 ART 停顿时间。

## 后续顺序

状态页面保留输入、详情选择、ViewModel、服务对象、根 remember 标识与滚动状态。带 key 的组件分别保留 remember 计数，列表行分别保留 rememberSaveable 标识、计数与业务对象标识；脚本检查屏幕上的实际顺序，点击移动后的项，并核对插入／重排／删除前后的首个可见 key 与滚动偏移。状态归属放在未改动的组件中；修改排序辅助函数或无状态标签不应清空这些无关状态。排序用例修改提供顺序数据的 Kotlin 方法，带 key 的页面作用域字节码未变；直接修改状态所属作用域时仍沿用上游的作用域重置契约。

签名用例同步重编 Kotlin 调用方与 Java 被调用方，持续调用最初的 Java 服务对象，分别断言结果与真实返回类型。跨文件移动方法会改变 ClassFile 所有者；最后恢复原签名并继续调用。同批拒绝必须保持已有可达调用方及旧对象行为。运行时先追加新类，再执行原子的重定义事务；追加是单向操作，拒绝后新类型可能仍在命名空间。恢复时再次修改该新类并验证修正后的方法体，不声称命名空间回滚或类卸载。

模型属性／初始化由 `properties` 专门用例覆盖，预期见下文；协程挂起／Flow 由 `coroutines` 组按下述契约覆盖；Repository／依赖注入／代理集成由 `repositories` 组覆盖。不声称可为旧对象重跑构造器、恢复任意新捕获值或迁移任意 continuation 状态布局。

开发时运行受影响分组；代码冻结后在两台设备分别运行 `full`，同一设备串行。引擎源码未改时复用已有冻结矩阵并保留版本、产物与来源；新样例 APK 的执行证据单独记录。底层 JNI、安全拒绝、真实调用、CheckJNI 与符号覆盖仍分别验收。历史长停顿和性能越线保持未解决状态，不能由样例功能通过来关闭。

## 旧 lambda 的兼容契约

Kotlin 按源码顺序给编译器生成的方法编号。插入 lambda 会改变编号，新增捕获还会改变方法描述符；D8 已安装的合成回调仍调用旧实现对应的桥接。只保留当前一轮 D8 的桥接会丢失这个入口，导致 `NoSuchMethodError`，复用编号还可能接到错误方法体。

主机在会话内保留初始 D8 桥接及之后每轮发出的回调入口，让它们调用同一逻辑 lambda 的最新方法体。对应关系依据外层方法或父 lambda、SAM 契约、规范化方法体与捕获模式，不能只按源码编号匹配。先以未变的方法体固定对应关系，只让剩余唯一的一对旧／新 lambda 演进。捕获模式本身不能区分“新插入的 lambda 保留旧模式，原 lambda 同时新增捕获”，因此同一作用域／SAM 中同时改动多个方法体可能需要分开更新。无法唯一对应时，在设备更新前明确拒绝；修正后可重试，活动映射不变。删除源码中的 lambda 后，已有回调仍可调用其最后一个方法体。

对旧回调，调试名称和 JVM 类型相同的捕获保留原值，不受参数顺序影响。新增、改名、改型的捕获使用 JVM 默认值：引用／数组为 `null`，数值为零，布尔为 `false`。不重跑初始化表达式、注册工厂或构造器；新代码直接解引用默认 null 时，仍可能正常抛出 `NullPointerException`，主机不会推算历史初始化值。新建对照回调捕获当前真实值。删除再恢复某一捕获时，创建时持有该名称／类型的回调可恢复自己的原绑定。

捕获缺少调试名称时，只允许方法体及捕获布局均未变化，否则拒绝演进。保留的编译器直接入口维持原修饰符，包括接口中的 private 方法体。普通方法引用沿用声明目标契约。当前规划器处理 LambdaMetafactory 生成的方法体，不声称支持任意匿名类的捕获迁移。只有设备报告对应类确实已追加／重定义后才提交入口清单，包括报告的部分成功；拒绝的计划不会推进清单。

回调不匹配会立即使测试失败。脚本没有替换原监听器的恢复步骤；旧回调和新建对照回调分别实际调用，并检查主机拒绝及 Native 事务拒绝后的成功重试。

## 状态保留边界

渲染和签名重构组通过将独立、反射创建并保留的准入守卫改成 abstract 来拒绝批次。守卫有真实活实例，ART 必须拒绝事务；修改它不会改变 Compose 参数 ABI。原有 `recovery` 组另行覆盖将活 ViewModel 改成 abstract。

探索中曾同时修改 ViewModel 修饰符与行标签。Kotlin 增量编译改变了调用方的参数稳定性检查与 intrinsic remember 比较，真实缓存槽布局随之变化；按已有组重置契约，面板／行局部状态被清空，而 ViewModel、草稿、选择、服务和根 remember 状态仍保留。该次运行仍记录为更强状态保留要求的失败，本次没有实现不同 Compose 槽布局之间的迁移。现用独立守卫将事务原子性与这类布局演进分开验收；没有归一化参数入口，也没有关闭编译器优化。

## 模型属性与初始化契约

`properties` 组在真实页面保留 provider 管理的 ViewModel、业务模型、委托对象和 data class；输入 `property42` 后修改旧对象数据。每次通过页面按钮调用最新 getter，并新建对照对象。不可变引用锚点、独立初始化计数器和原页面状态用于识别对象替换与初始化重放。

| 编辑 | 旧对象 | 新对象 |
| --- | --- | --- |
| 修改属性初始化表达式、`init` | 同名同类型值保留，不重跑初始化 | 执行最新初始化表达式和 `init` |
| 修改计算 getter | 下一次调用执行新 getter，读取保留数据 | 执行新 getter，读取新初始化值 |
| 新增 String／Int／Boolean 属性 | null／0／false；Kotlin 非空声明不补执行初始化，解引用 null 明确为 NPE | 执行声明的初始化表达式 |
| Int 改 Long | 0L，不作数值转换 | 使用新 Long 初始值 |
| 实例 Any? 改 CharSequence? | 本用例 StringBuilder 符合现有直接类型兼容规则，保留旧引用值 | 使用新初始化值 |
| CharSequence? 改 Number? | 不兼容值清为 null | 使用新 Number 初始值 |
| 修改 lazy 初始化 lambda | 已求值的旧 lazy 保留缓存；未求值的旧 lazy 首次访问执行更新后的方法体；各实例计数各一次 | 首次访问执行新初始化代码 |
| 修改既有 mutableStateOf 初始值 | 保留旧 delegate 和用户写入值 | 创建带新初值的 delegate |
| 新增委托属性 | backing delegate 为 null，getter 抛 NPE | 创建 delegate，getter 可调用 |
| 委托 String 改 Int | MutableState backing 字段 JVM 类型未变，旧 String 留存；getter 抛 CCE | 新 delegate 存储 Int |
| 应用显式调用委托 setter 迁移 | 设置 Int 后 getter 正常；这一步由页面按钮触发 | 不受影响 |
| data class 新增主构造参数 | 新字段为 null／0；不重跑主构造器 | 使用新默认参数 |
| data class 参数 Int 改 Long | 旧值变为 0L | 使用新参数默认值 |
| 修改 data class 默认参数 | 旧字段值保持 | 无参构造使用新默认值 |

每步检查 data class 的真实 `componentN`、`copy()`／`copy$default`、`equals`、`hashCode`、`toString`。`copy()` 创建新对象，但默认参数取接收者当前字段，而不是声明的新默认值；检查副本不同身份、相同字段及相等／哈希一致，新建默认对象与旧对象不相等。代表性阶段保存并核对真实 ClassFile 描述符，包括委托泛型改型后 backing 字段擦除不变。

缺失 delegate 与类型擦除导致的异常是明确暴露的使用边界，不计作“旧属性可正常使用”，也不通过自动运行构造器修复。旧对象初始化迁移需应用显式定义；本组只演示已存在 delegate 的 setter 迁移，不替新增 delegate 推算历史初值。

非法活对象改 abstract 的批次同时包含待新增属性。拒绝后继续真实调用旧 getter、创建旧定义的新对象并核对计数；修正后在同一进程提交，新属性仅在新对象执行初始化。各步请求 GC、切换前后台并保留输入、引用锚点及独立 remember 标识。开发期执行 `--scenario-group properties`；`properties-lazy` 单独定位已求值／未求值 lazy；冻结后纳入 `full`。结果以本地验证记录与逐次报告为准。

## suspend、协程与 Flow 契约

`coroutines` 组使用 provider 持有的稳定 ViewModel、`SupervisorJob`、实际 kotlinx.coroutines 与 Compose 页面。按钮启动任务或收集，独立的回调桥用 `suspendCancellableCoroutine` 挂起，另一个按钮模拟外部请求完成；不依赖延时猜测挂起位置，不在重组或重载时重启任务。它验证回调式请求处理，不声称已覆盖真实 HTTP 服务或传输层重试。

| 编辑／事件 | 已存在任务或收集器 | 新任务或收集器 |
| --- | --- | --- |
| suspend 方法体连续更新 | 保留挂起前已捕获的局部值；恢复时执行更新后的后半段，不重发请求 | 捕获最新初值，执行最新方法体 |
| suspend lambda、嵌套 suspend 更新 | 原 continuation／Job 继续，完成通知一次；嵌套返回到原调用方 | 新建任务使用最新代码 |
| 在当前挂起点之后追加一个挂起点 | 保留当前 label 和已有 spill 布局；原请求完成后进入新增挂起点，各请求一次 | 按新顺序经历两个挂起点 |
| 取消及外部迟到回调 | 原请求取消一次，finally／Job 终结各一次，迟到回调被桥忽略，不产生结果 | 后续新任务仍正常完成 |
| 挂起请求失败 | 一次错误终结和 finally，无成功输出；SupervisorJob 的下一任务可继续 | 新任务使用新体，不自动重试失败请求 |
| 更新已创建但未收集的冷 Flow | 原工厂捕获值保留，收集时执行新 Flow 体 | 新 Flow 捕获新工厂值 |
| 更新冷 Flow／map 的已挂起体 | 保留请求及原收集器，恢复后值只投递一次 | 最新处理逻辑生效 |
| 活跃 Flow 的 map／collect 更新 | 订阅不重建；后续事件执行更新后的转换／接收体 | 新收集器独立订阅，每个事件各接收一次 |
| Flow 取消／重新收集 | 旧订阅退出一次；挂起转换取消后不接收迟到值 | 显式启动的新收集器正常接收 |
| StateFlow 相同值赋值 | 按相等性合并，相同值不产生额外输出 | 保持 StateFlow 本身语义，不要求每次赋值都有回调 |
| 同批更新被拒绝／修正 | 已挂起请求和已有订阅保持旧行为，无丢失、重发或半更新 | 拒绝时仍为旧定义，修正成功后使用新定义 |

稳定台账记录每个请求、交付、取消、迟到回调、输出、订阅、退订和终结事件。断言检查事件多重集及每个收集器输出顺序；跨收集器的全局调度顺序不作承诺。每步请求 GC、前后台切换并检查输入、ViewModel 与独立 remember 标识。同一进程连续更新，代表性阶段保存真实 continuation 的 ClassFile、DebugMetadata、label 与 spill 字段报告。

追加挂起点用例只覆盖当前挂起点及原 spill 语义保持兼容的编辑。任意插入／删除前置挂起点、重排 label 或改型存活 spill 不是已承诺的迁移能力；不能把这组结果推广到任意状态机重写。开发期使用 `--scenario-group coroutines`；`coroutines-recovery` 可单独定位拒绝后旧任务／新任务与重试，冻结后纳入 `full`；设备结论以本地验证记录为准。

## Repository、依赖注入与动态代理契约

`repositories` 组通过真实页面持有 ViewModel、直接创建的 Repository、独立 Koin 4.1.1 容器、构造注入的客户端、Java Proxy、InvocationHandler 以及已缓存的 Method。输入 `repo42` 写入旧对象，按钮同时调用旧对象和新建对照对象。Kotlin 接口编译为真实 JVM default 方法，经正常 D8 生成设备产物；代表性步骤核对 ClassFile 方法及描述符。

| 编辑 | 已持有对象 | 新对象 |
| --- | --- | --- |
| 实现类或接口默认方法体 | 下一次调用新方法体，旧业务数据与引用保留 | 使用最新方法体与构造初值 |
| 新增抽象方法、default 方法；default 与 abstract 互换 | 直接与 DI 对象按同批接口、实现及调用方分派；代理已有入口继续转发 | 直接与 DI 新对象按同批定义调用 |
| Proxy 原来没有的接口方法 | 新增 abstract 抛 AME；新增 default 执行接口默认体，绕过 handler | Proxy 缓存复用同一个类，具有相同边界 |
| 接口参数 String 改 Int | 直接与 DI 对象的类型化调用使用 Int；代理缺少新签名入口时为 AME；已缓存的旧 String Method 明确失败为 NoSuchMethodError，不串到新方法 | 直接与 DI 对象可调用最新签名；复用的代理类仍有上述边界 |
| 更新 InvocationHandler 方法体 | 原代理和原 handler 下一次执行新逻辑，保留目标对象 | 新代理使用新逻辑，Proxy 类缓存可复用 |
| 修改 Koin 绑定实现 | 旧容器 singleton 保留；原注入对象与之后创建的 factory 客户端仍引用该 singleton | 新独立容器采用新绑定和新实例 |
| 更新注入客户端的方法体 | 原客户端立即调用新方法，注入引用保持 | 新客户端使用新方法 |
| 目标方法抛出异常 | 真实反射转发解包 InvocationTargetException，保留目标异常类型 | 相同异常契约 |
| 非法活对象改 abstract 导致同批拒绝 | 原接口、实现、代理和 DI 行为保持；无半更新 | 拒绝后新建对象、容器也保持旧定义；修正后整批采用新定义 |

代理处理器将已有代理入口的 Method 真实调用到具体 Repository，包括初始 default 方法；不会伪造返回值。新增 abstract／default 沿用现有 JVM 代理重链接契约，不自动生成新 handler 转发入口；这分别是安全失败和绕过 handler 的默认调用，不能统计为新增代理方法转发成功。旧代理、新代理、缓存的 read Method 均真实调用，签名变化后单独验收退休 Method 的安全失败。Object 的 equals／hashCode／toString 仍采用身份语义。没有给旧容器自动重新绑定或重新注入；本组覆盖 Koin single、factory 与构造注入，不推广为所有 DI／网络代理框架或自动 default 调用机制的兼容承诺。

独立台账精确检查 Repository 构造、客户端构造、业务 read 和 handler 次数，重载、GC、前后台切换不得额外执行这些动作。每次调用比较旧新数据、ViewModel 与 remember 标识、代理类缓存及所有引用锚点。同一进程依次运行 13 个场景，拒绝后直接检查旧新对象，再修正并重试。开发期运行 `--scenario-group repositories`；`repositories-interface` 单独定位新增抽象方法的真实调用与代理边界；冻结后纳入 `full`，设备结论单独记录。


Compose Effect、状态作用域、页面重建、真实 HTTP／框架缓存、跨模块编译及连续更新的补充契约见 [集成场景](integration-edits.zh-CN.md)。
