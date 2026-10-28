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

这里的 `all` **只运行本页的二十组**，不运行原有四套完整 runner。
可以选择 `navigation`、`hilt`、`room`、`input`、`androidview`、`native-listener`、`activity-result`、`snapshot-flow`、`derived-state`、`lifecycle-flow`、`overlays`、`paging-pager` 、`animation-gestures`、`navigation-stacks`、`async-images`、`compose-launcher`、`method-references`、`permission-launcher`、`remote-feed` 或 `long-session` 单组定位，也可以用
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

只验收响应式状态的新增场景时使用：

```sh
python3 tools/framework-edit-test.py --serial DEVICE_SELECTOR --device-id local-apiNN \
  --art-dcevm-version VERSION --scenario-group snapshot-flow derived-state lifecycle-flow
```

这三组直接进入普通 `remember` 持有的响应式页面，使用固定框架应用与原 UI 辅助包，
不切换输入法。核对原进程、页面对象、草稿和选区；草稿由夹具初始值提供，不以此替代输入组的真实输入协议验证。
`snapshot-flow` 检查相等结果去重、切换实际读取的 snapshot 依赖、原计算和 collector 方法体更新，
不把单纯代码重载当成一次 snapshot 写入。`derived-state` 检查派生值和依赖更新，并直接修改原计算 lambda；snapshotFlow 另直接修改原 snapshot 计算块，生命周期组直接修改原 map 块。
`lifecycle-flow` 检查 `collectAsStateWithLifecycle` 活跃期的 map 更新；通过真实不透明子 Activity
停止父页面，等待当前进程取消日志后连续更新原 StateFlow，确认停止时无交付、恢复后只交付最新值，
并在停止期间修改转换体后返回。各组还检查 GC、页面移除后的取消和重新进入后的新对象。
这不覆盖任意 snapshot mutation policy、自定义 LifecycleOwner、minActiveState 切换或进程死亡恢复。

只验收弹层、分页／翻页、动画／手势时使用：

```sh
python3 tools/framework-edit-test.py --serial DEVICE_SELECTOR --device-id local-apiNN \
  --art-dcevm-version VERSION --scenario-group overlays paging-pager animation-gestures
```

这三组复用固定应用与 UI 辅助包，不切换输入法。状态由普通 `remember` 持有，
不改用 ViewModel。`overlays` 在 AlertDialog／ModalBottomSheet 打开时修改显示业务与保留回调，
核对父页面草稿／选区、弹层局部状态、SheetState 身份及关闭次数；草稿由按钮设置，
不替代独立输入法组。`paging-pager` 使用 Paging 3.3.6 的真实 PagingSource、cachedIn、
collectAsLazyPagingItems、LazyColumn 和 HorizontalPager，在可控挂起的追加请求与错误重试期间编辑，
检查原流、列表／Pager 状态、当前页局部计数与返回数据。请求用内存数据源和显式 gate 保持确定性，
不声称覆盖 RemoteMediator、数据库分页或真实服务端。`animation-gestures` 在 Animatable 运行中
修改帧回调，在实际注入的 DOWN／MOVE／UP 跨热更新期间修改 pointerInput 逻辑，
检查对象、进度、原协程与精确事件数；不代表全部 Transition、动画规格或多指手势。
`animation-gestures` 支持 `--motion-phase animation` 或 `--motion-phase gestures`，
用于只补验失败部分。公开版本验收须使用独立消费工程和公开仓库，核对解析产物与 APK 内引擎哈希；
不能将此处开发 runner 的 Maven Local 结果记成公开包实测。

只验收多返回栈、异步图片及 Compose launcher 时使用：

```sh
python3 tools/framework-edit-test.py --serial DEVICE_SELECTOR --device-id local-apiNN \
  --art-dcevm-version VERSION --scenario-group navigation-stacks async-images compose-launcher
```

三组直接进入各自页面，沿用固定包名，不切换输入法；公开版本仍须在独立公开仓库消费工程执行，
不能用 Maven Local 结果代替。每台设备使用独立源码和构建目录，已通过组不随失败组重跑。

