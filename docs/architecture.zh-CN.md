# 工程架构

[English](architecture.md) | **简体中文**

Android 是顶层工程；上游 Compose Hot Reload 是独立、固定版本的源码子工程。协议、设备运行时、主机服务和 Gradle 集成各自承担一种职责。

```text
android-compose-hotreload/
├── modules/
│   ├── protocol/        # Java 通信协议
│   ├── runtime/         # Android + JVMTI + Compose
│   ├── host/            # JDK 21 主机服务
│   └── gradle-plugin/   # Android variant 与构建集成
├── sample/             # 独立 composite build 示例
├── upstream/
│   ├── lock.properties
│   └── compose-hot-reload/ # 完整上游源码、示例、测试与维护工具
├── gradle/libs.versions.toml
├── tools/
└── docs/
```

独立的 [sample](../sample) 包含 Jetpack Compose 应用与本地 library 模块；完整上游源码保存在 [upstream/compose-hot-reload](../upstream/compose-hot-reload)。

## 模块与依赖方向

| 模块 | 职责 | 依赖 |
|---|---|---|
| [protocol](../modules/protocol) | 有界帧协议、握手、重载消息、UI 结果与错误 | Java 标准库 |
| [runtime](../modules/runtime) | 接收变更、追加 DEX、执行原子类重定义、替换资源、静态重初始化及 Compose 刷新 | protocol、art-dcevm、应用提供的 Compose |
| [host](../modules/host) | 管理设备会话、类与资源索引、重载尝试、lambda 隔离和 DEX 转换 | protocol、上游分析与 tooling 构件、D8 |
| [gradle-plugin](../modules/gradle-plugin) | 发现 debuggable variant、插桩入口、收集编译产物、配置启动与重载 | AGP、Kotlin Gradle API、上游 Gradle 构件 |

插件通过 Maven 坐标配置 host 进程与 runtime 依赖，不把 host 打包进设备。协议和 runtime 不依赖上游 JVM agent。包名、插件 ID `io.github.ytlog.compose.hot-reload.android` 和 Maven 坐标保留；资源更新使用设备协议版本 3，需配套安装新的 runtime。

Gradle project 名仍为 `hot-reload-android-core`、`hot-reload-android-runtime`、`hot-reload-android-host` 和 `hot-reload-android-gradle-plugin`，由顶层 settings 映射到四个职责目录。因此 composite build 能继续按原有坐标替换项目依赖。

## 重载流程

Gradle 编译 Kotlin / Java 变更，上游分析器计算需要失效的 Compose scopes；Android host 将变更转为 DEX 并经 ADB 发送到设备。设备端通过 JVMTI 与 art-dcevm 将已有类与接口以单个原子批次重定义，在主线程完成静态重初始化和 Compose group invalidation。

主机在转换 DEX 前隔离类与接口中的 lambda 调用点，并保留旧闭包所需的 D8 桥接方法；上游分析仍基于隔离前的字节码。具体处理见 [Android 说明](android.zh-CN.md)。

## 主机侧边界

- `host/device`：ADB、设备连接、协议收发与当前请求的 ART 错误关联。
- `host/dex`：D8、DEX accessor 读取和 lambda 隔离。
- `host/session`：安装时字节码/资源快照及运行中的分析索引，资源兼容性检查与确认摘要。
- `host/reload`：准备定义、分析、生成 DEX、设备执行与提交的一次事务。
- `host/upstream`：Java agent 桥接、分析兼容入口、orchestration 消息与 devtools 集成。

