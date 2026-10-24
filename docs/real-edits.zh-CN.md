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

用例修改工作区源码，结束时恢复；运行前应提交或备份工作。不要同时在同一 checkout 运行两个用例脚本。脚本会拒绝覆盖已有的 `EditFormatter.kt`。原始日志、逐步源码差异、UI 快照、截图和报告只保存在已忽略的 `build/evidence/`。

| 分组 | 连续编辑与检查 |
| --- | --- |
| `business` | 修改返回值、判断条件、数据处理并提取辅助方法；通过页面按钮调用新逻辑 |
| `rendering` | 更新页面文字、增加组件并交互、删除组件；检查独立 `remember` 状态 |
| `callbacks` | 保留最初监听器；插入无捕获／同捕获的 lambda，修改方法体，新增、重排、删除、恢复引用与基本类型捕获，拒绝歧义插入并重试 |
| `recovery` | 将有活实例的 ViewModel 改为 abstract，同批改变业务方法和捕获布局；拒绝后检查旧调用，撤回非法改动并重试，再追加新类与调用方 |
| `full` | 按上述顺序在同一进程执行所有组 |

脚本通过真正的 TextField 输入草稿并选择详情；随后检查同一 PID、ViewModel 身份、监听器身份、输入内容、页面选择、业务操作次数及精确的工厂／回调调用次数。初始监听器与引用锚点都是不可变字段；单独创建的对照回调不会替换它们。每步更新后请求 GC，并切换到后台再返回原 Activity；不会用重启或重新安装绕过更新失败。AAR、APK 和引擎的 SHA-256 均记录，安装包哈希及 APK 内动态库必须与本地构件匹配。

旧监听器和新建监听器是不同的验收对象。特别是新增捕获变量，不能把新注册监听器的成功当成旧对象正确的证据。失败保留原始运行，不追加运行把失败改写成通过。该脚本验证功能，不是性能基准；每步 reload 耗时包括构建和通信，不能当作 ART 停顿时间。

## 后续顺序

先解决业务逻辑、渲染与 lambda 的真实缺口，再扩展多文件签名重构、模型属性／初始化、协程挂起／Flow、Repository／依赖注入／代理。当前脚本不覆盖协程和 Flow，也不声称可为旧对象重跑构造器或恢复任意新捕获值。

开发时运行受影响分组；代码冻结后在两台设备分别运行 `full`，同一设备串行。引擎源码未改时复用已有冻结矩阵并保留版本、产物与来源；新样例 APK 的执行证据单独记录。底层 JNI、安全拒绝、真实调用、CheckJNI 与符号覆盖仍分别验收。历史长停顿和性能越线保持未解决状态，不能由样例功能通过来关闭。

## 旧 lambda 的兼容契约

Kotlin 按源码顺序给编译器生成的方法编号。插入 lambda 会改变编号，新增捕获还会改变方法描述符；D8 已安装的合成回调仍调用旧实现对应的桥接。只保留当前一轮 D8 的桥接会丢失这个入口，导致 `NoSuchMethodError`，复用编号还可能接到错误方法体。

主机在会话内保留初始 D8 桥接及之后每轮发出的回调入口，让它们调用同一逻辑 lambda 的最新方法体。对应关系依据外层方法或父 lambda、SAM 契约、规范化方法体与捕获模式，不能只按源码编号匹配。先以未变的方法体固定对应关系，只让剩余唯一的一对旧／新 lambda 演进。捕获模式本身不能区分“新插入的 lambda 保留旧模式，原 lambda 同时新增捕获”，因此同一作用域／SAM 中同时改动多个方法体可能需要分开更新。无法唯一对应时，在设备更新前明确拒绝；修正后可重试，活动映射不变。删除源码中的 lambda 后，已有回调仍可调用其最后一个方法体。

对旧回调，调试名称和 JVM 类型相同的捕获保留原值，不受参数顺序影响。新增、改名、改型的捕获使用 JVM 默认值：引用／数组为 `null`，数值为零，布尔为 `false`。不重跑初始化表达式、注册工厂或构造器；新代码直接解引用默认 null 时，仍可能正常抛出 `NullPointerException`，主机不会推算历史初始化值。新建对照回调捕获当前真实值。删除再恢复某一捕获时，创建时持有该名称／类型的回调可恢复自己的原绑定。

捕获缺少调试名称时，只允许方法体及捕获布局均未变化，否则拒绝演进。普通方法引用沿用声明目标契约。当前规划器处理 LambdaMetafactory 生成的方法体，不声称支持任意匿名类的捕获迁移。只有设备报告对应类确实已追加／重定义后才提交入口清单，包括报告的部分成功；拒绝的计划不会推进清单。

回调不匹配会立即使测试失败。脚本没有替换原监听器的恢复步骤；旧回调和新建对照回调分别实际调用，并检查主机拒绝及 Native 事务拒绝后的成功重试。