- `navigation-stacks`：Navigation Compose 的两个嵌套图分别进入详情，使用 `saveState`／
  `restoreState` 切换返回栈。保留每个 destination 的 entry、草稿、计数和 LazyColumn 位置；
  在当前详情修改业务与原点击 lambda，再恢复另一栈并分别 pop。正常导航移出组合树时使用
  `rememberSaveable` 和列表自身 Saver；热更新额外核对当前页面普通 `remember` token 与回调对象。
  不使用 ViewModel，不声称导航本身会保留已经销毁的普通 remember 对象。
- `async-images`：固定 Coil 2.7.0 的真实 `AsyncImage`／ImageLoader／ImageRequest，加载设备
  loopback HTTP 服务返回的 PNG。服务用显式 gate 等待，跨业务与原成功 lambda 修改后释放，
  核对像素、NETWORK 来源、原请求／loader／回调及草稿。重新显示同一请求须命中 MEMORY_CACHE
  且无新 HTTP 请求；另测真实 503、修改错误状态下的业务后重试、退出时关闭 loader 与服务。
  测试服务在 `DisposableEffect` 启动／关闭，loader 使用已构造的 OkHttpClient，
  请求在 remember 外读取不可变 requestMode，再作为 key 与请求参数。关闭磁盘缓存，禁用网络自动重试；不外推 Coil 3、磁盘缓存、鉴权、图片转换或远端服务稳定性。
- `compose-launcher`：`rememberLauncherForActivityResult` 使用 OpenDocument 契约打开真实系统
  Google DocumentsUI（测试设备须已安装 `com.google.android.documentsui`，不使用 OEM 默认文件管理器），
  同包受 MANAGE_DOCUMENTS 保护的 DocumentsProvider 仅提供生成的测试 PNG。
  父 Activity 停止且选择器打开时修改业务和原 callback，然后实际点击文件，读取 URI 并解码核对。
  再检查系统取消、前后台无重复交付、页面移除和重新进入后的新 launcher。选择器期间只接受明确
  的 DocumentsUI 窗口，并继续监测原 PID 和外部触摸；不放宽其他测试的前台检查。
  此用例不模拟 dispatchResult，不读取用户文件；不外推权限对话框、相机、Photo Picker、
  任意 LifecycleOwner 的结果队列或进程死亡恢复。

以上方法体编辑以保留现有状态为验收条件；状态由夹具按钮准备，不能代替输入法协议验证。
图片服务与文档 provider 在基线安装时即存在，不将新增 Manifest 声明算作热更新能力。

2026-10-28 公开版本结果（Compose `0.1.0-alpha.2`／ART `0.1.0-alpha.4`）：

| 新增组 | API 33 | API 36 |
| --- | --- | --- |
| 多返回栈导航 | 通过，2 次热更新 | 通过，2 次热更新 |
| 异步图片 | 基础加载／缓存／重试通过；热更新状态保留失败 | 基础加载／缓存／重试通过；热更新状态保留失败 |
| Compose launcher | 通过，2 次热更新及全部 6 个检查点 | 后台重载应答超时，未通过 |

两台 launcher 不热更新对照均通过；不将其算作热更新通过。已通过的导航复用原 APK 结果，
后续只运行未完成组；源码、公开产物与 APK 哈希和逐次失败证据保留在本地忽略目录。

**公开版本初始化限制（尚未修复）：** Compose `0.1.0-alpha.2` 的保守分析仍可能把
未知外部 API 的同类型回调、remember 初始化内的 State 读取扩大为执行依赖。原图片夹具在
构造函数中启动线程、使用 callback 形式的 client 工厂，并在 request 的 remember 初始化内
读取可变 mode；修改图片成功回调依赖的业务函数时，API 33 实测页面、loader、请求和草稿重置。
调整为 Effect 启动服务、具体 client 与不可变 requestMode 后，API 36 仍实测重置，主机原因
指向 `OkHttpClient.Builder.<init>`。仅分析项目类的诊断无法排除完整依赖类路径中的初始化传播。
这两份失败均保留，不能用调整夹具或基础图片加载通过结果代替状态保留验证。

`--image-phase baseline` 仅运行真实 HTTP／PNG、内存缓存、503／重试及退出清理，不执行热更新，
用于区分图片管线自身问题和初始化失效问题。默认 `all` 保留热更新后对象／草稿不变的严格断言；
公开 alpha.2 目前在首个图片业务编辑检查失败，后续原成功 lambda、热更新后的缓存及重试检查
不能标记为通过。页面仍使用普通 remember，没有 ViewModel，也没有修改公开产物。

