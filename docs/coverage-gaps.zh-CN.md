# 真实开发场景覆盖与缺口

本文根据 sample、设备 runner 和主机测试逐项核对覆盖范围。用例存在不等于所有设备
通过；设备结果、源码版本、AAR／APK 哈希及复用依据保留在本地验证记录中。
不以场景数量计算“兼容率”，也不把安全拒绝或预期异常计为业务调用成功。

## 已有端到端入口

先按[真实编辑指南](real-edits.zh-CN.md)发布独立版本的 ART DCEVM AAR 到 Maven Local，
再通过 `--art-dcevm-version VERSION` 选择该版本。runner 校验安装 APK 与 AAR 中的
引擎哈希。下表均有实际页面操作与断言，不只是孤立反射检查。

| 用户操作 | 已有覆盖 | 必须区分的边界 |
| --- | --- | --- |
| 保留输入、详情选择，修改业务判断、返回值、辅助方法 | `business`；同 PID、ViewModel、操作计数和独立 remember | 不承诺改动任意状态作用域后槽位仍保留 |
| 修改已经注册的 lambda，插入另一个 lambda，调整捕获 | `callbacks`；原监听器与新建对照分别调用，引用锚点及注册次数不变 | 新捕获在旧回调中为 null／0／false；歧义映射拒绝，不重新注册来掩盖失败 |
| 调整页面组件、key 和列表顺序 | `rendering`、`compose-state`；实际点击移动后的项，核对状态与滚动锚点 | 直接改变状态所属作用域另由 `scopes` 检查重置；没有任意槽迁移 |
| 修改属性、默认值、委托、data class | `properties`；分别调用旧新对象 | 旧对象不重跑构造器；当前实现仅补齐常量 Compose 状态委托，动态初始化或擦除类型不兼容仍有边界 |
| 修改挂起请求、Flow、Effect 与事件监听逻辑 | `coroutines`、`effects`；请求、取消、注册、输出次数及旧任务身份 | 仅覆盖兼容的 continuation 布局；不承诺任意 label／spill 迁移 |
| 修改 Repository、注入对象、网络处理与序列化 | `repositories`、`network`、`build`；真实 Koin、Proxy、HTTP、Retrofit、Gson、serialization | 旧容器和框架缓存不自动重新生成；分别检查旧新实例 |
| 返回页面、Activity 重建、主题配置变化 | `lifecycle`；显式路由、BackHandler、SavedStateHandle 与 rememberSaveable | 这不是 Navigation Compose 库内部状态验证，也不是进程死亡恢复 |
| 编译失败、批次拒绝、初始化异常后继续编辑 | `recovery`、`transaction`、workflow runner | 准入拒绝与提交后异常分开；新类命名空间及外部副作用不承诺回滚 |
| 跨模块修改、快速保存和连续更新 | `refactoring`、`build`、`session`、workflow runner | 固定 20 次更新与十次快速保存不能证明全天稳定或不存在泄漏 |
| 真实导航页面保留时修改 Activity、业务和旧回调 | `framework/navigation`；NavHost、entry、Hilt ViewModel、参数、rememberSaveable、释放与重新进入 | Navigation 3、进程死亡恢复及任意导航图结构演进另立契约 |
| 改 Hilt 绑定或 Room 查询生成代码 | `framework/hilt`、`framework/room`；原 Provider、旧注入对象、已持有 DAO、缓存 DAO、Flow 事件及同批拒绝后恢复 | 不自动替换旧注入对象；不覆盖数据库 schema 迁移或所有框架版本 |
| 中文组合输入、非末尾光标和选区下修改校验逻辑 | `framework/input`；真实 InputMethodService／InputConnection、text、selection、composition、焦点和连接生命周期 | 不等于覆盖厂商拼音候选引擎、语音输入、剪贴板或所有 TextField 实现 |
| AndroidView 保留原生状态时修改显示与业务逻辑 | `framework/androidview`；普通 remember 持有原对象，稳定 update 内部辅助方法刷新，原 View／tag、文本／选区保留及更新抛错后恢复 | 不覆盖任意 Compose 槽迁移、View 池复用、WebView 或复杂手势；update 应可重复执行，其他 Compose 版本另验 |
| 修改原生按钮 lambda 和 TextWatcher 匿名监听器 | `framework/native-listener`；View 持有原对象，真实点击／setText 通知、精确次数、释放后不再通知和新对象对照 | 仅修改方法体，匿名捕获布局演进另立契约；setText 不替代键盘输入协议测试 |
| 系统结果等待返回时修改回调或业务体 | `framework/activity-result`；真实子 Activity、registry 原回调、成功／取消各一次、前后台无重复、退出注销与新注册 | 显式不绑定 LifecycleOwner 的 registry 注册；不外推生命周期结果队列、权限请求、外部相机／文档提供器或进程死亡恢复 |
| 修改 snapshotFlow 的计算和 collector | `framework/snapshot-flow`；相等结果去重、依赖分支切换、保留收集任务与页面对象、退出取消 | 不保证编辑代码本身触发 snapshot 写入；不覆盖任意 mutation policy |
| 修改 derivedStateOf 计算 | `framework/derived-state`；普通 remember 页面、原派生状态对象及原输入保持，依赖变化后使用新计算 | 真正的初始化值或槽布局变化仍可重置；不保证无依赖变化时立即清除全部派生缓存 |
| 生命周期收集中修改 Flow 转换 | `framework/lifecycle-flow`；真实子 Activity 停止父页面、停止时不交付、恢复时只交付最新 StateFlow 值，活跃期／停止期编辑后原页面保留 | 不覆盖自定义 LifecycleOwner、minActiveState 变更、任意挂起状态机迁移或进程死亡恢复 |
| 弹层保持打开时修改显示与回调 | `framework/overlays`；AlertDialog、ModalBottomSheet、父页面草稿、弹层局部状态、SheetState 与关闭次数 | 草稿由夹具按钮设置；不替代真实输入法覆盖，不外推任意弹层结构和作用域变化 |
| 分页请求等待／失败期间更新，保留当前翻页状态 | `framework/paging-pager`；真实 PagingSource、cachedIn、LazyPagingItems、LazyColumn、HorizontalPager，等待请求／重试的新结果、可见行锚点及页内状态 | 内存数据源与显式等待 gate；不外推 RemoteMediator、网络／数据库分页或全部 Paging 版本 |
| 动画进行中、手指按住时修改原回调 | `framework/animation-gestures`；Animatable 原对象／帧回调、任务及取消次数，pointerInput 跨更新的同一次 DOWN／MOVE／UP | 单指输入与兼容方法体编辑；不外推任意 Transition、状态机／捕获布局演进或多指竞争 |
| 切换多个已保存导航栈后编辑当前页面 | `framework/navigation-stacks`；两个嵌套图、各自详情与返回、entry／草稿／计数／滚动状态恢复，活跃页普通 remember 与原 lambda | 正常离开组合树由 rememberSaveable 恢复；不代表 inactive 页的普通 remember 对象存活，不外推深链或任意导航图重构 |
| 图片请求等待时修改业务和原回调 | `framework/async-images`；Coil 2.7.0 AsyncImage、真实 HTTP PNG、原 request／loader／callback、内存缓存无额外请求、503 与重试、退出释放 | 公开 alpha.2 状态保留检查失败：Effect／具体 client／不可变 requestMode 形式仍由 OkHttpClient 初始化依赖触发整页重置；baseline 只验证未热更新的图片管线，见框架指南；不外推 Coil 3、磁盘缓存、鉴权、变换或真实远端故障 |
| Compose launcher 的系统文档选择期间更新 | `framework/compose-launcher`；rememberLauncherForActivityResult、系统 OpenDocument、父 Activity 停止期间编辑、URI 内容解码、取消、无重复及新页面注册 | API 36 公开版后台重载应答超时，该设备此组未通过；显式使用已安装的 Google DocumentsUI，provider 只提供生成的测试文件；不外推 OEM 默认选择器、权限、相机、Photo Picker、任意生命周期队列或进程死亡恢复 |
| 保留方法引用／匿名对象时修改逻辑和新增捕获 | `framework/method-references`；真实事件注册、原接收者／回调身份、快照与实时数据、GC／前后台、旧新对象对照 | 新捕获字段在旧对象中为默认 null；不外推成员签名变化、任意捕获改型和歧义映射 |
| 权限弹窗等待时修改业务和原结果回调 | `framework/permission-launcher`；真实 RequestPermission 拒绝／允许、已授权同步结果、普通 remember 页面、精确交付和新注册 | 仅 CAMERA 授权，不打开相机；不覆盖永久拒绝、权限组、通知权限、进程死亡或 Manifest 热更新 |
| 网络／数据库分页联动时修改映射 | `framework/remote-feed`；真实 HTTP、RemoteMediator、Room 事务与生成 PagingSource，等待／503／重试／刷新、旧页面与可见行状态 | Room 内存数据库与确定性本地 HTTP；不外推磁盘持久化、schema 迁移、鉴权或远端稳定性 |
| 半小时持续编辑并保留页面与任务 | `framework/long-session`；30 次更新、至少 30 分钟，原监听器／任务、精确事件、GC／前后台、预设资源增长与心跳门槛 | 第 5 轮后固定检查点；不是全天稳定、无泄漏证明或性能基准，不覆盖 ADB 断连恢复 |
| 搜索查询竞争期间修改映射 | `framework/search-races`；debounce、flatMapLatest、旧响应晚到、原 mapper／任务、精确取消与交付 | 可控异步响应，不外推全部 HTTP 客户端或任意挂起状态机变化 |
| 多字段表单中修改转换和提交 | `framework/state-form`；TextFieldState、中文 composition、Input／OutputTransformation、真实 IME Next／Done | 不覆盖自动填充、密码管理器或厂商候选引擎 |
| 嵌套滚动和下拉刷新期间修改回调 | `framework/nested-scroll`；折叠标题栏、原连接／列表／刷新状态、等待任务和单次刷新 | 单指触摸与兼容方法体编辑，不外推多指竞争或任意布局结构变化 |
| 自定义绘制／布局中修改原回调 | `framework/custom-drawing`；真实像素、drawWithCache、原 Modifier.Node／MeasurePolicy、尺寸与挂卸载 | 显式绘制／测量失效后核对新逻辑，不外推所有缓存自动失效或任意节点结构变化 |
| 深链与 Navigation 3 系统返回期间编辑 | `framework/navigation-extensions`；NavDisplay、真实 VIEW／onNewIntent、原活跃页、系统手势取消／提交 | 自定义 PredictiveBackHandler 负责 pop；不覆盖内置预测动画、冷启动或进程死亡 |
| 新旧内容同时存在和可见性退出期间编辑 | `framework/content-transitions`；AnimatedContent、updateTransition、AnimatedVisibility、原子项和精确释放 | 固定 contentKey 与兼容方法体，不外推共享元素或任意状态结构变化 |

