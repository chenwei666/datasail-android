# V0.7.1 实际测试报告

版本 0.7.1 / versionCode 10；2026-10-13（Asia/Bangkok）。保持 com.example.datasail 和原有 Debug 签名。本次为实验性 Debug 交付，无 Release APK。

## 修改与验证目标

用户使用 IP 连接未手动配置证书的 SQL Server，严格默认模式拒绝 TLS。本次新增显式证书预览、核对、按连接指纹信任，而不是默认忽略证书错误。九种网络引擎共用策略；SQLite 不使用网络 TLS。

主要代码：core/CertificateTrust.kt、SqlServerTrustManager.kt、JdbcConnector.kt、PostgreSqlTlsFactory.kt、StrictTlsPlugin.kt、KeyValueSession.kt、MongoSession.kt、SearchSession.kt；app/ConnectionStore.kt、ConnectionsScreen.kt、WorkspaceViewModel.kt。新增共用真实协议 CertificateTrustContract、自动证书 SQL Server 测试、证书确认 Android UI 测试及核心/表单回归；脚本将本轮证据写入 v071，旧版本证据不覆盖。

## 构建与静态检查

- `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain`：通过；最终代码再执行核心/应用单元与 lint，退出 0，lint 无问题。
- 各 SQL Server fixture 脚本再次 assembleDebug/AndroidTest，最终 APK 哈希与两台设备一致。
- `./gradlew :app:assembleRelease --console=plain`：已尝试，退出 1；R8 缺少 193 类，和 V0.7.0 的缺失类集合相同，无新增或移除。未批量 dontwarn；详见 release-comparison.json 和原始日志。
- 驱动 profile 源码/上游完整性检查重复通过；11 项历史缓存驱动测试未计入本轮新测试数。

## JVM：109 项通过

| 套件/引擎 | 测试数 | 结果 |
|---|---:|---|
| unit-core | 54 | 通过 |
| unit-app | 9 | 通过 |
| SQL_SERVER | 15 | 通过 |
| SQL_SERVER_AUTOCERT | 1 | 通过 |
| MYSQL | 5 | 通过 |
| MARIADB | 5 | 通过 |
| POSTGRESQL | 5 | 通过 |
| REDIS | 3 | 通过 |
| VALKEY | 3 | 通过 |
| MONGODB | 3 | 通过 |
| ELASTICSEARCH | 3 | 通过 |
| OPENSEARCH | 3 | 通过 |

实际命令：`scripts/test-sqlserver-local.sh`（默认 CA 模式及 `DATASAIL_SQLSERVER_AUTOCERT=1`）、`scripts/test-jdbc-workspace-local.sh MYSQL|MARIADB|POSTGRESQL`、`scripts/test-key-value-local.sh REDIS|VALKEY`、`scripts/test-mongo-local.sh`、`scripts/test-search-local.sh ELASTICSEARCH|OPENSEARCH`。均为固定 digest 的隔离真实服务器和合成账号，具体版本/驱动沿用 DRIVER_MATRIX；没有接入用户服务器。

每种网络引擎的共用合约检查：默认无 CA 的 IP 连接拒绝；预览在 TLS 阶段失败但取得公开证书；精确指纹可扫描，JDBC 额外验证正式目标登录；错误指纹不能回退；注销的预览令牌不能继续认证。非 SQL Server 自动证书夹具使用临时内部 CA 签发、IP 不在 SAN 中的叶证书。SQL Server 自动证书模式不挂载 TLS 配置或证书，重现用户“安装后直接使用”的设置。

核心测试另验证 CN-only 自签名叶证书默认拒绝、精确指纹通过、过期/空链拒绝、即使 CA/SAN 可通过也不得绕过错误指纹、HTTPS 附加校验仍绑定地址和指纹、已有匹配指纹时预览仍必须中止、不同预览隔离。表单测试验证连接 ID/引擎/地址/端口绑定，以及 TLS 错误不能泄露原始远端文本或误导用户绕过扫描。原有严格 CA/SAN、认证、只读、数据类型、分页、安全写入与取消合约继续运行。

