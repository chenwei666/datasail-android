# V0.1.0 架构与边界

`app` 是 Compose / ViewModel / StateFlow 界面；`core` 是独立 JVM 模块，包含类型、错误、安全策略和固定 SQL 探针。只有驱动适配器依赖具体 JDBC 库。Activity 不连接数据库。

`ProbeRunner` 每次只运行一个会话，单工作线程、有限队列；取消控制最多两个线程与四个排队动作。取消并行请求 `Statement.cancel` 与 `Connection.close`；驱动若阻塞则继续显示正在清理，不报告成功。连接超时 5 秒、socket 超时 15 秒、正常查询超时 3 秒。资源使用 use/finally 关闭，失败没有重试。

三个探针：连接及 Unicode/NULL/精确小数/SELECT 1；10 秒合成延迟的查询超时；1 秒后请求取消的合成延迟。不会读取业务表或接收任意 SQL。只读探针不是服务端授权：账号仍须最小权限。

TLS 固定为身份校验策略。SQL Server 使用 SQL 账号、固定端口、encrypt=true、trustServerCertificate=false、connectRetryCount=0。MySQL 使用 MariaDB Connector/J，sslMode=verify-full，禁止 local infile、多语句与公钥自动获取。主机/库名校验阻止通过 URL 注入连接参数。当前没有自定义 CA 导入、IPv6、Windows/Kerberos/Entra/Always Encrypted 支持。

密码不在 ConnectionProfile、UiState、SavedState 或日志中。Credentials 持有 char[] 并在结束后清零；JDBC 接口和 Compose 文本输入所需 String 无法保证内存擦除，此处不宣称内存绝对保密。应用关闭备份和截图，进入后台（包括旋转触发的 onStop）请求取消，连接不跨后台保活。

错误仅输出受控分类、SQLState 与数字错误码。原始 Throwable/message、URL、SQL、用户名和密码不进入应用诊断。超时、失联、取消、关闭失败保留 UNKNOWN，不声称服务端事务已结束。

`AiDraftProvider` 只有用户批准文本和 SQL 草稿接口，无驱动或执行权限；本轮无实现。文档、键值、搜索接口仅占位，不伪称已实现多引擎。

本轮无数据库元信息持久化，因此尚未引入 Room 或迁移。添加存储时必须同时实现迁移测试，不能破坏性迁移。
