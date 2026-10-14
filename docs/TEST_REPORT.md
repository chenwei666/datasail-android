# V0.12.0 实际测试报告

日期：2026-10-14。版本 0.12.0 / versionCode 18；应用包名与签名保持连续。产物为 Debug APK，Release/R8 仍被驱动缺失类阻断，不能宣称正式稳定版或零缺陷。

## 完成内容与变更文件

- `WorkspaceMemory.kt`、`WorkspaceViewModel.kt`、`MainActivity.kt`：非致命错误保留上次结果并标记旧数据，刷新前禁止手工/AI 写入；丢失连接清理结果及权限。取消或过期确认 token 在入口拒绝。旋转保留会话，真正进入后台仍断开。本次运行内按连接保留草稿、表位置/筛选及显示选项，显式重连后只读，不恢复事务或自动重放操作。结构变化使记忆条件失效时清理该表条件，允许重新打开。
- `TableView.kt`：MySQL/MariaDB 无符号整数使用精确 BigInteger 范围校验和 BigDecimal 绑定，修复最大 BIGINT UNSIGNED 过滤失败；按类型限制 SQL Server 不支持的比较/排序。筛选编辑替换原条件。
- `TableViewControls.kt`、`ResultGrid.kt`、`WorkspaceScreen.kt`：筛选回填、追加多字段排序、按值筛选/排除；全屏与普通表格共享选中单元格、布局和分页/刷新/增删改操作栏，确认弹窗在全屏可见。双指手势逐次累积缩放，保留 65%–200% 上下限与复位。
- `SqlEditor.kt`、`SettingsScreen.kt`：选中表结构与 SQL 查询结果分开，查询后保留补全元数据；新增每连接最近 30 条成功查询历史（库/Schema/行数/耗时），可载入草稿、清空；设置可清理本次运行工作状态。缓存有界、仅进程内，不存结果行、密码或待提交预览。
- `AiScreen.kt`、`AiTableScreen.kt`：旧结果禁止作为 AI 表操作上下文，写入预览说明会开启本次连接编辑；保留发送预览、具体变更确认和单条写入边界。
- 测试及脚本新增实际无符号边界、失败恢复、旋转重连、结构变更、全屏选择一致性、取消确认拒绝；升级基线为 V0.11.0。CI 产物名称与证据路径更新为本版。

操作说明见 [WORKSPACE](WORKSPACE.md)、[AI_ASSISTANT](AI_ASSISTANT.md)，审查基线见 [DBEAVER_REVIEW_V0.11.0](DBEAVER_REVIEW_V0.11.0.md)。仅 SQL Server 支持安全单条写入；本轮未开放其它引擎写入、批量暂存、多标签或任意 AI SQL 执行。

## 实际命令

```bash
export DATASAIL_TOOLS_DIR=/workspace/toolchains
source scripts/env.sh
./gradlew :core:test :sqlserver-android:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
bash scripts/test-jdbc-workspace-local.sh MYSQL
bash scripts/test-jdbc-workspace-local.sh MARIADB
bash scripts/test-jdbc-workspace-local.sh POSTGRESQL
# 一次仅运行一台模拟器；分别执行完整升级和 AI 测试
ANDROID_SERIAL=<selected-emulator> bash scripts/test-android-upgrade.sh --reset-synthetic-emulator
ANDROID_SERIAL=<selected-emulator> bash scripts/test-ai-android.sh
# API30：SQL Server 驱动 + 本轮关键 UI 回归
ANDROID_SERIAL=emulator-5554 DATASAIL_ANDROID_TESTS=1 DATASAIL_SQLSERVER_ANDROID_COUNT=5 \
 DATASAIL_SQLSERVER_ANDROID_CLASSES='com.example.datasail.SqlServerDeviceTest,com.example.datasail.TableFilteringTest#recreationAndExplicitReconnectKeepDraftAndTableView,com.example.datasail.TableFilteringTest#filterChipRestoresValueAndFullscreenCanPage,com.example.datasail.WorkbenchInteractionTest#pinchZoomKeepsValuesAndResetRestoresScale' \
 bash scripts/test-sqlserver-local.sh
# API26：完整移动工作台 + SQL Server 驱动与 UI
ANDROID_SERIAL=emulator-5556 DATASAIL_ANDROID_TESTS=1 DATASAIL_SQLSERVER_ANDROID_COUNT=11 \
 DATASAIL_SQLSERVER_ANDROID_CLASSES='com.example.datasail.SqlServerDeviceTest,com.example.datasail.SpreadsheetSqlServerUiTest,com.example.datasail.TableFilteringTest,com.example.datasail.WorkbenchInteractionTest,com.example.datasail.MobileWorkflowTest' \
 bash scripts/test-sqlserver-local.sh
./gradlew :app:assembleRelease --console=plain
```

