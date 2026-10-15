# 数舟 / DataSail V0.14.0

数舟是一款原生 Android 数据库管理工具：连接服务器、浏览数据库与表、筛选排序、编辑单条记录、编写 SQL，并通过可选 AI 助手解释结构、生成草稿或预览受限变更。

本版重点是正式签名与混淆发行：保持 `com.example.datasail`，versionCode 为 **22**。APK 使用独立 RSA-4096 发布证书、R8 混淆与资源收缩，关闭调试。SQL Server、PostgreSQL、MariaDB/MySQL、MongoDB 驱动增加可重建的 Android 源码适配；不使用忽略缺失类警告的方式生成安装包。

- SQL 工作台：表/视图浏览、列头筛选与排序、分页、双指缩放、全屏表格、字段详情、表收藏、SQL 编辑与补全。
- 数据修改：在支持的类型和对象范围内新增、修改、删除，预览确认、并发校验；SQLite 修改私有副本。无稳定主键、未完整读取等情况保持只读。
- 连接与隐私：历史连接、可选加密保存密码、严格 TLS、连接专用 CA 或显式证书指纹信任。
- AI：设置中配置服务和模型、获取上游模型、问答与 SQL 草稿、当前表受限操作、本地加密上下文记忆；发送上下文及写入需用户明确选择和确认。

**安装提醒：V0.13.0 及之前使用 Debug 证书，不能直接被本版正式签名覆盖。** 先记录需要保留的连接、收藏、SQL 和设置，再决定是否卸载旧版。卸载会删除本地数据及加密密钥；目前没有完整配置迁移工具。以后的正式版沿用这次证书。

当前引擎范围为 SQL Server、MySQL、MariaDB、PostgreSQL、SQLite、Redis、Valkey、MongoDB、Elasticsearch 和 OpenSearch，能力并不相同。云 IAM、GSS/Kerberos、XA、MongoDB SRV/自动字段加密等桌面驱动扩展不在本版范围；其它数据库仍按路线图逐项接入。实际测试平台、通过项和限制见附件 `TEST_REPORT.md`，不承诺所有服务器版本、真机或完整 DBeaver 功能。

下载 `DataSail-V0.14.0-release.apk` 安装；完整源码、驱动适配、测试证据和 R8 mapping 在 `DataSail-V0.14.0.zip`。校验值见 `SHA256SUMS.txt`。正式证书 SHA-256：

`86d3ec30bb7bb3ab44a41b1268d40003a746c2ecda078b7db5ad249ce02ae93c`

## English

DataSail is a native Android database client for browsing databases and tables, filtering and sorting rows, editing individual records, writing SQL, and optionally using AI for explanations, SQL drafts and constrained change previews.

V0.14.0 (versionCode **22**, application ID `com.example.datasail`) introduces an independent RSA-4096 production signature, R8 minification, resource shrinking and a non-debuggable Release APK. Rebuildable source adaptations make the bundled SQL Server, PostgreSQL, MariaDB/MySQL and MongoDB drivers fit the supported Android connection paths. Missing-class warnings are not suppressed.

Features include a paged spreadsheet view with pinch zoom and full-screen editing, table favorites, SQL completion, saved connections, optional encrypted passwords, strict TLS, configurable AI providers and model discovery, and encrypted local conversation memory. Writes remain bounded by engine/type support, explicit previews and concurrency checks. SQLite edits an application-private copy.

**Older APKs through V0.13.0 used a Debug certificate and cannot be overwritten by this production-signed APK.** Preserve connection details, saved SQL, favorites and settings before deciding to uninstall. Uninstallation removes local data and encryption keys; a complete configuration migration tool is not available. Future official releases must reuse this production certificate.

The current engine scope is SQL Server, MySQL, MariaDB, PostgreSQL, SQLite, Redis, Valkey, MongoDB, Elasticsearch and OpenSearch, with different capabilities. Desktop driver extensions such as cloud IAM, GSS/Kerberos, XA, MongoDB SRV and automatic field encryption are outside this release. Consult `TEST_REPORT.md` for the actual tested platforms, results and limitations; this is not a claim of all-server, physical-device or full DBeaver compatibility.

Install `DataSail-V0.14.0-release.apk`. The ZIP contains the complete source, driver profiles, test evidence and R8 mapping. Verify downloads using `SHA256SUMS.txt` and the certificate fingerprint above.
