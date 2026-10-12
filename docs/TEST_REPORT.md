# V0.4.0 测试报告

日期：2026-10-12。版本 0.4.0 / versionCode 6；包名 com.example.datasail、最低 API26、目标 API36。源码提交、签名和 APK 校验值见交付 manifest。仅使用本机隔离容器、临时 CA 与合成数据，没有接入业务数据库。

## 完成内容

保留 SQL Server、MySQL、MariaDB、PostgreSQL、SQLite，新增 Redis / Valkey 的独立 RESP2/TLS 键值工作台。首页提供连接卡片与搜索、按引擎填写参数、校验、证书设置、删除确认和连接后自动导航。SQL 工作台增加显式 SELECT 收藏、按引擎载入草稿及当前页 CSV 导出确认。新增引擎仍只读；SQL Server 保留已验证的受控单行编辑。

修复连接配置/收藏的跨实例 AtomicFile 读写竞态，以及仅有 .bak 时未恢复已提交文件的问题。读写使用共享锁，文件大小分别限制 4/8 MB，读取失败禁止覆盖原配置。

## 实际结果

**94 项 JVM 测试通过；38 项 Android 仪器测试通过（API26 18 项，API30 20 项）。Debug 构建与 lint 通过。Release/R8 失败，143 项缺失类，没有 Release APK。最终计入的测试无失败或跳过。**

| 实际命令 / 范围 | 结果 |
|---|---|
| `./gradlew :sqlserver-android:check :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain` | 退出 0；driver 11、core 36、app 4 项单元测试；Debug 与测试 APK、lint 通过 |
| `scripts/test-mysql-local.sh` | 退出 0；6 项集成探针 + 1 项不可信 CA 反例 |
| `scripts/test-jdbc-workspace-local.sh MYSQL`，另用 MARIADB、POSTGRESQL | 均退出 0；各 5 项 JVM，真实服务器元数据、只读、类型、分页、TLS/认证回归 |
| `DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=emulator-5554 scripts/test-sqlserver-local.sh` | 退出 0；15 项 JVM + Android 11 上 2 项实连（读写/XML/冲突/超时/取消） |
| `DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=… scripts/test-key-value-local.sh REDIS`，另用 VALKEY；分别选择两台模拟器 | 全部退出 0；每引擎 3 项 JVM，每引擎每系统 `OK (3 tests)`，共 6 项 JVM、12 项 Android；最终 APK 重跑通过 |
| `ANDROID_SERIAL=… scripts/test-android-upgrade.sh --reset-synthetic-emulator`（两台分别） | 退出 0；每台 UI 3 + 升级 1。先装 V0.3.0/code5，写入 schema 2 合成连接，再覆盖安装 V0.4.0/code6，配置与生产只读策略保留 |
| 两台分别 `adb shell am instrument -w -e class com.example.datasail.SqliteDeviceTest,com.example.datasail.ConnectionUiTest,com.example.datasail.QueryStoreTest,com.example.datasail.MobileWorkflowTest com.example.datasail.test/androidx.test.runner.AndroidJUnitRunner` | 每台 `OK (8 tests)`；SQLite 2、首页 2、收藏/并发/恢复 3、移动工作流 1 |
| `./gradlew :app:assembleRelease --console=plain` | 退出 1；R8 缺失 143 类，未加批量 dontwarn |
| `bash -n` 新增/修改的测试脚本、`python3 -m py_compile scripts/archive-version.py`、`git diff --check` | 通过 |

计数见 `docs/evidence/v040/summary.json`。同一引擎随设备测试重复执行的 JVM 套件只累计一次；失败的诊断轮次不算通过。R8 清单与 V0.3.0 原始文件逐字节相同；上一版文字中的 142 少计了首条带 ERROR 前缀的 Missing class，本轮统一按 143 条计数，不是新增缺失类。

两台设备均为无 KVM 的 x86_64 软件模拟器：Android 8/API26（OSR1.180418.004/4931640）、Android 11/API30（RSR1.210722.013.A2/10067904）。精确指纹见 devices.json。所有本轮设备日志均晚于最终 Debug APK 构建；最终 APK 校验值在 summary.json 中记录。最终 APK 的数据库网络实连范围为 Redis/Valkey 两系统与 SQL Server API30；MySQL/MariaDB/PostgreSQL 的两系统 Android 网络证据沿用 V0.3.0 的独立报告，**没有计作本轮设备测试**。本轮这三种引擎重新运行了 JVM 回归，驱动和协议路径未改动。