**系统选择器后台重载：** API 36 的公开版验证出现设备重载应答超时：业务编辑曾成功应答，
随后原 callback 编辑未收到应答；另一次在业务编辑时即超时。主机线程等待设备返回，现有证据
尚不能确定设备端根因，不能认定该组通过。不做热更新的同流程对照单独记录，不能代替热更新验收。
选择文件需兼容 DocumentsUI 的根目录入口及 content-desc 文件名；HOME 后须等待 Activity
完成停止，不能仅凭暂停状态马上切回，避免系统转场覆盖页面。以上只修改新用例脚本，未替换公开运行库。

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
| androidview | 修改稳定 `AndroidView.update` 内部的标签／辅助方法及原生按钮调用的业务方法 | factory 只执行一次；同 PID、页面状态对象、原生容器／子 View 与 tag，EditText 原文本和选区保持；下一次实际按钮点击执行新业务逻辑 |
| androidview 恢复 | update 辅助方法抛错，随后修复 | 工具收到 UIException；原页面、原生 View 和输入仍可检查；下一次更新恢复最新显示，不重新安装／重启／注册 |
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
原生互操作页直接使用 `remember { InteropState() }`，没有 ViewModel 持有者。主机区分
缓存初始化期间真正执行的方法与仅创建的 lambda／匿名监听器：修改延后执行的回调体或其
业务依赖时重组原页面，不因构造器在传递依赖图中被标记 dirty 就清空整个 group。
实际初始化调用、被调用的虚方法／接口实现、静态初始化和字段初值变化仍参与重置判断；
未知 cache 调试范围及槽布局变化继续保守重置。主机测试同时检查“只创建”和“真正调用”
两种路径；传给不透明库的显式回调类型按可能同步执行处理。反射调用、通过擦除为 Object
的外部 API 间接执行回调等路径没有专项证明，不能据此承诺任意初始化语义都可保留。

稳定的 AndroidView update 函数对象可能被 Compose 的 Updater 判为未变。运行时在成功
重载并请求重组后，于窗口下一次 pre-draw 使用 Compose holder 自己的 update setter
刷新仍附着的旧 View；若正常重组已经换过 update wrapper 则跳过。该路径保留 Compose
自己的 snapshot 观察，不调用 factory、onReset 或 onRelease，不替换原监听器和注册。
后台窗口恢复绘制时再刷新，窗口移除时取消待执行刷新。更新函数应是可重复执行的渲染；
不承诺任意命令式副作用仅执行一次。命名 getter／setter 不可用时记录警告，不能把未刷新
视为成功；其他 Compose 版本仍需单独设备验证。

这两项曾经失败的行为现在作为正向回归检查；旧失败日志仍保留于本地，不改写为新版本通过。

## 响应式状态的初始化与执行边界

响应式页直接使用 `remember { ReactiveState() }` 和 `remember { derivedStateOf { … } }`，
没有 ViewModel 持有者。runner 检查原页面及原派生状态对象标识、非零输入计数、草稿和选区，
同时核对 snapshotFlow 启停及生命周期收集的精确次数。只出现正确的新文字不能算状态保留。
派生值在实际依赖写入后检查；代码重载本身不保证让所有缓存的派生值立即重算。

