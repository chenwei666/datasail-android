# V0.2.0 架构与边界

`app` 是 Compose / ViewModel / StateFlow 界面；`core` 是独立 JVM 模块，包含类型、错误、安全策略、固定 SQL 探针及 SQL Server 工作台会话。只有驱动适配器依赖具体 JDBC 库。Activity 不连接数据库。

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

`SqlServerTrustManager` 使用微软驱动公开扩展，先由平台 X509TrustManager 验证链，再匹配 SAN 的 DNS/IPv4（DNS 支持完整左标签通配符）。不会因 Android 缺少 JNDI 而跳过身份验证；无匹配 SAN 直接拒绝。MySQL 保留独立严格 TLS 插件，未据此推断 Android MySQL 已兼容。
