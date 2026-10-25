# Android 框架与页面状态的定向验证

`sample/framework` 是独立应用模块，应用 ID 为 `dev.compose.hotreload.framework`。
该模块仅在 `-Pcompose.reload.frameworkFixture=true` 时加入构建，两个专用 runner 自动传入该参数。
原 sample 的未限定任务不会连带安装／启动这个应用，也不会添加 Hilt、Navigation 或 Room 依赖。测试经正常 Kotlin／KSP／
Hilt／D8 构建和现有热更新主机，在同一进程连续更新；原始日志与生成源码仅保存在
忽略的 `build/evidence/`。本文说明验收契约，设备结论另记本地验证记录。

## 执行范围

先按[真实编辑指南](real-edits.zh-CN.md)发布引擎到 Maven Local，再运行：

```sh
python3 tools/framework-edit-test.py --serial DEVICE_SELECTOR --device-id local-apiNN \
  --art-dcevm-version VERSION --scenario-group all
```

这里的 `all` **只运行本页的七组**，不运行原有四套完整 runner。
可以选择 `navigation`、`hilt`、`room`、`input`、`androidview`、`native-listener` 或 `activity-result` 单组定位，也可以用
`--scenario-group hilt room input` 在同一会话只补验尚未通过的组。两台设备在同一 checkout
运行时必须串行，因为 runner 临时修改源码并在退出时恢复；不允许边测试边编辑这些文件。
多设备并行时使用独立 checkout、构建输出和证据目录，并为每份 checkout 准备相同版本的
本地 upstream Maven 产物；每台设备内部仍串行执行。

只验收原生互操作的新增场景时使用：

```sh
python3 tools/framework-edit-test.py --serial DEVICE_SELECTOR --device-id local-apiNN \
  --art-dcevm-version VERSION --scenario-group androidview native-listener activity-result
```

这三组直接进入互操作页，不执行详情页的 Hilt／Room／IME 场景，也不切换设备输入法。
它们仍使用同一个固定应用 ID 和原 UI 辅助包。

Hilt／KSP 插件在 sample 根构建仅声明 `apply false`，避免父子插件类加载器选择不同的
JavaPoet；只在 framework 模块应用。热更新插件通过 AGP 的 `ScopedArtifact.CLASSES`
读取最终项目类，包含 Hilt 追加组件及已变换的 Activity 超类，而不是只读取 Kotlin／javac
原始输出。主机回归 `python3 tools/test-framework-classpath.py --art-dcevm-version VERSION`
真实修改并编译 Hilt 绑定、Activity 方法体与 Room SQL，检查增量快照及恢复后的字节码。固定 Hilt 2.57.2、KSP 2.3.9、Navigation Compose
2.9.7、AndroidX Hilt 1.3.0、Room 2.8.4，Compose 和工具链沿用版本目录。这个组合用于
当前 AGP 8.11.1，不能由单一版本结果推断所有框架版本兼容。

## 必须观察到的真实行为

| 分组 | 编辑／操作 | 验收内容 |
| --- | --- | --- |
| navigation | 详情页输入并写库，修改 Repository 业务体及原 lambda | 同 PID、NavBackStackEntry、Hilt ViewModel、rememberSaveable 标识、草稿和引用锚点；原回调执行新逻辑，次数准确 |
| navigation | 进入 review 后系统返回；弹出详情，再进入另一参数及原参数 | 未弹出的 entry／ViewModel 保留；弹出的 ViewModel 清理一次；新 entry 不复用旧 ViewModel；Singleton 数据库内原数据仍可读取；Effect、Flow 按生命周期释放／恢复 |
| hilt | 将 `@Binds` 的 OriginalFormatter 切换到 AlternateFormatter 后恢复 | 真实重新生成 Hilt 组件；旧注入对象保持原绑定，原 Provider 后续 `get()` 使用新绑定；原 Repository、DAO 和数据库不被替换 |
| room | 修改 `@Query` SQL，调用已持有 DAO 和数据库缓存 DAO | 真实 KSP 重新生成 DraftDao_Impl；下一次查询执行新 SQL；已有 Flow 不因查询方法编辑而重复订阅或丢失数据 |
| room | SQL 变化与活对象改 abstract 同批提交；撤回非法改动再试 | Native 准入拒绝后旧 SQL、旧回调和页面保持；同进程修正后新 SQL 生效；不重新安装或重启来恢复 |
| input | 中文组合输入及中间光标下更新校验体 | 通过系统 InputMethodService 的 InputConnection 操作，保留 text、selection、composition、焦点及输入连接生命周期 |
| input | 提交组合文本，在选区存在时更新，再继续输入 | 选区保持；后续中文输入只替换选区，提交结束 composition，回调读取新草稿；不能用程序设置应用状态替代输入法链路 |
| androidview | 修改 Compose 计算后传给 `AndroidView.update` 的标签及原生按钮调用的业务方法 | factory 只执行一次；同 PID、页面状态对象、原生容器／子 View 与 tag，EditText 原文本和选区保持；下一次实际按钮点击执行新业务逻辑 |
| androidview 边界 | 仅修改稳定 `update` 回调内调用的辅助方法，不改变 Compose 输入 | 热替换成功不意味着命令式 update 自动重放；已有原生属性仍是旧值。随后改变标签输入触发 update，才观察到该辅助方法的新值；此项单列边界，不计为自动刷新成功 |
| native-listener | 修改原 `OnClickListener` lambda 和匿名 `TextWatcher.afterTextChanged` 方法体 | 监听器只在 factory 创建时安装；真实按钮点击及原 EditText 的 `setText` 通知调用原监听器的新逻辑，各一次；重载本身不触发通知 |
| activity-result | 子 Activity 尚未返回时修改原 registry 回调与业务方法，分别返回成功及取消 | 真实 `startActivityForResult`／`setResult` 链路；原有子 Activity token 不变，父页面原对象不变；成功与取消各交付一次，GC／前后台切换不重复交付 |
| 原生互操作共同检查 | 弹出页面后重新进入，再次操作并弹出 | 每次 entry 创建一个 View 和 registry 注册；每次退出释放／注销一次；移除监听器后对已释放 View 再发事件不会调用旧回调；新 entry 的新 View／新注册执行更新后的逻辑 |