开发中发现 SQL Server 的 SqlPassword 在 TLS 前要求非空密码，初版空密码预览因此未取得证书；已改为 JDBC 预览专用占位值，TrustManager 总是中止握手、不发送认证，自动证书 JVM 和 Android 测试通过。该占位值不是用户密码，也不是实际登录。

## 最终 APK Android：60 项通过

| 引擎/设备 | 测试数 | 结果 |
|---|---:|---|
| SQL_SERVER/emulator-5556 | 2 | 通过 |
| SQL_SERVER_AUTOCERT/emulator-5554 | 2 | 通过 |
| MYSQL/emulator-5556 | 4 | 通过 |
| MARIADB/emulator-5556 | 4 | 通过 |
| POSTGRESQL/emulator-5556 | 4 | 通过 |
| REDIS/emulator-5556 | 3 | 通过 |
| VALKEY/emulator-5556 | 3 | 通过 |
| MONGODB/emulator-5556 | 4 | 通过 |
| ELASTICSEARCH/emulator-5556 | 5 | 通过 |
| OPENSEARCH/emulator-5556 | 5 | 通过 |
| mobile/emulator-5556 | 8 | 通过 |
| upgrade/emulator-5556 | 4 | 通过 |
| mobile/emulator-5554 | 8 | 通过 |
| upgrade/emulator-5554 | 4 | 通过 |

API26 Android 8 与 API30 Android 11 均为 x86_64、TCG 模拟器，详细系统指纹见 devices.json。不是 arm64/真机证据。早期 APK 另通过 2 项 API26 自动证书 UI 检查，原始结果存 diagnostics/intermediate-autocert-api26.txt，未计入最终 APK 数量；最终 APK 的完整自动证书 UI 流程在 API30 通过。

自动证书 UI 测试实际操作：无需密码获取证书，未勾选核对不能确认；确认后从磁盘重新读取绑定的信任记录；输入密码扫描 master、选中并连接；目标变化及取消不能保存旧预览。其余真实引擎的驱动测试运行同一指纹合约，SQL 连接页扫描和既有 MongoDB/搜索 UI 流程另有回归。

## 覆盖升级与产物

两个明确选定的可丢弃模拟器执行 `scripts/test-android-upgrade.sh --reset-synthetic-emulator`：先安装不可变 V0.7.0/code9，写入合成连接/收藏/标记，再 `install -r` 升级到 code10；标记、SQL Server 生产只读配置、MongoDB authSource、OpenSearch 索引范围、PostgreSQL 查询收藏均保留。没有把卸载重装最终 APK 当成升级通过。

- APK SHA-256：`9520c3345315cbffbb0961248de6722ea4a1f37a8094378c732af98ac23fe182`；23997984 字节。
- AndroidTest SHA-256：`4e2dd3401ad327cd243602424029aa15465426959f7866e43f5036ed866d1802`。
- Debug 证书 SHA-256：`1c0f10a1cc14cc44b1eefc18a78de23b34ce30761b70dae039bea7f302cc78db`，与上一版本一致。
- 交付目录：`versions/V0.7.1/` 与 `versions/V0.7.1.zip`；干净提交生成完整源码、APK、文档、manifest、SHA256SUMS，归档后校验另记 verification.json。

## 限制与下一步

仍需服务端支持 TLS；未启用 TLS、过期证书、旧协议/算法、Windows 集成认证不因本次改动自动兼容。首次指纹必须独立核对；SQL Server 自动证书更换后需要重新确认。不宣称已支持未接入的市场全部数据库。用户实际服务器、真机/arm64、源归档独立重构建均未验证。下一步最小任务是在用户 SQL Server 上用新证书流程验证，并根据实际版本排查剩余协议问题。

云环境完整 start_skill 已更新并保存为待发布草稿，未发布、未验证新任务恢复。未推送或公开发布代码/APK。
