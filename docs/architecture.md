# 工程架构

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
| [runtime](../modules/runtime) | 接收变更、追加 DEX、执行原子类重定义、静态重初始化及 Compose 刷新 | protocol、art-dcevm、应用提供的 Compose |
| [host](../modules/host) | 管理设备会话、类索引、重载尝试、lambda 隔离和 DEX 转换 | protocol、上游分析与 tooling 构件、D8 |
| [gradle-plugin](../modules/gradle-plugin) | 发现 debuggable variant、插桩入口、收集编译产物、配置启动与重载 | AGP、Kotlin Gradle API、上游 Gradle 构件 |

插件通过 Maven 坐标配置 host 进程与 runtime 依赖，不把 host 打包进设备。协议和 runtime 不依赖上游 JVM agent。现有包名、插件 ID `io.github.ytlog.compose.hot-reload.android`、Maven 坐标及设备协议保持兼容。

Gradle project 名仍为 `hot-reload-android-core`、`hot-reload-android-runtime`、`hot-reload-android-host` 和 `hot-reload-android-gradle-plugin`，由顶层 settings 映射到四个职责目录。因此 composite build 能继续按原有坐标替换项目依赖。

## 重载流程

Gradle 编译 Kotlin / Java 变更，上游分析器计算需要失效的 Compose scopes；Android host 将变更转为 DEX 并经 ADB 发送到设备。设备端通过 JVMTI 与 art-dcevm 将已有类与接口以单个原子批次重定义，在主线程完成静态重初始化和 Compose group invalidation。

主机在转换 DEX 前隔离类与接口中的 lambda 调用点，并保留旧闭包所需的 D8 桥接方法；上游分析仍基于隔离前的字节码。具体处理见 [Android 说明](android.md)。

## 主机侧边界

- `host/device`：ADB、设备连接、协议收发与当前请求的 ART 错误关联。
- `host/dex`：D8、DEX accessor 读取和 lambda 隔离。
- `host/session`：安装时字节码快照及运行中的分析索引。
- `host/reload`：准备定义、分析、生成 DEX、设备执行与提交的一次事务。
- `host/upstream`：Java agent 桥接、分析兼容入口、orchestration 消息与 devtools 集成。

`ReloadAttempt` 持有单次重载的暂存分析。拒绝尝试直接丢弃；只有设备报告已有类已重定义或请求成功时，才提交分析索引。上一轮成功应用的 statics-only 定义会延续到下一次尝试。新增 DEX 与用户副作用的不可逆边界仍按 [Android 说明](android.md#尚未支持) 处理。

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

资源和 Manifest 的边界保持原状，见 [当前限制](android.md#尚未支持)。