新增 `framework/delegate-state` 与 `framework/scoped-layout` 专项检查常量委托补初始化、
原对象身份与构造计数，以及子组槽布局变化时保留未变父层／兄弟状态。二者都有原失败
复现入口；当前源码的修复范围见[框架指南](framework-edits.zh-CN.md)，实际通过范围和
产物版本以本地记录为准，不外推任意初始化或任意槽迁移。

执行命令和精确预期见[真实编辑指南](real-edits.zh-CN.md)及[集成场景](integration-edits.zh-CN.md)。
完整消费方验收同时涉及 `real-edit-test.py`、`device-test.py`、`resource-test.py`、
`workflow-test.py` 和新增的 `framework-edit-test.py`；不能把一个 runner 的 `full`／`all`
当作其他 runner 都已执行。框架组的入口、固定依赖版本和断言契约见
[独立框架夹具与定向 runner](framework-edits.zh-CN.md)。设备通过范围仍以本地验证记录为准。

## 建议补验顺序

以下是尚缺少专项端到端断言的场景，优先级按对日常开发的影响排列，不表示已发现缺陷。

| 优先级 | 缺口与当前最接近的覆盖 | 最小补验标准 |
| --- | --- | --- |
| P1 | 外部系统选择器在前台时的后台重载：API 36 公开版出现应答超时，根因是原进程被厂商冻结；新的受限 provider 唤醒实现见框架指南，公开版本结论仍保留 | 保留超时及线程证据，对照不热更新流程；新产物须补验原页面／launcher／callback 和后续返回，不能靠重启应用判通过 |
| P1 | remember 初始化执行依赖精度：图片原夹具和调整为标准生命周期的夹具均出现整页重置，后者指向 OkHttpClient.Builder 构造器，公开版限制仍存在 | 新的操作数来源和调用参数分析见框架指南；保留原失败复现，并补初始化真实变化必须重置的反例，不能仅增加全局忽略名单 |
| P2 | 方法引用／匿名对象的复杂结构演进：新增专项覆盖目标方法体和引用捕获新增，成员签名及捕获改型仍缺失 | 保留 `service::method` 与 `object : Listener`，专项修改成员签名、捕获类型与删除捕获；区分成功、链接错误与拒绝，不能借用 LambdaMetafactory 捕获映射的结论 |
| P2 | 全天会话与连接故障：已有原 20 轮、主机退出及新增半小时专项入口，设备通过以本地记录为准 | 在固定半小时门槛之外，按发布需要扩展时长／平台；主机 Socket 故障测试已覆盖无应答／半帧、断连、阻塞写入、迟到结果及禁止重复提交；仍需独立验证真实 ADB 中断／恢复，不把主机终止后的新进程称为原进程重连 |

