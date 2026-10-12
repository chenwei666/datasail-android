# V0.3.0 测试报告

日期：2026-10-12。版本 0.3.0 / versionCode 5；应用 ID com.example.datasail、最低 API26、目标 API36。源码提交、APK 校验与签名指纹以交付 manifest 为准。所有数据库仅使用本机隔离容器内的合成数据，临时 CA/凭据在测试后清理，没有接入业务数据库。

## 实现范围

保留 SQL Server 单行编辑；新增 MySQL、MariaDB、PostgreSQL 的只读对象/列/主键/分页/查询，以及 SQLite 私有一致性副本导入和只读查询。连接配置 schema 1 向 schema 2 兼容读取并保存数据库类型，密码不落盘。其它主流引擎是已记录的接入目标，尚未实现，不以目录条目表示支持。

## 最终结果

**82 项 JVM 测试通过；Android 8 和 Android 11 各 14 项、合计 28 项仪器测试通过。Debug 构建和 lint 通过。Release/R8 失败，142 项缺失类。无跳过项。**

| 命令 / 范围 | 结果 |
|---|---|
| `./gradlew :sqlserver-android:check :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain` | 退出 0；驱动 11、core 31、app 3 项单元测试；lint、Debug 和测试 APK 构建通过 |
| `scripts/test-sqlserver-local.sh`（再分别设 `DATASAIL_ANDROID_TESTS=1` 和两个 `ANDROID_SERIAL`） | 退出 0；15 项 JVM，每台 2 项设备实连 |
| `scripts/test-mysql-local.sh` | 退出 0；6 项探针集成与 1 项不可信 CA 测试 |
| `scripts/test-jdbc-workspace-local.sh MYSQL`（另分别使用 MARIADB、POSTGRESQL 参数，设备模式同上） | 全部退出 0；每引擎 5 项 JVM，每系统每引擎 2 项设备测试 |
| `scripts/test-android-upgrade.sh --reset-synthetic-emulator`（两台分别执行） | 退出 0；每台 UI 3 项 + 升级 1 项。先装 V0.2.2/code4，再覆盖安装 V0.3.0/code5，配置与生产只读策略保留 |
| 显式运行 `com.example.datasail.SqliteDeviceTest`（两台分别执行） | 每台 `OK (2 tests)`；不是仅检查 ADB 退出码 |
| `./gradlew :app:assembleRelease --console=plain` | 退出 1；R8 缺失 142 类，没有 Release APK |

结果聚合见 `docs/evidence/v030/summary.json`，设备版本见 `devices.json`；JDBC 各引擎的 XML/设备日志独立保存。JVM 套件按引擎分别计数，设备夹具启动时的重复 JVM 执行不重复累计。Android 8 为 API26/OSR1.180418.004/4931640，Android 11 为 API30/RSR1.210722.013.A2/10067904，均 x86_64 软件 TCG，无 KVM。

V0.1.0、V0.2.0、V0.2.1、V0.2.2 既有 ZIP 的 SHA-256 全部复核通过；没有覆盖历史交付。最终归档来自干净本地提交，包含源码、Debug/测试 APK、证据、manifest 和逐文件校验，不推送或发布。

## 主要变更文件

- `core/.../SqlWorkspaceSession.kt`、`WorkspaceRunner.kt`：跨 SQL 引擎会话及生命周期。
- `core/.../JdbcBrowseSession.kt`、`JdbcResultReader.kt`：方言元数据、只读事务、有界类型保真。
- `core/.../JdbcConnector.kt`、`PostgreSqlTlsFactory.kt`、`StrictTlsPlugin.kt`：新增驱动、严格 TLS 与 Android 适配。
- `app/.../SqliteBrowseSession.kt`、`ConnectionStore.kt`、`WorkspaceViewModel.kt`、`WorkspaceScreen.kt`：文件副本、配置升级、统一入口。
- `core/.../DatabaseCatalog.kt`、`docs/DATABASE_SCOPE.md`：当前能力与主流引擎接入清单。
- `scripts/test-jdbc-workspace-local.sh`、相关 JVM/Android 测试：独立引擎验收及匿名测试卷清理。

## 真实用例

JDBC 工作台每种服务器五项：对象/列/主键及 105 行分页；中文/前导零/NULL/精确小数/长字段保真；特殊引号、百分号和下划线对象名的正确引用；拒绝多语句/写入/可执行注释；错误 CA、错误 SAN、错误密码。PostgreSQL 再验证 SELECT 函数改会话默认设置不能取消后续只读事务，3000 位 NUMERIC 不加载、NaN 保留；MySQL/MariaDB 验证无符号 BIGINT 上限。

设备 JDBC 每引擎两项：ART 严格 TLS 正负例和真实对象/分页/只读查询；语句超时与显式取消/关闭。MySQL SLEEP 的中断可返回整数 1，或由驱动报告超时/取消；均不宣称服务端所有请求已停止。SQLite 两项：导入副本的分页/类型/只读及源文件、副本逐字节不变；拒绝无效或 WAL 主文件并清理失败临时副本。

SQL Server 保留 15 项 JVM 工作台/并发/冲突/回滚/未知提交与连接目标状态回归；设备每系统两项 TLS/CRUD/冲突/XML/缓冲/超时/取消。覆盖安装采用不变的本地 Debug 签名，先安装不可变 V0.2.2 APK，写入 schema 1 合成配置，再执行 adb install -r；UI 三项和升级一项检查应用入口、驱动加载、所有类型配置往返以及旧 SQL Server/生产策略保留。

## 诊断与修复

保留早期失败日志于 docs/evidence/v030/diagnostics。PostgreSQL 元数据调用 Android 缺少的 closeOnCompletion，改为参数化系统目录查询并显式关闭资源。MySQL 错误主机名路径触发 JNDI CN 解析，插件改为共享严格 SAN 校验。软件模拟器首次握手超出原登录预算，新增引擎保持有界 15 秒连接、PostgreSQL 30 秒整体登录。事务结束失败现在保留原查询异常并关闭连接，新增合成故障单元回归。MariaDB DECIMAL 不支持 Reader 转换，按有界精度用 BigDecimal；PostgreSQL 无界数值另设预算。

SQLite 初测误选 Android 自动创建的额外表，测试改为按 records 精确定位，原文件保真等断言没有删除。不存在忽略错误证书、跳过失败测试、放宽写权限或自动重试 SQL 的修复。

GitHub Actions 已添加三个新增 JDBC 引擎的独立工作台步骤与 XML 上传；相同脚本已在本机执行，但未推送或启动远端 CI，不把工作流修改描述成远端 CI 通过。

## 限制

Release/R8 仍失败，没有正式 Release APK；缺失 Android/可选驱动 API 的日志单独留存，不做批量 dontwarn。上述结果只覆盖指定服务器及 API26/API30 x86_64 无 KVM 软件模拟器，真机/arm64、其它版本/云认证、硬断网、性能和完整交互/无障碍验收未执行。连接测试成功不是所有数据库类型或功能兼容证明。语法过滤/只读事务不能代替服务端最小授权。没有推送、公开发行或环境发布。

环境启动说明 start_skill 草稿已更新，包含各引擎隔离测试及两个模拟器的复用/启动说明；草稿保存不代表已发布或完成全新任务恢复验证。需在环境设置审阅保存并发布后才供后续任务使用。
