# V0.11.0 实际测试报告

日期：2026-10-14。版本 0.11.0 / versionCode 17；应用包名与签名保持连续。本版产物为 Debug APK，Release/R8 仍被驱动缺失类阻断，不能宣称正式稳定版或零缺陷。

## 完成内容与变更文件

- `TableView.kt`、`SqlWorkspaceSession.kt`、`SqlServerSession.kt`、`JdbcBrowseSession.kt`、`SqliteBrowseSession.kt`：新增元数据白名单筛选和排序，用户值参数绑定。SQL Server / MySQL / MariaDB / PostgreSQL / SQLite 在数据库端执行条件后再分页；最多 8 个 AND 条件、3 个排序字段，追加主键消除排序值相同导致的页序不稳定。输入非法日期拒绝，不自动换算日期；SQL Server 日期时间按类型 CONVERT 精确文本参数，避免驱动将 time 按 datetime 传输。
- `TableViewControls.kt`、`ResultGrid.kt`、`WorkspaceScreen.kt`：列头菜单提供字段选择、升降序、值/NULL 筛选和移除条件，上方显示当前条件并一键清除。刷新、翻页、字段分组、提交后保留条件。行距、内边距随缩放同步调整，行高考虑系统字体大小，修复 65% 缩放时文本裁切。
- `AiTableAction.kt`、`AiClient.kt`、`AiTableScreen.kt`、`AiViewModel.kt`、`WorkspaceViewModel.kt`：AI 通过严格 JSON 描述当前表的一次操作，不能指定目标连接/表或 SQL。用户预览问题及表结构后批准发送，读取结果留在本机；默认只查询。每次显式允许写入预览后，可以为 SQL Server 生成单条新增、修改、删除预览，确认具体目标/键/原值/新值后才提交。零条或多条匹配拒绝写入；复用已有短事务、单次 token、乐观并发、未知结果不重试。
- UI 配置继续统一在设置，问答/SQL 草稿及实时建议沿用原来的非自动执行模式。目标/权限/上下文变化后旧 AI 响应不再应用。未修改包名、签名或存储格式。

操作说明见 [WORKSPACE](WORKSPACE.md) 与 [AI_ASSISTANT](AI_ASSISTANT.md)。列筛选为 AND 组合，不支持任意 SQL、OR 分组或计算表达式；其它 SQL 引擎仍不支持写入。没有给文档/键值/搜索引擎开放 AI 数据写入。

## 实际命令

```bash
export DATASAIL_TOOLS_DIR=/workspace/toolchains
source scripts/env.sh
./gradlew :core:test :sqlserver-android:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
bash scripts/test-jdbc-workspace-local.sh POSTGRESQL
bash scripts/test-jdbc-workspace-local.sh MYSQL
bash scripts/test-jdbc-workspace-local.sh MARIADB
bash scripts/test-sqlserver-local.sh
# 以下分别针对一次只启动的一台模拟器
ANDROID_SERIAL=<selected-emulator> bash scripts/test-android-upgrade.sh --reset-synthetic-emulator
# API26 最终包 UI 回归
adb -s emulator-5556 shell am instrument -w -e class com.example.datasail.WorkbenchInteractionTest,com.example.datasail.TableFilteringTest com.example.datasail.test/androidx.test.runner.AndroidJUnitRunner
# API30 完整移动 UI 回归
adb -s emulator-5554 shell am instrument -w -e class com.example.datasail.SettingsUiTest,com.example.datasail.TablePresentationTest,com.example.datasail.AiNavigationTest,com.example.datasail.MobileWorkflowTest,com.example.datasail.WorkbenchInteractionTest,com.example.datasail.TableFilteringTest com.example.datasail.test/androidx.test.runner.AndroidJUnitRunner
ANDROID_SERIAL=<selected-emulator> bash scripts/test-ai-android.sh
DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=<selected-emulator> bash scripts/test-sqlserver-local.sh
./gradlew :app:assembleRelease --console=plain
```

服务器夹具均为临时隔离的合成数据，镜像固定摘要见各脚本。凭据通过进程环境文件/标准输入进入临时容器和 App 私有目录，退出清理；未访问用户业务服务器。合成 AI HTTPS 服务验证平台 TLS 和三种协议，不代理真实 AI 供应商。

## JVM 与构建