## 专项验证

- Redis 8.2.3 和 Valkey 8.1.5 使用各自固定镜像摘要，不用兼容协议推断支持。固定合成库有 212 个键、600 字段哈希、六类内容、UTF-8/二进制/含 CRLF 的键和超限字段；检查游标完整性、数值文字、TTL、空结果与关闭行为。
- 服务器 reader ACL 拒绝 SET/DEL。APP 仅提供固定读命令；测试专用 pauser 账号的 CLIENT PAUSE 用于真实超时/取消注入，不进入主 APK。错误主机名、未知 CA、错误密码均拒绝；没有明文降级。
- RESP 单 bulk、数组节点、嵌套深度、总响应预算在分配前检查；异常响应丢弃会话。取消登录的回归验证旧成功回调不能复活会话。
- CSV 单元测试检查引号/换行、NULL/空值/反斜杠、精确数值与二进制展示、公式前缀防护、拒绝含未加载字段的页面。Android 工作流覆盖到导出预览/取消；**系统文件选择器的完整写出与第三方文件提供者未自动化验收**。
- 首页实际运行数据库选型、条件表单、非法参数阻止连接、卡片搜索与删除确认；SQLite 实连后执行查询、收藏、载入草稿和导出确认。收藏不会自动执行 SQL。
- Android 并发回归使用不同存储实例同时读写，验证最后提交保留；模拟中断写入只留下 .bak，验证恢复已提交的连接和收藏。连接元数据对全部七种类型往返，密码未写入文件。

## 发现与修复

初次界面测试夹具在 Activity recreate 后仍保留旧 ViewModel，且按文本选择数据库时同时匹配输入框；改为在 Activity 启动前准备夹具，并精确选择卡片，保留原有功能断言。随后删除确认测试暴露 AtomicFile 的跨实例并发竞态，修复共享锁与备份恢复，新增真实设备回归，两系统最终通过。原始失败输出保留在 `diagnostics/`，不作为通过证据。

## 主要变更文件

- `core/.../KeyValueSession.kt`、`KeyValueRunner.kt`：键值能力接口、严格 TLS、RESP 预算和生命周期。
- `core/.../Model.kt`、`DatabaseCatalog.kt`、`JdbcConnector.kt`：类型、入口隔离与真实支持范围。
- `app/.../ConnectionsScreen.kt`、`KeyValueScreen.kt`、`WorkspaceScreen.kt`、`WorkspaceViewModel.kt`：首页与独立工作台、导航和操作状态。
- `app/.../ConnectionStore.kt`、`QueryStore.kt`、`QueryLibrary.kt`、`core/.../PageCsv.kt`：持久化恢复、收藏、明确范围的导出。
- `core/src/fixture/.../KeyValueContract.kt`、相关 JVM/Android 测试、`scripts/test-key-value-local.sh`：真实服务与设备验证；CI 增加两引擎入口，尚未执行远程 CI。
- `docs/PRODUCT_ACCEPTANCE.md`、ROADMAP、DATABASE_SCOPE、DRIVER_MATRIX、WORKSPACE：移动工具完成标准及当前能力边界。

## 交付与剩余范围

版本目录 `versions/V0.4.0/` 与 ZIP 包含独立源码、Debug/测试 APK、说明、报告、manifest 和逐文件 SHA-256。来自干净本地提交；原包名与本地 Debug 签名连续。V0.1.0、V0.2.0、V0.2.1、V0.2.2、V0.3.0 五份旧 ZIP 校验通过，未覆盖。没有推送仓库或发布正式 Release。

当前不是“所有主流数据库最终版”。MongoDB、Elasticsearch/OpenSearch、ClickHouse、Oracle、Db2、国产/兼容协议及其它引擎尚待独立实现；新增引擎编辑、DDL、全量导入导出、AI、SSH/IPv6/云认证、集群、多版本服务端、物理/arm64 设备与 Release/R8 均有剩余工作。最小下一步是 MongoDB 集合/文档工作台及严格 TLS、类型、分页、取消的独立 JVM/Android 验收。完整完成标准见 PRODUCT_ACCEPTANCE。
