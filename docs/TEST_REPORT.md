# V0.12.1 实际测试报告

日期：2026-10-14。版本 0.12.1 / versionCode 19；包名与签名保持连续。交付 Debug APK，Release/R8 驱动适配未完成。

## 修复与功能

- 用户确认“编辑模式”会变为“结束编辑”，因此不是权限按钮没有生效。原实现把无 rowversion、含 float/real 字段的整表设为只读。现为 SQL Server 增加可往返浮点读取、类型限定参数、原值字节并发比较；拒绝溢出、下溢、非有限值及超输入精度，不禁用原有事务/主键/触发器保护。
- `SqlServerFloating.kt`、`JdbcResultReader.kt`、`SqlServerSession.kt`、`WorkspaceModel.kt` 及共享真实服务器合约覆盖无 rowversion 表的新增、修改、删除、NULL、相邻浮点值冲突和时间字段。
- `ResultGrid.kt`、`WorkspaceScreen.kt` 在全屏/普通表顶部展示只读原因；列出不支持字段。新增全屏真实点击开启编辑、双击修改提交、打开整行/新增表单的回归。
- `AiChatScreen.kt`、`AiScreen.kt`、`AiViewModel.kt`、`AiClient.kt` 增加默认普通聊天、连续追问、取消、清空和配置隔离。最多保留 8 轮完整问答，发送内容不超过 24000 字符；仅内存存储，不自动携带数据库信息。
- SQL 草稿和当前表操作仍是独立入口，保留上下文预览与写入确认。三个 AI 文本协议保持各自消息角色；合成 HTTPS 服务检查上一轮实际到达和不附带表/数据。

## 实际命令

```bash
export DATASAIL_TOOLS_DIR=/workspace/toolchains
source scripts/env.sh
./gradlew :core:test :app:testDebugUnitTest :sqlserver-android:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
bash scripts/test-sqlserver-local.sh
# API26 完整执行；API30 尝试但因系统服务故障在旧包安装阶段中断
# 初始卸载仅用于放置旧版本，之后通过 install -r 覆盖升级
ANDROID_SERIAL=<serial> bash scripts/test-android-upgrade.sh --reset-synthetic-emulator
# API26：驱动、普通/全屏编辑、完整工作台交互、AI 设置导航
ANDROID_SERIAL=emulator-5556 DATASAIL_ANDROID_TESTS=1 DATASAIL_SQLSERVER_ANDROID_COUNT=9 \
 DATASAIL_SQLSERVER_ANDROID_CLASSES='com.example.datasail.SqlServerDeviceTest,com.example.datasail.SpreadsheetSqlServerUiTest,com.example.datasail.WorkbenchInteractionTest,com.example.datasail.AiNavigationTest' \
 bash scripts/test-sqlserver-local.sh
# API30 以下为环境恢复后的复现命令，本轮未执行到此阶段
ANDROID_SERIAL=emulator-5554 DATASAIL_ANDROID_TESTS=1 DATASAIL_SQLSERVER_ANDROID_COUNT=6 \
 DATASAIL_SQLSERVER_ANDROID_CLASSES='com.example.datasail.SqlServerDeviceTest,com.example.datasail.SpreadsheetSqlServerUiTest,com.example.datasail.WorkbenchInteractionTest#fullscreenShowsTableRestrictionWithoutSelectingACell,com.example.datasail.AiNavigationTest' \
 bash scripts/test-sqlserver-local.sh
ANDROID_SERIAL=emulator-5556 bash scripts/test-ai-android.sh
./gradlew :app:assembleRelease --console=plain
```

## 结果

JVM：92 个 core、17 个 app、14 个驱动配置、22 个实际 SQL Server 集成测试通过，共 145 个。Lint 与 Debug/测试 APK 构建通过。Release 尝试退出 1，仍有 193 个缺失类；未批量添加 dontwarn，未生成或交付 Release APK。

Android 8/API26：5 项覆盖升级检查、9 项 SQL Server / 工作台 / 导航检查、5 项合成 HTTPS AI / 真实页面检查全部通过，共 19 项；另有旧版升级准备 1 项。`docs/evidence/v0121/summary.json` 与 `devices.json` 绑定最终 APK 校验值，诊断运行不计入最终验收。

Android 11/API30：本轮环境阻塞，未完成应用验收。第一次软件冷启动出现 System UI ANR，随后系统服务重启，安装旧 V0.12.0 APK 失败（Broken pipe）。等待服务恢复后输入仍不能稳定响应；重启模拟器，使用 SwiftShader 软件渲染与 3072 MB 内存，再次启动仍停留在“Phone is starting”，输入命令超时。已保留系统日志与截图，没有删除/跳过应用断言来制造通过；没有将历史版本 API30 结果计入本版。


## 诊断过程与限制

初始全屏测试错误关闭对象选择框导致找不到表，修正为显式重新打开浏览器；保留失败日志。修正后的测试在旧 V0.12.0 的可编辑 rowversion 表上通过，未把弹窗遮挡猜测当作已确认缺陷。用户后续确认与源码共同指向字段限制。首次编译发现跨模块属性不能智能转换，已用可空访问修复。首次 Android 合并回归还发现新增浮点测试遗留可写会话状态影响后续日期测试，以及只读原因断言误用完整文本匹配；已明确重置测试会话并使用子串断言，未删除原有检查，保留失败记录后重跑。

本轮没有访问用户的 dbo.iclock_transaction；模拟表包含主键、时间、float/real 和 NULL，但不能保证用户完整表结构不存在其它限制。界面会显示剩余原因。没有真机/arm64、真实 AI 供应商账号/地区/计费、其它引擎完整矩阵、Release、归档源码独立重建或新云环境恢复测试。不承诺零缺陷，未开放任意 AI SQL 执行、批量写入或其它引擎写入。

## 交付与下一步

`versions/V0.12.1/` 与 `versions/V0.12.1.zip` 包含明确提交的源码、APK、报告、manifest 和逐文件校验；应用 APK 为 `versions/V0.12.1/apk/DataSail-V0.12.1-debug.apk`。下一步先恢复 Android 11 验证环境补跑本报告列出的升级与回归，再处理 Release 驱动适配及真机验收。
