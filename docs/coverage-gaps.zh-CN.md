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
| 修改属性、默认值、委托、data class | `properties`；分别调用旧新对象 | 旧对象不重跑构造器；缺失 delegate 或擦除类型不兼容可抛异常 |
| 修改挂起请求、Flow、Effect 与事件监听逻辑 | `coroutines`、`effects`；请求、取消、注册、输出次数及旧任务身份 | 仅覆盖兼容的 continuation 布局；不承诺任意 label／spill 迁移 |
| 修改 Repository、注入对象、网络处理与序列化 | `repositories`、`network`、`build`；真实 Koin、Proxy、HTTP、Retrofit、Gson、serialization | 旧容器和框架缓存不自动重新生成；分别检查旧新实例 |
| 返回页面、Activity 重建、主题配置变化 | `lifecycle`；显式路由、BackHandler、SavedStateHandle 与 rememberSaveable | 这不是 Navigation Compose 库内部状态验证，也不是进程死亡恢复 |
| 编译失败、批次拒绝、初始化异常后继续编辑 | `recovery`、`transaction`、workflow runner | 准入拒绝与提交后异常分开；新类命名空间及外部副作用不承诺回滚 |
| 跨模块修改、快速保存和连续更新 | `refactoring`、`build`、`session`、workflow runner | 固定 20 次更新与十次快速保存不能证明全天稳定或不存在泄漏 |

执行命令和精确预期见[真实编辑指南](real-edits.zh-CN.md)及[集成场景](integration-edits.zh-CN.md)。
完整消费方验收同时涉及 `real-edit-test.py`、`device-test.py`、`resource-test.py` 和
`workflow-test.py`；不能把 real-edit 的 `full` 当作四个 runner 都已执行。

## 建议补验顺序

以下是尚缺少专项端到端断言的场景，优先级按对日常开发的影响排列，不表示已发现缺陷。

Navigation Compose、Hilt／Room 和输入状态已新增[独立框架夹具与定向 runner](framework-edits.zh-CN.md)。
设备验收完成前仍保留为覆盖缺口；构建和主机快照回归通过不能替代页面实测。

| 优先级 | 缺口与当前最接近的覆盖 | 最小补验标准 |
| --- | --- | --- |
| P1 | Navigation Compose：当前只有手写路由栈 | 使用真实 NavHost／NavController，从列表进入详情并输入数据；修改目的页业务与回调后返回、再次进入；核对 back stack entry、导航作用域 ViewModel、参数、保存状态及 Effect 释放次数 |
| P1 | Hilt／Dagger 与 Room：Koin 和 serialization 不能代表这些生成代码 | 保留注入的 Repository、DAO、数据库和 Flow 收集器；先改实现体，再改绑定或 DAO 查询；核对增量生成产物、真实查询结果、订阅次数及旧新依赖图。数据库 schema 迁移另立契约，不隐含由热更新完成 |
| P1 | 编辑中的文本状态：当前只断言字符串草稿保留 | 在真实 IME 组合输入、非末尾光标和选择区间下修改校验／格式化 lambda；核对文本、selection、composition、焦点及后续输入，不只核对最终字符串 |
| P2 | AndroidView、原生监听器和 Activity Result：现有回调主要由测试页面自己的容器持有 | 由真实 View／系统注册表持有原回调，在未返回的结果或下一次事件前更新方法体；检查原对象调用新逻辑、结果只交付一次、销毁时释放一次，并单独验证新注册对照 |
| P2 | 绑定方法引用与匿名对象的捕获演进：已有 Java `String::toUpperCase` 和 Retrofit 匿名回调，但没有相应专项演进断言 | 保留 `service::method` 与 `object : Listener`，分别修改目标体、成员签名、捕获布局；检查原接收者数据和引用，区分成功、链接错误与拒绝；不能借用 LambdaMetafactory 捕获映射的结论 |
| P2 | snapshotFlow、derivedStateOf、collectAsStateWithLifecycle：已有普通 Flow／Effect 覆盖 | 保留原派生状态和订阅，更新计算体后分别变更依赖、切换前后台；检查最新值、订阅数量、取消及恢复行为，按各 API 本身语义断言 |
| P2 | 长会话与连接故障：已有 20 轮和主机退出测试 | 运行前固定会话时长、编辑次数、停顿与资源增长门槛；记录多个阶段的堆、线程、监听器和任务数量；另测 ADB 中断／恢复与失败提示，不把主机终止后的新进程称为原进程重连 |

## 需要独立契约的范围

- 进程死亡后的恢复应验证持久化状态与重新安装的源码版本，不属于保留原进程对象的热更新。
- 多进程、混淆 variant、动态功能模块，以及新增资源 ID／Manifest 变化不能由当前调试样例外推；现有资源内容更新与安装结构变化要分开。
- API 33／36 的消费方执行不能代替其他 Android 版本或所有厂商设备验证；引擎符号检查、主机测试、CheckJNI 和真实页面调用各自保留结论。
- 旧对象补跑初始化、任意 Compose 槽迁移、任意协程状态机重写属于能力边界。增加测试应先确定预期，不默认要求运行时恢复无法推导的历史值。

新增用例放在消费方 sample 和 tools，沿用同 PID、旧对象引用、精确副作用计数、GC、
前后台切换及失败后恢复检查。开发期仅补验受影响组；冻结后按实际影响补齐设备组合，
复用未受影响的原始结果并保留其版本和产物来源。原始失败不能通过追加轮次消除。
