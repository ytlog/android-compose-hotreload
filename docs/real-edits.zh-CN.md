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
| `callbacks` | 修改已注册 lambda 的方法体、在其前插入另一 lambda、增加局部捕获；检查旧监听器的身份与实际调用 |
| `recovery` | 将有活实例的 ViewModel 改为 abstract，同批改变业务方法；拒绝后检查旧行为，撤回非法改动并重试，再追加新类与调用方 |
| `full` | 按上述顺序在同一进程执行所有组 |

脚本通过真正的 TextField 输入草稿并选择详情；随后检查同一 PID、ViewModel 身份、监听器身份、输入内容、页面选择和业务操作次数。每步更新后请求 GC，并切换到后台再返回原 Activity；不会用重启或重新安装绕过更新失败。AAR、APK 和引擎的 SHA-256 均记录，安装包哈希及 APK 内动态库必须与本地构件匹配。

旧监听器和新建监听器是不同的验收对象。特别是新增捕获变量，不能把新注册监听器的成功当成旧对象正确的证据。失败保留原始运行，不追加运行把失败改写成通过。该脚本验证功能，不是性能基准；每步 reload 耗时包括构建和通信，不能当作 ART 停顿时间。

## 后续顺序

先解决业务逻辑、渲染与 lambda 的真实缺口，再扩展多文件签名重构、模型属性／初始化、协程挂起／Flow、Repository／依赖注入／代理。当前脚本不覆盖协程和 Flow，也不声称可为旧对象重跑构造器或恢复任意新捕获值。

开发时运行受影响分组；代码冻结后在两台设备分别运行 `full`，同一设备串行。引擎源码未改时复用已有冻结矩阵并保留版本、产物与来源；新样例 APK 的执行证据单独记录。底层 JNI、安全拒绝、真实调用、CheckJNI 与符号覆盖仍分别验收。历史长停顿和性能越线保持未解决状态，不能由样例功能通过来关闭。

对于引擎已接受但旧回调调用失败的探测，脚本保留 `FAIL` 后继续测量显式重新注册和后续独立步骤；最终退出码仍为非零。这是功能缺口，不能记为完整验收通过。