详情页每次正常编辑后请求 GC、切换前后台，并检查精确的订阅／退订与行值事件。
退到桌面后读取会话持续采集的当前进程夹具事件日志，确认 Flow 实际取消订阅再恢复页面，
避免用固定延时假定退出动画已结束，也避免设备环形日志覆盖早期事件后误判退订数量。
输入组在组合区间和选区断言完成之前不切后台，因为失焦本身可以合法结束组合输入；
完成输入后再验证 GC 和前后台切换。安装 APK、Maven Local AAR 及内部引擎哈希必须匹配。

原生互操作页在每次编辑与事件后核对工厂／释放、注册／注销、子 Activity 创建／销毁及
回调计数，并读取原生 EditText 的真实文本与选区。Activity Result 使用显式
`ActivityResultRegistry.register`（不绑定 LifecycleOwner），由 `DisposableEffect` 管理注销；
它验证注册表持有原回调的行为，不用 `dispatchResult` 模拟系统返回，也不代表所有
`rememberLauncherForActivityResult`、生命周期暂停期间待交付结果或进程死亡恢复策略均已覆盖。
TextWatcher 的通知由对真实 EditText 的 `setText` 触发；键盘输入协议仍由独立 `input` 组验收。
runner 对已确认的 update 重放边界写入单独的 `boundary` 字段；不能把该契约检查的
PASS 描述为原生 UI 自动刷新已支持。原始失败和后续边界验证均保留在本地证据中。

原对象由 Navigation 的 ViewModelStore 持有。探索中使用 `remember { InteropState() }`
创建回调持有者时，修改原生回调依赖的业务方法使构造器被判为 dirty，日志明确记录
`cache evaluates dirty method`，整个所属 Compose group 被重置，View 和注册随之重建。
因此本组的保留结论限定于 ViewModel 持有者，不能外推到这种 remember 初始化依赖变化。
这项更强保留要求的失败仍保留；没有修改公共 invalidation 策略来绕过它。

## 输入法与设备准备

仅选择输入组时安装／启用测试 IME；其他组用系统按键输入草稿，不更换设备输入法；若现有键盘转换了输入，记录实际非空
草稿并对后续保留／查询值做精确断言。中文组合、选区与提交行为只由输入组的专用 IME 验收。
输入组使用独立、测试专用 IME，向真实 InputConnection 发送确定的中文组合与提交操作。
它不是应用内 setter，也不模拟第三方输入法的拼音候选引擎。小米／其他 OEM 的安装确认
必须在验收开始前完成。runner 保存原默认输入法，退出时恢复并撤销本轮新增的启用设置；保留固定包名的辅助 APK，
后续核对哈希后复用，避免每轮重新安装触发 OEM 确认。验证应用也固定包名：先编译并核对
已安装 APK 哈希，仅在不一致时安装；随后启动主机跳过重复安装任务。安装与配置证据留在忽略目录。
测试 IME 的命令接收器要求发送方具有 `android.permission.DUMP`，且仅操作 framework 包的
活动编辑器；普通应用不能发送控制命令。

验收阶段继续使用已有物理触摸、进程及窗口监视器。外部触摸或进程更换立即使本次失败，
保留证据，不自动重发操作。该测试覆盖 Android 输入协议与 Compose 编辑状态，不宣称
已覆盖所有厂商键盘、语音输入、剪贴板、候选词选择或所有 TextField 实现。

## 回归与复用

开发期先执行新增或失败的组。若仅变更新模块和测试夹具，保留已有引擎与旧 sample
的原版本结果，不把旧包写成新包实测。若发现主机、Gradle 插件、runtime 或引擎公共路径
需要修复，先说明影响，再补验受影响组；不能只凭改动行数判断范围。完整旧矩阵不作为
每次新增用例后的默认动作。

本组不修改数据库 schema，不自动重建旧 DI 对象，不迁移任意 Compose 槽或任意协程
状态机。Navigation 3、原生 View 池复用／复杂手势、生命周期绑定结果队列、任意匿名捕获演进和长时间连接故障
仍需各自专项用例，不能因本组通过而划为完成。