`ReloadAttempt` 持有单次重载的暂存分析。拒绝尝试直接丢弃；只有设备报告已有类已重定义或请求成功时，才提交分析索引。上一轮成功应用的 statics-only 定义会延续到下一次尝试。新增 DEX 与用户副作用的不可逆边界仍按 [Android 说明](android.zh-CN.md#尚未支持) 处理。

上游分析类型仍可用于主机分析和 DEX 转换；这里集中维护有兼容风险的调用，不复制或重写上游算法。Gradle 插件的 `upstream` 包集中管理上游快照/重载任务、pid 文件读取和启动参数。

## 实际使用的 JetBrains 能力

| 能力 | 上游模块 |
|---|---|
| 字节码分析、dirty scope 与基础设施 | analysis、core、annotations |
| 静态重初始化转换、tooling 启动、日志与 pid 文件 | agent 的平台无关入口 |
| 构建、主机、devtools 之间的消息 | orchestration |
| 快照、重载任务和启动参数 | gradle-plugin、gradle-core |
| 开发工具、自动编译、异常与状态重置 | devtools、devtools-api |
| 上述构件的传递构建需求 | gradle-idea、runtime-api、runtime-jvm，以及上游测试 fixture |

MCP、桌面示例、IDE 测试工程、Native/JS 目标和上游 CI/发布工具的源码继续保留在上游子工程；它们不成为 Android 顶层子项目。`prepareUpstream` 只选择所需 JVM 发布和公共元数据任务，不构建这些可选应用或运行桌面功能回归。

## 上游版本与构建

[lock.properties](../upstream/lock.properties) 记录来源、提交、版本及排除项。`.idea/` 和 `.run/` 属于本地 IDE 配置，不纳入 Git；其余上游文件与固定原始提交逐文件一致。顶层 Android 发布版本在 `gradle.properties`，依赖版本在 [版本目录](../gradle/libs.versions.toml)。Android 版本可以独立演进；上游版本目录、lock 和上游 `gradle.properties` 必须一致，顶层构建会检查。

```bash
./gradlew prepareUpstream
./gradlew androidCheck
./sample/gradlew -p sample assembleDebug
./sample/gradlew -p sample hotRunDebug --auto
```

所需上游构件发布到 `upstream/compose-hot-reload/build/repo`。使用 Maven 边界是为避开上游 Compose Gradle 插件的自举循环；上游不是 Android 根构建的 included build。

缺少构件时，编译前的 `verifyUpstream` 会指出 `./gradlew prepareUpstream`。这项校验不阻止顶层配置和准备任务本身。上游构件只从指定本地仓库解析，避免同版本远端构件掩盖准备步骤。

## 接入与验证

示例通过 `pluginManagement.includeBuild("..")` 使用根构建中的 Android 插件，顶层 `includeBuild("..")` 替换 host/runtime 坐标，并从同一上游本地仓库获取 JetBrains 构件。

`androidCheck` 包含协议与 host 测试、Gradle 插件校验和 runtime Debug AAR 构建。设备回归入口是 `python3 tools/device-test.py --serial DEVICE`，覆盖 18 项源码场景，并自动保存和恢复示例源码。`python3 tools/workflow-test.py --serial DEVICE` 单独验证 `--auto`、devtools 消息、composition 异常恢复和主机生命周期；orchestration 探针只编译在 host 的测试 classpath 中，不进入正式 host JAR 或 APK。

根目录的 `.github/workflows/android.yml` 在 GitHub Actions 上准备 SDK 和固定版本上游构件，运行 `androidCheck` 并构建独立示例。需要真机的测试保留为本地设备验证。

## 资源更新链路

Android 插件通过 AGP 的公开 `SingleArtifact.APK` 获取当前 variant 的完整资源产物。`hotResources<Variant>` 提取资源表、`res/`、`assets/` 和用于校验的 Manifest，借助 AGP 提供的 aapt2 记录资源 ID 与限定配置；保留条目的压缩方式，并将未压缩条目按 4 字节对齐，保持 `noCompress` 和文件描述符读取语义。不把 DEX、签名或 native 库放进资源快照。快照纳入现有 `hotSnapshot` 输入，因此手动 `reload` 和 `--auto` 共用同一链路。当前需要构建单个 universal APK，资源构建和合并由 AGP 负责。

host 的 `ResourceIndex` 以启动时安装的快照为基线，先检查 Manifest、资源名称/ID，以及已安装文件和限定配置是否被删除。不兼容修改会在提交任何类之前拒绝；内容更新与代码放在同一条设备请求中，只有设备成功响应才确认资源摘要，失败后可重试。协议版本为 3，旧 runtime 需要重新运行 `hotRun` 安装。

runtime 在主线程先解析新资源 provider，再执行类变更，接着用公开的 [ResourcesLoader](https://developer.android.com/reference/android/content/res/loader/ResourcesLoader) / [ResourcesProvider](https://developer.android.com/reference/android/content/res/loader/ResourcesProvider) 替换共享 provider，最后重初始化静态字段并刷新 Compose。Application、已有和后续创建的 Activity 共享 loader。开发入口独立于 devtools 开关，更新时提供新的 Resources 和 Context，并清理矢量图缓存以释放旧 Theme/Resources。Android 的 Theme 按样式序列比较并去重重复样式，runtime 用每个二进制位独立的空样式标记资源版本，使 Compose painter 的主题缓存键随更新变化，不改变主题属性。普通 `remember` 状态保留，不调用 Activity recreation。类定义、已追加 DEX、资源应用后的用户静态初始化等副作用，仍不构成可完整回滚的事务。

`python3 tools/resource-test.py --serial DEVICE` 验证资源内容、代码混合更新和拒绝后恢复，`--auto` 验证保存资源触发自动重载。具体使用范围见 [资源更新](android.zh-CN.md#资源更新)。

## Compose 依赖刷新

host 比较脏组与已应用分析中的完整作用域树。只因依赖改变而变脏、树本身未改的组单独发送；无状态作用域的常量变化在分组及调用布局相同时也只重组。布局、缓存初始化器及其求值依赖变化时沿用上游组重置。分析扩展区分缓存计算中的直接调用与缓存 lambda 对象的方法体依赖，修改回调方法体不会因此重跑其缓存初始化。依据 ClassFile 调试信息中的缓存计算区间限定依赖范围，不把缓存外的正常渲染调用算进初始化器；缺少解析／缓存区间元数据时保守重置。设备协议 3 携带两类组。

runtime 通过 Compose 的注册观察器同步枚举现有 composition，立即释放临时观察器，再调用 `ControlledComposition.invalidateAll`；通过 Compose tooling 的 composition data 枚举现有 restart scope，标记一次强制重组，避免父级失效合并子级失效后因参数未变而跳过新方法体；不丢弃槽位、不复制应用状态或重跑构造器，让带 key 的子组件可移动并保留状态。应用提供的 Compose 若缺少该观察 API，则保留原组重置路径，并明确记录 remember 保留不可用。Compose 存在界面异常时，沿用其组失效恢复路径以续跑失败的 composition，普通作用域失效本身不能完成异常恢复。本次设备用例只证明样例实际解析的 Compose 版本，不代表所有旧版本。

## JB orchestration 与 Android 传输边界

构建／DevTools ↔ Host 直接使用固定版本的 JB orchestration。
Host 的请求处理和设备执行入口按上游逻辑适配；DEX 转换、ADB 转发及
Host ↔ Android LocalSocket 属于本项目，不能当成上游 JVM Agent 的原样实现。

重载沿途直接传递 JB `OrchestrationMessageId` 的公开编码，设备结果及 UIRendered
返回同一 ID。不再生成第二套 UUID，也不保存随重载次数增长的 ID 映射。
失效会话在准备 DEX 前拒绝新请求；不确定提交仍禁止自动重放。

Host 和设备握手共用 `IoDeadline`，期限覆盖阻塞 I/O 和断续到达的数据。
设备握手最多 5 秒；空闲接收不设期限，收到一帧的第一个字节后最多 120 秒
接收完整帧。设备使用单独的 writer 线程按入队顺序发送，单次写入最多 10 秒；
Compose 的渲染／异常回调只入队，不直接写 Socket。队列最多 256 条，
超限或写入失败会关闭该连接，避免静默丢失重载应答。关闭时清空旧连接队列，
新连接有独立队列。设备端先 shutdown 输入／输出，再关闭 LocalSocket。
Android 冻结进程时，设备定时器也无法执行。Host 在发送重载或重置前，通过
调试 Provider 唤醒现有运行时并核对 PID，不启动 Activity，也不替换页面状态。

这些修改没有改变协议 3 的帧布局，也没有修改上游源码、ART 类重定义事务或
应用状态模型。连接释放不表示未知重载已回滚；Host 不会自动接回并重试。

定向设备故障入口（先启动已安装的固定包，并退出占用连接的 hotRun）：

```sh
python3 tools/runtime-transport-test.py --serial DEVICE_SELECTOR --device-id apiNN \
  --output build/evidence/runtime-transport-apiNN
```

工具不安装、卸载或重启应用，检查无应答／半握手、空闲后半帧超时及再次握手，
并核对原 PID 与 APK 哈希。半帧检查使用生产的 120 秒期限；可用 `--case partial-frame`
或 `--case handshake` 缩小失败复验范围。工具要求测试页面保持前台，前台或进程
改变会记录为无效运行。这不替代真实页面状态
保留、编译／资源更新、JNI 模式或设备完整矩阵。原始记录仅存本地忽略目录，
每次运行须使用新的输出目录，避免覆盖之前的失败记录。