## 需要独立契约的范围

- 进程死亡后的恢复应验证持久化状态与重新安装的源码版本，不属于保留原进程对象的热更新。
- 多进程、混淆 variant、动态功能模块，以及新增资源 ID／Manifest 变化不能由当前调试样例外推；现有资源内容更新与安装结构变化要分开。
- API 33／36 的消费方执行不能代替其他 Android 版本或所有厂商设备验证；引擎符号检查、主机测试、CheckJNI 和真实页面调用各自保留结论。
- 旧对象补跑初始化、任意 Compose 槽迁移、任意协程状态机重写属于能力边界。增加测试应先确定预期，不默认要求运行时恢复无法推导的历史值。
- 普通 remember 内只创建回调与真正执行回调的依赖分别判断；初始化值或槽布局变化仍可重置。通过反射或擦除为 Object 的外部 API 执行初始化回调没有专项覆盖，不外推本组结论。

新增用例放在消费方 sample 和 tools，沿用同 PID、旧对象引用、精确副作用计数、GC、
前后台切换及失败后恢复检查。开发期仅补验受影响组；冻结后按实际影响补齐设备组合，
复用未受影响的原始结果并保留其版本和产物来源。原始失败不能通过追加轮次消除。

## 连接故障与不确定结果

当前源码将连接／握手限制在总计 60 秒内（单次握手最多 5 秒），
普通控制消息写入最多 10 秒，重载发送及等待应答合计最多 120 秒。
期限覆盖阻塞写入和未完整收到的帧；正常空闲连接没有读超时。
请求发送前的 provider 唤醒仍由各次 ADB 命令的 60 秒期限控制。

若重载发送中或等待应答时断连／超时，设备可能已经提交，因此结果明确标为
“不确定”。此时关闭传输、禁止该会话继续提交或重置，保留应用运行，
也不由主机退出钩子发送 Shutdown。工具仍可显示失败原因，需要用户显式
开启新会话后继续；不承诺原进程自动重连或自动回滚。已收到的明确拒绝结果
继续使用既有修正后重试流程。上述为当前源码行为，不代表旧公开版本已有此能力。

主机故障注入入口：

```sh
./gradlew :hot-reload-android-host:test --tests '*RuntimeConnectionTest*' --console=plain
```

测试使用真实本地 Socket，分别断言超时、原请求只发送一次、迟到结果不能恢复
已失效会话、禁止 Shutdown、明确拒绝后下一次成功，以及空闲连接可继续使用。
这些主机断言不替代 API 33／36 的实际页面状态保留结果。