主机对未进入 upstream 类分析的库使用明确的延后执行契约，按完整 owner、方法名和
JVM descriptor 匹配 `derivedStateOf` 两个公开重载、`snapshotFlow`、`flow`、`onStart`、
`onEach`、`onCompletion`。它们创建计算／流管道时不执行被保存的回调，因此回调体变化
只要求重组，不应清空所属页面的 remember 槽。语义依据见
[Compose 副作用 API](https://developer.android.com/develop/ui/compose/side-effects)及
[Flow 中间操作](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-flow/)。

若在 remember 初始化内读取 `State.value`、调用 `Flow.collect` 或将这些包装对象交给
未知外部消费者，则仍追踪其延后计算依赖；初始化实际执行的计算体变化继续要求重置。
主机测试分别检查创建／消费路径、class lambda／indy lambda、未知 owner／重载及原有
初始化重置规则。未列入契约的 API 继续保守分析；不按包名前缀把所有 Flow 或 Compose
方法都视为延后执行。没有修改引擎、协程调度、Compose 私有缓存或页面状态的存放位置。

直接修改同一 Kotlin 文件中的 lambda 还会改变编译器生成的 `ComposableSingletons` 源码位置。
lambda 身份指纹忽略精确 Compose 调试调用（`sourceInformation`、`sourceInformationMarkerStart`、
`traceEventStart`）的最后一个立即字符串常量，避免把字符偏移变化误认成多个回调的业务修改。
归一化只用于比较指纹，下发方法保留最新源码信息；业务字符串、未知调用、真实歧义与捕获绑定保护不放宽。

## 方法引用、匿名对象与权限 launcher

```sh
python3 tools/framework-edit-test.py --serial DEVICE_SELECTOR --device-id local-api36 \
  --art-dcevm-version VERSION --scenario-group method-references permission-launcher
```

两组继续使用固定应用包和 UI 辅助包，不启用测试输入法。页面由普通 `remember` 持有，
没有 ViewModel。除了同 PID 和基线 APK 哈希，还核对根页面、页面对象、原接收者、
方法引用／匿名对象或 launcher／callback 身份，以及业务数据和精确调用次数。

- `method-references`：通过真实按钮事件注册 `receiver::fire` 和 `object : Listener`，
  再改变接收者草稿。修改目标方法和匿名方法体后调用原对象，分别核对实时接收者数据与
  注册时捕获的快照；新增捕获字段时，旧匿名对象读取默认 `null`，新建对象读取构造值。
  GC、HOME／恢复后再次调用原注册；退出释放后重新进入，验证新对象使用最新代码。
  注册是事件，不是 remember 初始化期间执行工厂；不能由此推断真正初始化依赖变化不重置。
  本组不覆盖成员签名改变、删改任意捕获字段类型或有歧义的映射。
- `permission-launcher`：使用真实 `RequestPermission` 请求相机权限，只请求授权，不打开相机。
  系统弹窗保持打开时修改业务逻辑后点击拒绝；再次申请时修改原结果 callback 后点击
  “使用应用时允许”。还检查已授权时的同步结果、前后台无重复交付、退出释放与新页面注册。
  弹窗阶段只允许指定系统权限控制器，继续核对原 PID 和外部物理输入；不伪造 registry 结果。
  CAMERA 声明在基线 APK 内，不将新增 Manifest 权限算作热更新能力。仅选择本组时，
  在验收前保存并清理该固定测试包的授权及用户选择标志；监控和主机停止后恢复，保留清理结果。
  失败退出时关闭明确属于该测试应用的遗留系统权限弹窗，避免其阻挡下一次冷启动。
  不在同 PID 验收途中用 `pm revoke` 制造拒绝，也不覆盖永久拒绝、权限组、通知、Photo Picker
  或进程死亡后重投递。

这两组可单独运行。设备通过范围及产物来源以本地记录为准；本地 Compose 修复包与公开 ART
组合的实测，不代表公开 Compose alpha.2 已包含修复。API 33 暂缓时不补跑其旧组。

## 长会话与网络／数据库分页

```sh
python3 tools/framework-edit-test.py --serial DEVICE_SELECTOR --device-id local-api36 \
  --art-dcevm-version VERSION --scenario-group remote-feed long-session
```

固定包名与原 UI 辅助包不变，不切换输入法。两组仅新增测试夹具，不通过 ViewModel 保存页面。
同一设备串行，API 33 可独立暂缓；通过组不随另一组的失败重跑。产物来源与结果记录在本地证据，
使用 Maven Local Compose 修复包不能标记为公开 Compose alpha.2 通过。

`remote-feed` 使用 Room 2.8.4（含 room-paging）、Paging 3.3.6、OkHttp 与设备 loopback HTTP。
RemoteMediator 等待真实网络响应，映射结果后通过 Room 事务写库；UI 仅通过 Room 生成的
PagingSource 读取。初始 20 行、追加 10 行、追加 503／重试、刷新 503／重试均有确定数量断言。
空源及刷新后的边界预取属于正常 Paging 行为，完整 HTTP offset 顺序固定为
`0,20,30,30,0,0,20`；最后一次预取也实际释放并写库，共 7 个响应、5 次成功事务和 2 次 503。
更新分别发生在追加请求等待、追加失败、刷新重试等待期间；旧草稿、页面／数据库／mediator／流／
列表对象，以及追加时实际可见行 key／token 和像素偏移保持。Room 使 PagingSource 失效后，
加载窗口可能改变，窗口内部索引不要求等同于业务行 id；数据未变化的热更新阶段仍检查内部索引不变。失败不得删除已缓存行；成功刷新才在同一
事务清空并写入新行。检查 HTTP 请求／响应、成功提交、错误次数及 PagingSource 失效重建，
最后检查 HOME 恢复不重复请求和页面退出时释放资源。`--feed-phase refresh` 只补刷新相关断言，
`--feed-phase refresh-finish` 进一步只补刷新更新与释放；
前置操作重新准备 40 行数据，不重复已经接受的追加热更新。显式刷新前由按钮滚到当前加载窗口起点，不把刷新造成的
数据替换当作热更新重置。数据库使用真实 Room 内存 SQLite，服务器使用显式响应 gate；不外推
磁盘持久化、远端认证、数据库 schema 迁移、离线重启或任意 RemoteMediator 实现。

`long-session` 在开始前保存不可变验收计划：30 次更新，更新开始间隔至少 60 秒，整段至少
1800 秒；前 5 次预热，第 5／10／20／30 轮各取 3 个固定资源样本，其余轮次各取 1 个。
每次更新后实际调用原注册回调，后台协程持续调用新业务逻辑；检查版本集合、精确累计次数、
原页面／callback／scope／job 身份和草稿。每轮 GC 后等待，取样间隔固定 1 秒；每 5 轮执行
HOME／恢复，结尾注销监听器并取消任务。源码、PID、APK、逐轮耗时、指标与失败全部保留。

资源门槛在运行前固定，以第 5 轮 3 个样本的最大值为基线，后续检查点的每个样本均需通过：

| 指标 | 上限 |
| --- | --- |
| Java 已用堆 | 基线 + max(32 MiB, 基线的 40%) |
| Native 已分配堆 | 基线 + max(16 MiB, 基线的 30%) |
| 应用线程／文件描述符 | 基线 + 8／+ 12 |
| 主机 RSS | 基线 + max(512 MiB, 基线的 50%) |
| 活跃后台任务／监听器／注册次数 | 均为 1；退出后任务与监听器为 0 |
| 单次构建至重载应答 | ≤ 120 秒 |
| 前台主线程心跳最大间隔 | ≤ 5000 毫秒；HOME 停止期间另行排除 |

这些是固定负载下的稳定性验收门槛，不是性能对照或无泄漏证明。不追加轮次平均越线结果，
不以较短预检冒充完整 30 分钟通过。ADB 断连恢复、全天使用、其他厂商与框架版本仍需独立验证。

## 图片初始化依赖与后台冻结

主机沿调用参数追踪实际类型、空值及 lambda 来源，跨普通调用和 SAM 转发保留这些信息。
不透明库仅按参数类型的公开实例方法展开；不会把 `Object.toString` 当作调用函数体，
也不会给外部消费者凭空传入其他页面的回调。缺少类型声明、控制流合并后无法证明的
来源仍保守处理。编译器 bridge 的入口类型转换只用于排除不能抵达方法体的调用；
新旧转换不一致时不能据此跳过变更。Kotlin `lazy` 的三个公开重载按延后执行处理，
读取 `value` 或交给未知消费者时仍分析计算依赖。没有按 Coil／OkHttp 包名忽略初始化。

后台系统选择器可能使原应用进程进入厂商冻结组，已有 socket 数据不能保证唤醒。
主机在发送重载或重置消息前，通过调试 runtime 的受限 ContentProvider 发起 `ping`，
唤醒原进程；调用前检查原 PID 仍存在，返回后再次核对 PID。provider 只接受本应用、
shell 或 root 的调用，只返回 PID；不启动 Activity、不关闭选择器、不重新创建页面。
主机唤醒失败直接报告错误，不把重启后的进程当作原会话，也不通过加长超时掩盖冻结。

上述修复需使用包含改动的新 Compose 主机与 runtime 配套产物。公开 `0.1.0-alpha.2`
的历史失败结论继续保留，本地 Maven Local 修复包的结果不能写成公开版本已修复。
验收先运行 `async-images`、`compose-launcher`，再按公共路径影响定向补验互操作、
响应式收集和真实初始化变更的重置对照；设备与产物哈希见本地验证记录。

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