四种网络 SQL 引擎采用实际临时服务器、固定摘要镜像和隔离合成数据。凭据经临时环境文件/标准输入进入容器及 App 私有目录，脚本退出清理；未访问用户业务服务器。AI 为合成 HTTPS 服务，验证平台 TLS 与三种协议，不代理真实供应商。

## JVM 与构建

| 检查 | 结果 |
|---|---:|
| Core 单元测试 | 88 通过 |
| App 单元测试 | 17 通过 |
| SQL Server 驱动适配测试 | 14 通过 |
| SQL Server 实连 | 21 通过 |
| MySQL 实连 | 8 通过 |
| MariaDB 实连 | 8 通过 |
| PostgreSQL 实连 | 8 通过 |
| 合计 | 164 通过，无跳过 |
| Debug 构建、Android 测试包、lint | 通过；lint 0 项 |
| Release/R8 | 失败；193 个缺失类，无 Release APK |

无符号边界在真实 MySQL/MariaDB 验证，PostgreSQL 验证超 Long 数值的精确比较。保留绑定、前导零、NULL、日期时间、并发冲突、未知结果不重试等既有测试；类型不支持时本地拒绝，未通过移除只读限制开放写入。

## Android 最终 APK

以 `evidence/v0120/summary.json`、`devices.json` 和原始日志为准；设备已安装 APK 字节散列与最终交付包相同。目标为 AOSP API26 / API30 x86_64 软件模拟器，不是真机或 arm64。

| 最终包检查 | API26 | API30 |
|---|---:|---:|
| V0.11.0 覆盖升级、基础流程、密码/设置/AI 配置保持 | 5 | 5 |
| SQL Server 与移动工作台组合回归 | 11 | 5 |
| HTTPS AI：三协议、预览/草稿、实时建议、自然语言实际查询 | 4 | 4 |
| 合计 | 20 | 14 |

共 34 项最终 APK Android 检查通过；另有两项旧版本种子准备测试，不计入 34。

API26 组合包含 SQL Server 驱动 2、实际 UI 1、筛选/恢复 4、缩放/Tab/500 库选择 3、收藏/导出流程 1。实际 SQL Server UI 验证全屏删除预览、取消后调用旧 token 被拒绝且服务器记录仍存在；保留单元格提交、NULL、只读查询等断言。

API30 组合包含驱动 2 和关键 UI 3，验证筛选回填、全屏翻页/刷新及退出后的选中单元格一致性、旋转和显式重连保留草稿/筛选/只读、表结构更改导致旧条件失效后可重新打开，以及双指缩放上下限。不同平台覆盖范围有差异，不把部分测试称为全矩阵。

最终 APK SHA-256：`0e14bc6882235af1e116069f8887e7a6f4d49e42671bfc218ba381ddd023c0ae`。

## 测试发现、修复及环境恢复

1. 共享网格选项后，首次双指回归仅缩放到 107%，预期为 200%。原因是重组前读取旧状态，修复为单次指针手势独立累积；保留原 200%/65% 断言并复验。首次失败见 `diagnostics/mobile-api30-before-zoom-fix.txt`。
2. 完整升级回归发现首次未连接工作台错误显示“已断开”。仅对确实保留了连接上下文的会话显示重连入口，初次使用恢复原连接引导；保留 SmokeTest 断言并从旧版安装、准备数据开始重跑。首次失败见 `diagnostics/upgrade-api30-before-first-launch-fix.txt`。
3. API30 冷启动在应用测试前出现 System UI 无响应，截图保存在 diagnostics。选择系统可见 Wait 后确认 Launcher 恢复焦点再验收，未关闭 ANR 或修改系统信任。
4. 本轮修复中途曾通过 API30 移动 UI 8 项、SQL Server 3 项及 AI 4 项。由于后续加入选择同步、失效确认与初次使用修复，这些旧 APK 日志仅保存在 diagnostics，不计为最终包通过。

## 交付与未验证范围

本地包：`app/build/outputs/apk/debug/app-debug.apk`。归档：`versions/V0.12.0/` 与 `versions/V0.12.0.zip`，由干净源码提交生成，含源码、APK、文档、驱动、证据、manifest 和逐文件校验；源码 SHA 见 manifest，归档校验见 `versions/V0.12.0-verification.json`。旧版本不覆盖。归档没有独立重建；发布上传验收原件，不重新编译或签名。

未验收真机/arm64、用户业务数据库、全部服务器版本、真实 AI 供应商账号及计费、大规模生产并发；未重跑 Redis/Valkey/MongoDB/Elasticsearch/OpenSearch 矩阵。草稿/布局/历史仅保留本次进程，真正后台仍断开，分页不是快照。模型可能误解需求，写入仍须核对预览。

Release/R8、正式签名与真机验收尚未完成；下一步最小发行任务是驱动兼容拆分和 Release 实测。功能后续按独立版本推进多标签、暂存编辑及其它 SQL 引擎安全写入，不承诺全部 DBeaver 功能或零缺陷。
