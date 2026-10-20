# Compose Hot Reload for Android

本项目实现 Android Gradle 接入、主机服务与设备运行时，并复用 JetBrains Compose Hot Reload 的分析器、重载任务、通信和开发工具。本文说明 Android 使用方式及 art-dcevm alpha.3 的变更语义；模块划分与依赖边界见 [工程架构](architecture.md)。

## Android 实现与复用的功能

| 上游（JVM 桌面） | Android | 说明 |
|---|---|---|
| `hot-reload-agent` 的 `premain`：devtools、orchestration、pid 文件、日志 | **`hot-reload-android-host`**（主机 JVM 进程） | 直接调用上游 agent 的 `startDevTools` / `startOrchestration` / `createPidfile` / `startWritingLogs`（见 `UpstreamAgent.java`）。上游 orchestration 客户端依赖 `java.lang.ProcessHandle`，ART 上没有这个类，所以 agent 的这一部分放在主机上运行 |
| `runtimeTracking.kt`：记录已加载类的 `ClassInfo` | `ApplicationIndex` | ART 不提供类文件字节，改为从安装 APK 时用的类文件建立索引；`currentApplicationInfo` / `pendingRedefinitions` 的语义与上游相同 |
| `reloadRequestHandler.kt` / `reload.kt` | 主机侧 `reload.kt` | 失败请求的变更保留到下次一起重试；`verifyRedefinitions`、`resolveDirtyScopes` 用的都是上游分析器 |
| `staticsInitialization.kt` | 主机：调用上游 `transformForStaticsInitialization`；设备：执行 `$chr$clinit` | `AllDirty` / `ChangedOnly`、拓扑排序、“只处理已加载的类”这几点都与上游一致 |
| `Instrumentation.redefineClasses` + Swing EDT | **`hot-reload-android-runtime`**：JVMTI `RedefineClasses` + [ART DCEVM 0.1.0-alpha.3](https://github.com/ytlog/art-dcevm)，在主线程执行 | 已有类和接口以单个原子批次重定义，然后执行静态重初始化和 group invalidation；引擎在 API 30–37 arm64 上提供接口能力，具体变更由事务校验 |
| `compose.kt`（hot reload 模式、`invalidateGroupsWithKey`、硬重置） | `compose.kt` | 同样通过反射调用 `Recomposer.Companion` |
| `WindowInstrumentation`：把 `ComposeWindow.setContent` 替换为 `DevelopmentEntryPoint` | 用 AGP ASM 插桩 `androidx.activity.compose.ComponentActivityKt` 中的 `ComposeView.setContent` | 同样只插桩库内部这一个调用点，用户代码不变 |
| `hot-reload-runtime-jvm`：`DevelopmentEntryPoint`、`HotReloadState`、`recomposerErrors`、`resetComposition` | 同名 Kotlin 文件 | 发送 `UIRendered` / `UIException`，处理 `CleanCompositionRequest` / `RetryFailedCompositionRequest`（Android 上没有 `WindowId`，所以为 `null`） |
| Gradle：`hotSnapshot*` / `hotReload*` / `reload` / `hotRun*` | 直接注册上游的 `ComposeHotSnapshotTask` / `ComposeHotReloadTask`；`hotRunDebug` 用上游的 `withComposeHotReload {}` 传递全部参数 | 上游的 devtools 窗口和 Recompiler（`--auto`）可以直接使用 |

主机与设备之间（`hot-reload-android-core`）是一套有长度上限的二进制帧协议，经由 `adb forward` 连到应用里的 abstract unix socket。设备端只接受应用自身、shell 和 root UID 的连接。对 orchestration 来说，这个 Android 应用和上游的桌面应用没有区别：`ReloadClassesRequest` → `ReloadClassesResult` → `InvalidatedComposeGroupMessage` → `UIRendered`。

### 由平台决定的差异

1. **lambda 隔离**（`LambdaIsolation.kt`）。JVM 在链接 call site 时为 lambda 生成匿名类，而 D8 会把 lambda 脱糖成按出现顺序编号的具名类（`Foo$$ExternalSyntheticLambda0`）。新增一个 lambda 会让编号整体后移，再去重定义这些合成类就变成了 ART 不允许的继承层级变更。处理方式是：在转成 DEX 前，把被重定义的类或接口里的 lambda call site 挪进每次 reload 都唯一的辅助类，同时保留 D8 原来生成的 `$r8$lambda$*` 桥接方法，让 reload 之前创建的 lambda 实例照常执行（与 JVM 上一样，执行的是新代码）。接口的桥接必须为合法的 public static 方法，静态调用也使用接口方法引用。minSdk 30 使用原生 default 方法，没有 `$-CC` 伴生类。上游分析器看到的仍然是原始字节码。
2. **新增类**通过 ART 的 `add_to_dex_class_loader` 追加到应用的 ClassLoader；**APK 里还没加载的类**也要重定义。上游会跳过这类类，因为 JVM 之后会从 hot classpath 加载新版本；ART 却会从 APK 加载旧版本。追加 DEX 不能撤销；重定义失败时，已追加的类保留以供重试，已有类的原子重定义整批不生效。
3. devtools 默认使用 detached 模式，因为没有应用窗口可以吸附。可以通过 `compose.reload.devToolsDetached` 覆盖。
4. 主机进程用标准 JDK 21 运行，不需要 JetBrains Runtime。应用运行在 ART 上，DCEVM 由 art-dcevm 提供。
5. Android 主机在设备执行事务前完成分析，所以每次尝试暂存 `ClassInfo`，设备确认重定义成功后才更新索引。失败的源码变更仍保留到下一次请求；撤回的类不会留下失败尝试的分析结果。

接口与实现类不分开提交，也不依赖文件遍历顺序：它们与本次其他已有类一起进入同一个 `RedefineClasses` 调用，由 art-dcevm 校验和执行。拒绝原因优先使用 `capabilities().lastRejection`，经设备协议原样进入上游 `ReloadClassesResult.errorMessage`；devtools 使用这个字段。若 ART 只在 logcat 的 `FAILURE TO REDEFINE` 后解释原因，主机按进程和本次请求标记取回原文；不会把旧请求的错误混入本次结果。

静态初始化沿用上游：`transformForStaticsInitialization` 明确跳过接口，保持接口字段 final，也不为接口生成 `$chr$clinit`。已初始化接口的初始化代码不会重跑，新增静态字段取默认值。Kotlin companion 的存储类是普通类，仍采用上游重初始化；`const val` 的调用方使用编译时内联值，未重新编译的调用方不会自动获得新值。

## 使用

依赖说明：
- art-dcevm 通过 Maven Central 获取：`io.github.ytlog:artdcevm:0.1.0-alpha.3`；本工程使用 compileSdk 37，保留 `android.suppressUnsupportedCompileSdk=37`。
- JetBrains 构件通过本仓库的 `prepareUpstream` 发布到本地 Maven 仓库。该源码子工程独立构建，以避开 Gradle 插件自举循环；Android 插件与示例之间仍使用 composite build。

从源码构建设备 runtime 还需要 NDK `27.2.12479018` 和 CMake `3.22.1`，用于将本项目的 [agent.cc](../modules/runtime/src/main/cpp/agent.cc) 编译成 `libcompose_hot_reload_android.so`。art-dcevm 使用 Maven 发布的预编译 AAR，其自身源码构建工具版本不影响这里的配置。

```bash
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew prepareUpstream
```

运行示例（需要从进程启动起就是 debuggable 的 arm64 设备，API 30+；多台设备时设置 `ANDROID_SERIAL`）：

```bash
./sample/gradlew -p sample hotRunDebug
```

修改 `sample/src/main/java/dev/compose/hotreload/sample/MainActivity.kt` 后执行：

```bash
./sample/gradlew -p sample reload
```

保存即自动重载（上游的 Recompiler，运行在 devtools 中）：

```bash
./sample/gradlew -p sample hotRunDebug --auto
```

`hotRunDebug` 会先安装 APK，再启动主机进程。主机进程负责启动应用，生命周期与应用绑定：应用退出，主机就结束；主机被停止或收到 `ShutdownRequest`，应用也随之退出。这与上游 `hotRun` 的行为一致。

接入其他工程的完整配置见 [首页接入步骤](../README.md#接入已有工程)：在 `pluginManagement` 和 settings 顶层引入本仓库的根构建，并在插件仓库、依赖仓库中加入准备好的本地 Maven 仓库。然后在应用模块现有的 `plugins` 中添加热重载插件：

```kotlin
plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    id("io.github.ytlog.compose.hot-reload.android")
}
```

插件只为 debuggable 的 build type 添加运行时（`<buildType>Implementation`）。运行时通过非导出的 ContentProvider 在 `Application.onCreate` 之前启动。

### alpha.3 代码变更

保存源码后执行 `reload`，或使用 `hotRunDebug --auto` 自动编译重载。应用和本地 library 模块的变更可以一起提交。以下列出 alpha.3 引擎支持的主要变化及使用时需要处理的行为；具体支持受编译器、分析器和实际 ART 事务约束，本工程的设备覆盖见 [验证](#验证)。

| 修改 | 使用方式与结果 |
|---|---|
| 函数体、Compose UI、顶层属性与 lambda | 重新编译并重载；普通类静态重初始化和 Compose 刷新由本项目处理，状态保留仍取决于 scope 分析 |
| 新增类或源文件 | 主机生成 DEX 并追加到应用类加载器，再由调用方使用；删除源码文件不会卸载进程中已经加载的类 |
| 普通类字段、方法、构造器的新增、退役或改签名／类型 | 引擎支持相应结构演进；改签名后应同步更新调用方。已有对象保留兼容字段，新字段取零值或 `null`，不补跑构造器 |
| 接口方法新增、删除或改签名 | 支持 default / abstract / static 等成员演进和 public abstract/default 互换；修改接口与实现类时可同批提交 |
| 接口 static 字段、父接口及普通类实现的接口 | 引擎支持相应字段与接口关系演进；原始字段 offset、旧类型引用和旧调用需按新版语义处理 |
| 父类与类型修饰符 | 支持受约束的父类放宽及修饰符变化；父类收窄、有直接实例的类变为 abstract 等仍会拒绝 |

例如，把接口的 `label(): String` 改成 `renamedLabel(): String` 时，应一起更新调用方；旧签名退役后，仍引用它的代码或缓存方法对象会抛 `NoSuchMethodError`。新增 abstract 方法时可以同批给实现类补齐；也可以先重定义接口，但旧实现类实际调用缺失方法时会抛 `AbstractMethodError`，不能把事务成功理解为所有旧调用都能继续工作。

default 冲突可以在事务中被接受，实际调用冲突方法时抛 `IncompatibleClassChangeError`。static / 实例方式切换或 virtual 改为 private 时，旧调用可能分别抛 `IncompatibleClassChangeError` 或 `IllegalAccessError`，并且受运行时服务限制。删除后恢复同名成员会创建新成员，旧反射、JNI 或 MethodHandle 引用保持退役语义。

### 失败处理

初始化成功后直接提交 `RedefineClasses`，不要用 `capabilities()` 的布尔值或状态字符串提前阻止提交。非零返回表示失败，主机将错误码与 `lastRejection` 回传到 devtools。撤回不支持的变更或修正源码后再次 `reload`，之前尚未应用的源码变更会一起重试；已有类定义保持批次原子性。

新增 DEX、类加载和用户副作用不能完整回滚。如果引擎初始化失败或返回运行时不可用错误，应查看诊断、修正环境并重启应用，而不是反复提交同一批次。已初始化接口不会重跑初始化器，`const val` 的内联调用方仍需重新编译。

### 版本

与上游一致：Gradle 8.14.4、AGP 8.11.1、Kotlin 2.3.21、Compose 1.11（BOM `2026.06.01`）。本工程使用 compileSdk 37，而 AGP 8.11.1 官方最高支持 36，因此设置了 `android.suppressUnsupportedCompileSdk=37`。

运行时启动时会打印 `DcevmRuntime.capabilities()`，这些字段仅用于诊断，不作为提交前的准入条件。初始化成功后，设备直接通过标准 `RedefineClasses` 提交批次；以实际返回值判断成功或失败，失败时读取 `lastRejection`。新版不再使用已移除的 `interfaceAbstractMethodAddition` / `interfaceAbstractDispatch` 字段。

alpha.3 支持接口方法退役、改签名和新增 abstract 方法。缺少 abstract 实现时可以成功重定义，实际调用缺失方法时抛 `AbstractMethodError`；已退役方法的旧调用抛 `NoSuchMethodError`。引擎的接口能力覆盖 API 30–37 arm64，其中 API 33–36 有引擎真机验证，其余版本仍需补充设备覆盖。完整语义和条件见[固定版本的引擎说明](https://github.com/ytlog/art-dcevm/blob/a61e98e29c2ef795127ee7654ef23848fb478646/docs/LIMITATIONS.md)。

## 验证

单元测试：

```bash
./gradlew :hot-reload-android-core:test :hot-reload-android-host:test
```

2026-10-20：升级 alpha.3 后通过 17 个单元测试（protocol 6、host 11）、Gradle 插件校验和 runtime Debug AAR 构建。单元测试包含接口 lambda 的 D8 桥接、接口与 companion 的上游静态初始化转换、重载事务提交、失败分析隔离和 logcat 请求关联。

真机端到端测试（arm64、API 30+、debuggable；完整接口回归还需接口能力可用）。脚本会启动 `hotRunDebug`，依次修改源码并执行 `reload`，用 uiautomator 断言界面和 `remember` 计数，结束时恢复源码：

```bash
python3 tools/device-test.py --serial DEVICE
```

2026-10-20 使用 alpha.3 在 Android 16 / API 36 arm64 真机上执行完整脚本，**18/18 通过，退出码 0**。检查内容如下：

| 场景 | 检查内容 |
|---|---|
| 修改函数体 | UI 更新，状态保留 |
| 修改顶层 `val`（静态重初始化） | UI 更新，状态保留 |
| 修改 library 模块（`:lib`） | UI 更新，状态保留 |
| 修改 Java lambda；在 Java 里插入新 lambda | UI 更新，状态保留 |
| 新增类（放在新文件中） | UI 更新，状态保留 |
| 在 Kotlin 已有 lambda 之前插入新 lambda | UI 更新，新旧 lambda 行为正确；状态重置（原因见下） |
| 修改接口 default 方法里的 lambda | UI 更新，状态保留；首次事务后接口 capability 变为 verified |
| 在接口 default 方法里插入新 lambda | UI 更新，状态保留 |
| 同批修改接口与实现类 | UI 更新，状态保留 |
| 给接口新增 default 方法，在调用方使用 | UI 更新，状态保留 |
| 接口 default 方法改名，同批更新调用方和 library | 旧签名退役，新调用和 library 更新生效，状态保留 |
| 同批新增抽象接口方法、实现类提供实现、调用方使用 | UI 更新，实际接口分派正确，状态保留 |
| 接口新增抽象声明，复用实现类原有方法，通过接口调用 | UI 更新，状态保留 |
| 动态追加并使用另一个具体接口实现类 | UI 更新，状态保留；系统属性保留实例以供后续场景验证 |
| 将有直接实例的类改为 abstract，同批另有 library 修改 | 返回 70，原生原因进入 `ReloadClassesResult`；整批 UI 不变，撤回后 library 待处理修改重试成功，状态保留 |
| 新增抽象方法但另一个已加载、未提交的具体类缺少实现 | 事务成功；通过该旧实现类实际调用缺失方法时捕获 `AbstractMethodError`，其余 UI 和 library 更新生效 |
| 改变文件行数 | 用到 `remember` 的 composable 状态重置（上游行为，原因见下） |

当前 alpha.3 脚本先动态加载一个 `LoadedLabels`，再从本次源码中移除其声明，并给接口新增 abstract、给主实现类补齐。事务成功后，通过接口实际调用旧实现类缺失的方法，断言 `AbstractMethodError`。另用系统属性持有一个 `LoadedLabels` 实例，再把该类改为 abstract，验证返回 70、整批 UI 不变、原生原因回传，以及撤回后 library 待处理修改重试成功。测试使用可正常编译的 Kotlin 源码，结束时恢复全部源码。

可独立运行相关回归；后两条命令会自动包含各自的前置场景：

```bash
python3 tools/device-test.py --serial DEVICE --only "interface abstract batch"
python3 tools/device-test.py --serial DEVICE --only "interface abstract missing implementation"
python3 tools/device-test.py --serial DEVICE --only "live class abstract rejection and retry"
```

[`tools/repro-artdcevm-alpha1`](../tools/repro-artdcevm-alpha1) 保留旧版失败证据，不代表 alpha.3 的当前限制。

已核对 Maven Central alpha.3 AAR 的引擎动态库与 APK 内打包内容一致，AAR SHA-256 为 `ed1de8df09aa0bc5f6af00b389acf3b6dfcb77cb8bb579deb35555f5600b6554`。

每次脚本执行在 `sample/build/device-test-evidence/<run>/` 保存 source patch、capabilities、设备与主机日志和汇总。源码回归覆盖本项目的常用流程，不代表引擎的所有结构演进组合都已逐项运行。

开发工作流另行验证：

```bash
python3 tools/workflow-test.py --serial DEVICE
```

该脚本检查 7 项流程：devtools 子进程与 tooling 连接、`--auto` 保存即编译／重载且保留状态、`CleanCompositionRequest` 清空计数、composition 异常经 `UIException` 上报且应用存活、修复源码后自动恢复渲染、`RetryFailedCompositionRequest` 重建 composition、停止主机后应用随之退出。它不手动调用 `reload`；异常与重载消息均由实际应用产生。

2026-10-20 使用 alpha.3 在上述 API 36 真机上执行，**7/7 通过**，包含自动重载、composition 异常恢复与主机生命周期。

### 状态重置：上游行为

Kotlin 2.2+ 会在内联函数局部变量名后附加行号（例如 `$i$a$-cache-...$count$2\3\68\0`）。内联代码使用的是文件末尾之后的虚拟行号，所以**只要文件总行数变化**，这些名字就会变。上游 `ScopeHash` 已经特意忽略了带行号的 source information 和 trace 字符串，但会把局部变量名计入 hash，于是使用 `remember` 的 composable 被判定为 dirty，状态随之重置。桌面端使用同一个 Kotlin 编译器，行为相同。按"以上游为准"的原则，Android 侧不做修补，正确的做法是向上游修复 `ScopeHash`。

## 尚未支持

以下按 art-dcevm `0.1.0-alpha.3` 的[固定版本限制](https://github.com/ytlog/art-dcevm/blob/a61e98e29c2ef795127ee7654ef23848fb478646/docs/LIMITATIONS.md) 汇总，具体变化仍由实际事务检查；引擎文档列出的能力不代表本工程已逐项验证。

| 变化或运行时条件 | 限制或语义 |
|---|---|
| 运行库缺少必要命名服务或声明探测不通过 | 初始化失败或事务拒绝；诊断字段不能代替实际提交结果 |
| 接口新增 native 方法 | 仍不支持，返回 63 |
| boot 接口、历史 pointer-id 标记或接口事务使用指针型 JNI ID | 可能返回 79；事后切换 ID 模式不能修复已缓存的指针 |
| 成员映射或方法选择服务不可用 | 相应的接口方法退役、字段或层级变化仍可能被拒绝 |
| 类有直接实例却改为 abstract | 返回 70；本工程以保留实例的源码回归验证整批拒绝和恢复 |
| 改父类但新父类不扩展原父类 | 仍按父类收窄拒绝，返回 66 |
| 类型种类变化、final 与已加载子类型冲突 | 有独立的准入条件，不能视为任意结构转换均可接受 |
| 新增 abstract 方法但旧实现类缺少实现 | 可以成功重定义；实际调用缺失的方法时抛 `AbstractMethodError` |
| 接口方法退役或改签名 | 旧签名退役，旧调用或缓存引用抛 `NoSuchMethodError`；恢复同名成员不会使旧引用复活 |
| 新的 default 冲突 | 可以接受事务，调用冲突方法时抛 `IncompatibleClassChangeError` |
| static / 实例或 virtual / private 调用方式变化 | 需要相应服务，旧调用可能抛 `IncompatibleClassChangeError` / `IllegalAccessError`；缺少服务时仍可能返回 71 |
| 新字段、新父类字段或接口初始化变化 | 已有对象不重跑构造器，新增字段取零值或 `null`；已初始化接口不重跑初始化器 |
| 原始字段 offset | `Unsafe`、`Atomic*FieldUpdater` 等缓存不会自动修复，布局变化可能使其失效 |
| ART 固有条件 | 必须从启动起 debuggable；数组、基本类型、String、mirror、代理类自身、Thread 及其父类型等仍受限制 |

新增类追加 DEX、类加载器缓存，以及已初始化接口不重跑初始化等进程副作用不能由拒绝事务完全撤销。原子性针对已有类定义，不能承诺恢复新增 DEX、任意用户副作用或原始 offset 缓存。

- Android 资源、manifest 的热替换（需要重新安装）。
- 多进程应用、minified variants。
- devtools 中依赖窗口的功能：截图、语义树、UI 操作、窗口尺寸。
- `ReloadEffects` 叠加效果。
- 上游 `hot-reload-runtime-api` 已经发布了 Android 变体，但运行时还没有接上它的 `AfterHotReloadEffect` / `invokeAfterHotReload` 钩子。
- 尚未接入 IDE 插件的 Android 运行配置。