| 检查 | 结果 |
|---|---:|
| Core 单元测试 | 85 通过 |
| App 单元测试 | 14 通过 |
| SQL Server 驱动适配测试 | 14 通过 |
| SQL Server 实连 | 21 通过 |
| PostgreSQL 实连 | 7 通过 |
| MySQL 实连 | 7 通过 |
| MariaDB 实连 | 7 通过 |
| 合计 | 155 通过，无跳过 |
| Debug 构建、Android 测试包、lint | 通过 |
| Release/R8 | 失败；193 个缺失类，无 Release APK |

筛选测试覆盖超过首批 100 行的数据、相同排序值的稳定翻页、数值与前导零、特殊字符/引号按字面绑定、NULL 和日期时间。AI 覆盖未知字段/目标/SQL 注入结构拒绝、无写权限拒绝、单条增删改预览与提交、提交前数据库不变、零条/多条匹配拒绝、并发冲突不覆盖。

## Android

最终结果以 `evidence/v0110/summary.json`、`devices.json` 及对应原始日志为准，APK 安装字节的 SHA-256 与交付包绑定。测试目标为 AOSP API26 / API30 x86_64 软件模拟器，不是真机或 arm64。

| 最终 APK 检查 | API26 | API30 |
|---|---:|---:|
| V0.10.0 覆盖升级、基础流程与密码/设置/AI 配置保持 | 5 | 5 |
| 移动 UI（API26：缩放/Tab/500 库选择/列头筛选；API30：另含设置/宽表/AI 导航/收藏导出） | 4 | 8 |
| HTTPS AI：三协议、发送预览/草稿、实时建议、自然语言实际查询 | 4 | 4 |
| SQL Server ART / UI：时间筛选、AI 单条操作、安全编辑、NULL、取消与冲突 | 3 | 3 |
| 合计 | 16 | 20 |

共 36 项最终 APK Android 检查通过，另各有 1 项旧版准备测试。API26 完整重走旧版安装与覆盖升级后通过，API30 的 8 项 UI、4 项 AI、3 项 SQL Server 均整组通过；没有跳过失败断言。

交付 APK SHA-256：`7472e7c1540f1747faafedd0716235fdc31512cb73e9a5921b0e4cfdecd72ca7`。两台模拟器读取已安装 APK 字节所得散列均相同。

## 发现并修复的问题

API30 冷启动时系统媒体服务和 System UI 发生无响应，尚未运行数舟测试。保留系统日志/截图，选择系统“Wait”后确认 Launcher 恢复焦点、服务已注册，再执行应用测试；未关闭 ANR、修改系统信任或降低应用断言。

新增真实时间筛选回归最初失败：SQL Server 返回 `time and datetime are incompatible in the greater than operator`。原因是 JDBC 默认将 java.sql.Time 参数作为 datetime 发送。改用与字段类型绑定的 `CONVERT(time(7),?,126)` 等精确文本转换后，相同真实用例通过；初次失败保存在 `diagnostics/sqlserver-temporal-filter-first.xml`，没有删除断言或跳过测试。

一次额外调用升级验证器时失败，因该一次性验证器在首次通过后会清除升级夹具（预期字号 18 已恢复默认 14），不是 App 升级丢失设置。保留失败日志后重新执行完整的旧版安装、准备数据、覆盖安装与验证流程；没有重写待验证数据来让断言通过。

第一轮 API26 的 8 项 UI 和升级测试在日期筛选细化前已通过，日志保存在 diagnostics，不计入最终 APK 的测试数。后续修改后的实际复验范围由 summary 独立列出，不将旧构建结果冒充最终包。

## 交付与限制

本地安装包：`app/build/outputs/apk/debug/app-debug.apk`。版本归档：`versions/V0.11.0/` 和 `versions/V0.11.0.zip`，由干净源码提交生成，包含源码、APK、文档、驱动、测试证据、manifest 和逐文件校验。归档生成后的校验记录位于 `versions/V0.11.0-verification.json`，源码 SHA 记录在 manifest。原 V0.10.0 及更早归档不覆盖。

没有验证真机/arm64、用户业务数据库、全部服务器版本、真实供应商模型账号/计费、其它引擎写入或大规模生产并发；没有重新验收 Redis/Valkey/MongoDB/Elasticsearch/OpenSearch 全矩阵。模型仍可能误解需求，写入必须核对预览。分页不是数据库快照。下一步最小发行任务为修复剩余 Release 驱动兼容阻塞并进行真机验收。
