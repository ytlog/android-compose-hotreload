# 连续开发集成场景

本组补充真实编辑的组合边界；“剩余 30%”只是规划估计，不是可测量的覆盖率。
所有场景经正常 Kotlin/Java、D8、设备热更新执行。原始失败、逐次报告和 APK
保留在已忽略的 `build/evidence/`。以下是运行前契约，设备结论另记本地验证记录。

| 分组 | 编辑或事件 | 旧对象、旧任务的预期 | 新对象、新任务的预期 |
| --- | --- | --- | --- |
| effects | 更新挂起 Effect 调用的业务方法与监听器方法 | 不重新发起任务或注册监听器；恢复或下一次事件执行新方法 | 使用新方法 |
| effects | rememberUpdatedState 输入改变 | 已注册监听器读取最新值，引用不必重建 | 最新值 |
| effects | Effect key 改变、组件移除、返回页面 | 旧任务取消一次，监听器释放一次，无迟到输出 | 新 key 或新页面进入一次，任务和监听器各一个 |
| effects | rememberCoroutineScope 任务、组件销毁 | 原 scope 任务取消一次，无遗留任务 | 新组件获得新 scope |
| scopes | 修改直接持有 remember 的作用域、增加或删除槽 | 按上游作用域重置契约重新初始化；不承诺槽迁移 | 最新初始化值；无关根状态和 ViewModel 保留 |
| lifecycle | 返回栈、参数、Activity.recreate、深浅主题配置往返 | 离开页面的 Effect 释放；重建保留 ViewModel，普通 remember 重建 | rememberSaveable 与 SavedStateHandle 恢复原值；当前路由恢复 |
| network | 真实 HTTP 挂起期间更新处理逻辑 | 同一请求只发一次；迟到响应不能交付给已取消任务 | 最新处理逻辑，每次显式操作一个请求 |
| network | Retrofit 方法注解、Gson DTO 字段新增/改型 | 记录缓存 ServiceMethod/TypeAdapter 的实际边界，不把缓存自动更新当承诺 | 新 Retrofit/Gson 实例使用当前声明；旧对象字段遵循结构迁移契约 |
| build | 跨模块 inline、const、默认参数、reified | 已编译调用方随正常增量构建同步更新；存量业务数据保持 | 新调用使用新编译产物 |
| build | Kotlin serialization 生成 serializer 修改 | 实际调用旧缓存 serializer 并记录契约；不声称所有 KSP/KAPT 框架兼容 | 新建 serializer 与新 schema 匹配 |
| transaction | 新类、已有类、资源同批更新，准入拒绝及编译失败 | 拒绝前旧行为与资源保持；编译失败不发送更新；保留每次失败 | 修正后同批生效；已追加类命名空间不承诺回滚 |
| session | 后台并发调用期间连续更新 | 每个调用结果属于完整旧版或新版，不崩溃、不漏结果 | 最终版本可调用，存量对象不重建 |
| session | 连续 20 次编辑及 GC/前后台切换 | 不通过追加轮次平均失败；同 PID、固定请求与回调次数 | 每轮最终版本正确 |

定向组稳定后冻结源码，再运行双设备适用完整场景。引擎未修改时复用已冻结的
原生普通/CheckJNI 证据，必须保留原 APK 和引擎哈希，不能冒充本轮新包实测。

连续更新预先固定为 20 轮，顺序 v1 → v2 … → v21，每轮一次更新、一次业务调用、
一次 GC 和一次前后台切换。记录每轮构建到响应耗时、设备主线程心跳最大间隔、
堆占用和活跃监听器/任务数量。功能失败、单次更新超过 120 秒、前台主线程停顿
超过 5 秒均保留为失败，不补轮数。堆占用是诊断指标，不以单次 GC 数字推断泄漏；
本组不替代引擎 CPU 绑定的微基准，也不抹除历史性能越线。

Effect 夹具的 ViewModel 仅公开稳定值与 Compose 可观察状态，并显式标注 `@Stable`，
固定完整编译与增量编译的参数稳定性 ABI。未标注模型的编译器稳定性推断可能改变
真实缓存槽布局；已观察到的此类重置保留为边界证据，不能承诺旧 Effect 无条件存活。

当前导航夹具使用显式路由栈和 BackHandler，验证 Compose/Activity 生命周期；
不代表 Navigation Compose 库内部缓存已覆盖。进程死亡后的冷启动、多进程、
混淆构建和动态模块不属于保留同一运行进程的契约。主机退出按现有契约结束应用，
不能把重新启动冒充原进程重连。

## 运行与效率

```sh
python3 tools/real-edit-test.py --serial DEVICE_SELECTOR --device-id local-apiNN \
  --art-dcevm-version VERSION --scenario-group integration
# 一个进程中仅运行本次受影响的组；full 禁止使用此过滤器。
python3 tools/real-edit-test.py --serial DEVICE_SELECTOR --device-id local-apiNN \
  --art-dcevm-version VERSION --scenario-group integration \
  --integration-groups scopes lifecycle build transaction
```

API 33 及以上使用独立、只读的 instrumentation 辅助 APK 常驻读取真实 accessibility
节点，点击仍通过原 ADB 输入路径。构建缓存位于已忽略的 `tools/ui-driver/build/`，
每轮记录辅助 APK 哈希。读取失败直接终止；`HOT_RELOAD_SLOW_UI=1` 可以显式选择
原始 uiautomator 读取方式，旧 API 自动使用原方式。辅助 APK 首次安装可能触发设备
自身的 USB 安装确认。最终回归不能让不同任务同时占用同一设备。

仅在显式启动 Activity 后，等待新 accessibility 窗口就绪；期间核对原 PID、保留
窗口快照并持续检查物理输入。此等待不重发启动或点击，其他阶段的窗口覆盖仍立即
拒绝。原始读取与常驻读取的等价性、真实点击和前后台切换应在设备上分别核对。

初始化抛异常须区分提交阶段：引擎准入拒绝保持原有定义和资源；用户静态初始化
发生在类/资源已经提交之后，其异常会报告失败，但已提交代码和外部副作用不能
撤销。用例明确验证旧字段值、初始化次数、已生效代码和资源，再修正并完成下一
次更新，不能把这一边界描述为任意失败都会全局回滚。
