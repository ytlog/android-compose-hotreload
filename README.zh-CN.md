# Android Compose Hot Reload

[English](README.md) | **简体中文**

**在 Android 真机上热重载 Jetpack Compose 应用。** 修改 Kotlin / Java 代码、Compose UI 和已有 Android 资源后，编译变更并更新正在运行的应用；支持的修改无需重新安装 APK 或重启进程，并尽可能保留 `remember` 状态。

本项目实现了 Android Compose 热重载所需的 Gradle 集成、主机服务和设备运行时：将编译后的代码变更转换为 DEX 并发送到设备，通过 [art-dcevm](https://github.com/ytlog/art-dcevm) 在 ART 上重定义类，完成静态重初始化与 Compose UI 刷新。其中复用 [JetBrains Compose Hot Reload](https://github.com/JetBrains/compose-hot-reload) 的字节码分析、重载任务、通信和开发工具。热重载用于开发阶段的 debuggable 应用。

[接入已有工程](#接入已有工程) · [源码构建与示例](#源码构建与示例) · [Android 详细说明](docs/android.zh-CN.md) · [工程架构](docs/architecture.zh-CN.md) · [许可证](LICENSE.txt)

真实页面连续编辑与 Maven Local 引擎联调见[验证指南](docs/real-edits.zh-CN.md)。

## 可以做什么

- **修改 Compose UI 和函数实现**：重新编译变更，在运行中的应用里查看结果。
- **更新 Android 资源**：修改已有资源的字符串、颜色、drawable、raw 和 assets 内容，沿用手动或自动重载流程，详见 [资源更新](docs/android.zh-CN.md#资源更新)。
- **更新应用与本地 library 模块**：支持顶层属性的静态重初始化、添加类，以及 Kotlin / Java lambda 的变更。
- **更新类与接口结构**：支持字段、方法的增删改、接口 default / abstract 演进，以及接口与实现类同批重载；具体变更由运行时事务校验，详见 [alpha.3 使用说明](docs/android.zh-CN.md#alpha3-代码变更)。
- **手动或自动重载**：使用 Gradle 的 `reload` 任务，或以 `--auto` 模式启动，保存源码后自动编译并重载。
- **使用上游开发工具**：通过 devtools 查看重载结果、处理 composition 异常、重置 UI 状态。
- **查看失败原因并重试**：ART 原生拒绝原因回传到 devtools；失败的源码变更保留，撤回不支持的改动或补齐实现后可继续重载。

在初始化逻辑与 Compose 槽布局保持兼容时，受支持的业务逻辑和已有回调修改会保留普通 `remember` 状态。已验证原 AndroidView、snapshotFlow / derivedStateOf 和生命周期收集的相关编辑，不需要把状态移到 ViewModel。具体改动与条件见[真实页面验证](docs/real-edits.zh-CN.md)和[框架用例](docs/framework-edits.zh-CN.md)。

## 接入已有工程

适用于已有的 Android application + Jetpack Compose 工程。以下为当前可用的源码接入方式，通过 Gradle composite build 引入本仓库；本地 Maven 目录用于解析源码构建的固定上游依赖。首次使用请先完成文末的 [源码准备](#1-准备源码与构件)。

以下假设本仓库与应用工程位于同一父目录，路径按实际位置调整。配置片段请合并到应用现有配置中。

### 1. 配置应用工程的 settings.gradle.kts

```kotlin
pluginManagement {
    includeBuild("../android-compose-hotreload")
    repositories {
        maven(file("../android-compose-hotreload/upstream/compose-hot-reload/build/repo"))
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven(file("../android-compose-hotreload/upstream/compose-hot-reload/build/repo"))
        google()
        mavenCentral()
    }
}

includeBuild("../android-compose-hotreload")
```

### 2. 在应用模块应用插件

在 `app/build.gradle.kts` 现有的 `plugins` 中添加：

```kotlin
plugins {
    // 保留已有的 Android、Kotlin Android 和 Compose compiler 插件
    id("io.github.ytlog.compose.hot-reload.android")
}
```

应用需要 `compileSdk >= 37`，调试 variant 需要 `minSdk >= 30`，并在 `arm64-v8a` 设备上运行。可以通过现有 flavor 隔离调试的 `minSdk` 配置；应用继续使用自己的 Compose 依赖和 `targetSdk`。

插件会自动为 debuggable build type 添加设备 runtime，并接入 Compose 热重载入口。完整配置可参照 [示例 settings](sample/settings.gradle.kts) 和 [应用模块配置](sample/build.gradle.kts)。

### 3. 启动并重载你的应用

连接已授权的 ADB 设备，在**你的应用工程根目录**执行：

```bash
./gradlew :app:hotRunDebug --auto
```

任务会安装并启动应用、打开 devtools；保持终端运行，保存 Kotlin / Java 源码或受支持的资源修改后自动编译并重载。连接多台设备时，先用 `ANDROID_SERIAL` 指定目标设备。

如果需要手动重载，启动时去掉 `--auto`，修改代码后在另一个终端执行：

```bash
./gradlew :app:reload
```

`:app` 按实际应用模块名替换；存在 product flavor 时，启动任务对应具体 variant。应用退出时主机进程随之结束，停止主机进程也会关闭应用。

## 环境要求

以下为当前验证的构建配置：

| 项目 | 要求或配置 |
|---|---|
| Android 设备 | Android 11–17 / API 30–37，`arm64-v8a`，通过 ADB 连接；具体变更以运行时事务校验为准 |
| 应用 | 从进程启动起就是 debuggable，使用 Jetpack Compose |
| 主机 Java | JDK 21 |
| Android SDK | Platform 37；应用的 `compileSdk` 至少为 37 |
| Native 构建工具（构建设备 runtime） | NDK `27.2.12479018`、CMake `3.22.1`，用于编译本项目的 JVMTI 代理 |
| Gradle / AGP / Kotlin | Gradle `8.14.4`、AGP `8.11.1`、Kotlin `2.3.21` |
| ART 重定义引擎 | `io.github.ytlog:artdcevm:0.1.0-alpha.4` |

art-dcevm 及其依赖要求 `compileSdk 37`。使用 AGP `8.11.1` 时，可在应用工程的 `gradle.properties` 中加入 `android.suppressUnsupportedCompileSdk=37`，配合当前配置构建。

## 当前边界

- 类与接口的结构演进受运行时服务、boot 类型和历史指针型 JNI ID 等条件限制；新增接口 native 方法等变更仍会被拒绝，详见 [完整限制](docs/android.zh-CN.md#尚未支持)。
- 已初始化接口的初始化代码不会重跑；Kotlin companion 存储类按普通类重初始化，`const val` 的既有内联调用方需要重新编译。
- 新增字段不会补跑已有实例的构造器；缓存的原始字段 offset 可能失效，已退役成员的旧引用不会因同名成员恢复而复活。改变调用方式或移除成员后，旧调用可能抛出相应的链接错误。
- 原子性针对已有类定义。新增 DEX、类加载器缓存和任意用户副作用不能由失败事务完全回滚。
- 新增、删除资源或改变资源 ID、删除已安装的资源文件或限定配置，以及 Manifest 修改，需要重新安装；已有资源内容支持热更新。
- 尚未支持多进程应用与 minified variants。
- devtools 的桌面窗口功能尚未接入 Android，包括截图、语义树、UI 操作和窗口尺寸控制。
- 尚未接入 IDE 插件的 Android 运行配置，以及 `ReloadEffects`、`AfterHotReloadEffect` / `invokeAfterHotReload` 钩子。
- 修改 `remember` 初始化逻辑或 Compose 槽布局可能重置受影响的组；缺少分析元数据或 Compose 刷新 API 不可用时也可能回退到重置。受支持的延后回调修改会保留其所属状态，但不保证任意槽迁移或挂起协程状态迁移。`derivedStateOf` 在依赖变化使缓存失效后执行新计算，单纯重载代码不会强制使该缓存失效。

更多平台差异和限制见 [Android 说明](docs/android.zh-CN.md#尚未支持)。

## 源码构建与示例

以下命令在本仓库中执行，用于准备接入所需的构件或体验仓库示例。

### 1. 准备源码与构件

```bash
git clone https://github.com/ytlog/android-compose-hotreload.git
cd android-compose-hotreload

# 替换为本机 Android SDK 的实际路径
export ANDROID_HOME="/path/to/Android/sdk"

./gradlew prepareUpstream
```

这一步构建固定版本的 JetBrains JVM 构件，并发布到 `upstream/compose-hot-reload/build/repo`。首次接入前执行一次，更新上游源码后重新执行；Android 插件、host 和 runtime 由应用的 composite build 自动构建。

### 2. 运行本仓库示例

连接设备后，在本仓库根目录执行：

```bash
./sample/gradlew -p sample hotRunDebug --auto
```

修改 [示例 MainActivity.kt](sample/src/main/java/dev/compose/hotreload/sample/MainActivity.kt) 后自动重载。手动模式去掉 `--auto`，再在另一个终端执行 `./sample/gradlew -p sample reload`。

更多构建与模块说明见 [工程架构](docs/architecture.zh-CN.md)。
