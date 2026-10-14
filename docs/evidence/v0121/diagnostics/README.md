# 诊断记录，不计入最终验收

- `repro-fixture-navigation.txt`：首次全屏回归测试在关闭对象选择器后没有重开浏览入口，测试准备失败。
- `baseline-android-emulator-5556.txt`：修正测试导航后，在 V0.12.0 和原 rowversion 测试表上通过 1 项，证明未复现弹窗层级猜测；不是本版浮点修复的最终验证。
- `baseline-grid-emulator-5556.png`：旧测试残留截图，明确排除本版验证。
- `initial-build.txt`：首次构建的 Kotlin 跨模块智能转换错误，已修复。
- `android-fixture-state.txt`：首次本版 Android 合并测试 9 项中 2 项失败；新增浮点合约遗留可写状态影响后续日期合约，只读提示断言误用完整文本匹配。修正测试隔离和匹配后完整重跑，没有取消测试或移除关键断言。

- `system-ui-startup-api30.png`、`system-startup-api30.txt`、`system-watchdog-api30.txt`、`upgrade-api30-startup.txt`：API30 软件模拟器首次冷启动系统界面无响应，系统服务重启，旧 APK 安装失败（Broken pipe）；发生在数舟测试之前。系统服务短暂恢复，但输入仍不能响应；没有进入本版应用验收。

- `emulator30-first-start.txt`、`emulator30-retry.txt`、`api30-retry-still-starting.png`：第二次使用 SwiftShader、3072 MB 内存重新启动后仍停留在系统启动页面，输入命令超时；API30 标为环境阻塞，未把历史版本结果计作本版通过。
