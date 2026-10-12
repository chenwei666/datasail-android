# V0.4.0 架构与边界

`app` 是 Compose / ViewModel / StateFlow 界面；`core` 是独立 JVM 模块，包含类型、错误、安全策略、固定 SQL 探针及多引擎 SQL 工作台会话。只有驱动适配器依赖具体 JDBC 库。Activity 不连接数据库。

`ProbeRunner` 每次只运行一个会话，单工作线程、有限队列；取消控制最多两个线程与四个排队动作。取消并行请求 `Statement.cancel` 与 `Connection.close`；驱动若阻塞则继续显示正在清理，不报告成功。连接超时 5 秒、socket 超时 15 秒、正常查询超时 3 秒。资源使用 use/finally 关闭，失败没有重试。

三个探针：连接及 Unicode/NULL/精确小数/SELECT 1；10 秒合成延迟的查询超时；1 秒后请求取消的合成延迟。不会读取业务表或接收任意 SQL。只读探针不是服务端授权：账号仍须最小权限。

TLS 固定为身份校验策略。SQL Server 使用 SQL 账号、固定端口、encrypt=true、trustServerCertificate=false、connectRetryCount=0。MySQL 使用 MariaDB Connector/J，sslMode=verify-full，禁止 local infile、多语句与公钥自动获取。主机/库名校验阻止通过 URL 注入连接参数。工作台支持连接专用 CA 导入；没有 IPv6、Windows/Kerberos/Entra/Always Encrypted 支持。

密码不在 ConnectionProfile、UiState、SavedState 或日志中。Credentials 持有 char[] 并在结束后清零；JDBC 接口和 Compose 文本输入所需 String 无法保证内存擦除，此处不宣称内存绝对保密。应用关闭备份和截图，进入后台（包括旋转触发的 onStop）请求取消，连接不跨后台保活。

错误仅输出受控分类、SQLState 与数字错误码。原始 Throwable/message、URL、SQL、用户名和密码不进入应用诊断。超时、失联、取消、关闭失败保留 UNKNOWN，不声称服务端事务已结束。

`AiDraftProvider` 只有用户批准文本和 SQL 草稿接口，无驱动或执行权限；本轮无实现。文档、键值、搜索接口仅占位，不伪称已实现多引擎。

V0.1.0 没有持久配置。V0.2.0 新增 schema=1 的非秘密连接元数据文件和往返测试，未知 schema 读取失败时禁止覆盖；未引入 Room，未来升级不能破坏性迁移。

## V0.2.0 增量

`WorkspaceRunner` 管理单工作线程和取消线程，隔离 JDBC 会话与 UI；连接失败、取消或代次变化使旧回调失效。`SqlServerSession` 负责 SQL Server 元数据、有限结果、预览和短事务确认；预览不持有事务，不存在离线写队列。`ReadQueryPolicy` 是语法门禁，不是权限控制。`WorkspaceModel` 明确整数、小数、文本、NULL、二进制、时间及未加载值。

`WorkspaceViewModel` 通过 Flow 暴露不可变界面快照，额外用代次防止主线程排队回调在断开后恢复旧连接状态。`ConnectionStore` 只保存有版本的非秘密连接元数据与用户导入的公共 CA，AtomicFile 防止半写；密码不进入存储或 saved state。`WorkspaceScreen` 提供原生对象选择、懒加载可视网格、记录详情、编辑和变更确认。

`SqlServerTrustManager` 使用微软驱动公开扩展，先由平台 X509TrustManager 验证链，再匹配 SAN 的 DNS/IPv4（DNS 支持完整左标签通配符）。不会因 Android 缺少 JNDI 而跳过身份验证；无匹配 SAN 直接拒绝。MySQL 保留独立严格 TLS 插件；V0.3.0 的插件同样仅匹配 SAN，设备结果独立记录。

V0.2.1 中 WorkspaceRunner 返回 SessionReply（操作结果 + 实际 SessionSnapshot），即使组合操作部分失败，也会同步实际库和可写状态。驱动切库未确认时关闭会话；UI 切换目标或失联时清空旧 Schema/对象/结果/批准。只读 driverAudit source set 独立于 APK，用于方法级引用诊断。

Android 进程启动时注册 Conscrypt 2.7.0 并强制 engine socket，避免旧版平台将 JDBC 包装流当作原生 socket fd。`provideTrustManager(false)` 保留系统 TrustManagerFactory；导入 CA 仍仅作用于对应连接，错误链/SAN 不降级。JVM core 未引入此 Android AAR。

V0.2.2 的 sqlserver-android 模块将固定源码补丁编译并与其余上游字节组成派生 JAR；core 和 Android 使用同一派生产物，避免 JVM 测试落在未修改驱动上。模块运行时不依赖原始 JAR，也不把 ASM/Python 打进 APK。maxResultBuffer=8M 仅为驱动保留缓冲预算，不能替代应用结果读取限制。详细来源和拒绝能力见 sqlserver-android/README.md。


V0.3.0 由 `SqlWorkspaceSession` 隔离 SQL 工作台操作：原 `SqlServerSession` 保留确认/并发写入；`JdbcBrowseSession` 为 MySQL/MariaDB/PostgreSQL 实现各自元数据与标识符、服务器只读事务；`app/SqliteBrowseSession` 使用系统 SQLiteDatabase 的 OPEN_READONLY 和 CancellationSignal。每次元数据/查询操作关闭结果并结束事务；本地文件也有取消和十秒查询期限。JDBC 查询仍有十秒语句、十五秒 socket 期限；新增引擎 TCP/TLS 连接十五秒，PostgreSQL 登录整体三十秒。

`DatabaseCatalog` 单独表达产品接入范围：仅实现的 `DatabaseKind` 能成为连接；规划项目不发起连接。非 SQL 接口仍未实现，不把通用 SQL 会话假装成文档/键值能力。

schema=2 元数据追加 kind，schema=1 读入时默认为 SQL_SERVER；文件名保持不变以保留升级路径，下一次保存以 AtomicFile 写 schema=2。SQLite ID 是应用私有副本 SHA-256，仅经 SAF 授权读取、校验并复制；不保存任意外部文件路径或密码。不存在持久写队列。

`PostgreSqlTlsFactory` 使用 pgjdbc Properties 构造扩展，复用严格平台证书链及 SAN TrustManager；固定 verify-full，无信任全部选项。PostgreSQL 也设置 8 MB 驱动结果缓冲预算，仍不等于整个应用堆内存上限。JVM 和 Android 的实际结果见 DRIVER_MATRIX。

## V0.4.0 能力扩展

`KeyValueSession` 与 `KeyValueRunner` 独立于 SQL 会话。固定 RESP2 命令在单个有界工作线程执行，操作截止时间关闭原始/TLS socket；取消增加代次，旧成功回调不能复活连接。解析前检查长度、节点、深度和整体预算。连接使用相同严格链/SAN 信任管理器，不加入新运行时驱动依赖。

`ConnectionsScreen` 管理卡片/选型/表单，`KeyValueScreen` 提供专用浏览。`QueryStore` 为 AtomicFile schema 1、显式 SELECT 收藏，读取失败时禁止覆盖。连接与收藏各自使用进程内共享锁串行化跨实例读写；只有 .bak 时仍调用 AtomicFile 恢复，连接配置/收藏文件分别限制 4/8 MB。`PageCsv` 只序列化经确认的有界页面；系统文件选择器回调在顶层持有导出快照，生命周期断开不会丢失已确认的导出内容。保存草稿不会执行任何 SQL。
