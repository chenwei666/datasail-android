# V0.12.2 实际测试报告

日期：2026-10-14。版本 0.12.2 / versionCode 20；包名与签名保持连续。交付 Debug APK，Release/R8 驱动适配未完成。

## 问题与修复

用户 V0.12.1 截图明确显示 `gps_location (nvarchar)` 因“没有行版本号，暂不能安全比较”导致整表只读。此前浮点字段修复未覆盖 MAX 文本声明：`TableSchema` 把声明长度超过 4000 等同于数据未完整加载，SQL Server 的 MAX 元数据为 Int.MAX_VALUE，因此即使字段为 NULL 也锁住整表。

先增加真实 SQL Server 回归用例，在旧实现上得到同样的只读原因（23 项中 1 项失败），证据位于 `evidence/v0122/diagnostics/before-fix.*`。随后修复：

- `WorkspaceModel.kt` 按类型判断表能力，实际行完整性继续由提交入口逐行检查；不再用字段声明容量锁住整表。
- `SqlServerText.kt` / `SqlServerSession.kt` 使用白名单类型与合法的 `nvarchar(max)` / `varchar(max)` 转换，不产生 `nvarchar(2147483647)`；使用 DATALENGTH 和字节比较完整原值，继续保留事务、主键、触发器和异常影响行数保护。
- `JdbcResultReader.kt` 对 SQL Server 使用每字段 4000 个 UTF-16 单元读取上限，与原输入上限一致，保留每页 262144 字符预算；其它 JDBC 引擎默认读取范围不变。真正超限仍标记未加载，不截断后参与更新。
- `ResultGrid.kt` 在选中不可编辑行时列出未完整加载字段名。
- `SqlServerLargeTextContract.kt` 在 JVM 与 Android 上共用，覆盖 NULL/空串、大小写、尾空格/尾零、Unicode、4000 边界、末尾字符并发变化、4001 超限拒绝、其它完整行可改及新增/删除。真实普通/全屏 UI 夹具增加 `gps_location nvarchar(max)`。

无需修改业务表结构或新增 rowversion。未访问用户服务器；仍不支持无稳定主键、启用触发器、复杂类型和实际未完整加载行的直接修改。

## 实际命令

```bash
export DATASAIL_TOOLS_DIR=/workspace/toolchains
source scripts/env.sh
./gradlew :core:test :app:testDebugUnitTest :sqlserver-android:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
bash scripts/test-sqlserver-local.sh
# 新增尾零并发断言后重新构建测试 APK，下面的 SQL Server 脚本重跑最终合约
./gradlew :app:assembleDebugAndroidTest --console=plain
ANDROID_SERIAL=emulator-5556 bash scripts/test-android-upgrade.sh --reset-synthetic-emulator
ANDROID_SERIAL=emulator-5556 DATASAIL_ANDROID_TESTS=1 DATASAIL_SQLSERVER_ANDROID_COUNT=5 \
 DATASAIL_SQLSERVER_ANDROID_CLASSES='com.example.datasail.SqlServerDeviceTest,com.example.datasail.SpreadsheetSqlServerUiTest,com.example.datasail.WorkbenchInteractionTest#fullscreenShowsTableRestrictionWithoutSelectingACell' \
 bash scripts/test-sqlserver-local.sh
./gradlew :app:assembleRelease --console=plain
```

升级脚本只在可销毁模拟器上清理合成数据，以安装旧版并准备夹具；真正升级为 `adb install -r`，不是卸载后把新装当升级。检查原连接、证书信任、加密数据库密码、收藏、设置和 AI 配置密钥。模拟器启动时脱离会话的启动进程退出留下 PID 锁，确认原进程已结束后清理并重新启动；重复发起的升级启动器被终止，最终从旧版重新完整执行一次，未计入中断尝试。

## 结果

最终 JVM 报告：core 94、app 17、驱动配置 14、实际 SQL Server 集成 23，共 148 项，失败/错误/跳过均为 0。Gradle 允许复用未改动任务的有效缓存，具体执行/UP-TO-DATE 状态保留在构建日志；没有把缓存任务称为全部重新执行。Lint 无 issue，Debug 与测试 APK 构建通过。

Android 8/API26 x86_64 软件模拟器：5 项 V0.12.1 覆盖升级检查、5 项实际 SQL Server/普通及全屏编辑/只读原因检查，共 10 项最终 APK 检查通过；另有 1 项旧版状态准备检查，不计入最终 APK 的 10 项。全屏用实际触摸开启生产连接编辑，双击单元格，确认提交后从真实服务器读取核对；普通页同时覆盖 NULL 与查询结果只读。实际安装 APK SHA-256 与本地交付 APK 一致，见 `evidence/v0122/devices.json` / `summary.json`。

Release 构建退出 1，仍报告 193 个缺失类，未新增批量 dontwarn；没有 Release APK。原包名与 Debug 签名保持，归档脚本额外核对上一版签名。

## 验证边界

- 仅本机隔离 SQL Server 2022 CU16 Developer、临时 TLS CA 和合成数据；未连接用户 dbo.iclock_transaction 或其它业务库，未验证业务账号权限和实际所有字段。
- Android 11/API30 当前包未重跑。上一轮 V0.12.1 模拟器系统服务及启动故障记录保留在不可变旧版报告，不能把旧版尝试或历史通过当作当前验证。真机、arm64、生产大表性能未验证。
- 其它引擎完整实连矩阵、AI 网络端到端和真实供应商账号本轮未重跑；常规单元检查仍包含原有逻辑。未增加其它引擎写入。
- 大字段完整编辑器未实现；实际超过单字段/整页读取预算的行继续禁止修改或删除。表锁短事务可能阻塞其它写入，分页不是一致性快照。
- Release/R8、正式身份签名、独立归档源码重建与全新云环境恢复未完成，不宣称正式无缺陷发行或完整 DBeaver 功能。

产物：`app/build/outputs/apk/debug/app-debug.apk`、`versions/V0.12.2/apk/DataSail-V0.12.2-debug.apk`、`versions/V0.12.2.zip`。归档 manifest 记录源码提交、签名、摘要与实际测试证据；归档时 `published=false`，GitHub 发布后另存发布回执，历史档案不改写。

下一项最小任务：在用户相同表的授权副本或真机复核本次编辑流程，并继续处理 Android 11 验收与 Release/R8 门禁。
