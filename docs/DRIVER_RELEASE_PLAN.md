# Android 驱动混淆适配（V0.14.0）

本轮从 193 类 R8 缺失引用基线出发，为应用实际使用的 TCP/TLS、账号认证和同步数据库路径提供源码适配。正常 `assembleRelease` 已通过，不使用 `-ignorewarnings` 或批量 `-dontwarn`。最终 DEX 审计和实际设备结果见 [测试报告](TEST_REPORT.md)；构建通过不是数据库兼容性结论。

| 模块 | 派生版本 | Android 边界 |
|---|---|---|
| sqlserver-android | 13.6.0.jre8-datasail.3 | 保留 SQL 账号、TLS、JDBC 整数类型 API、事务和关闭；集成认证/GSS、云令牌、连接池/XA、可选密钥库与 enclave 分支明确拒绝 |
| postgresql-android | 42.7.14-datasail.1 | 保留 TCP/TLS、密码/SCRAM；Runtime 堆预算；拒绝 GSS/SSPI 和 StAX；SAN 身份验证 |
| mariadb-android | 3.5.10-datasail.1 | 保留 MySQL/MariaDB TCP/TLS 与普通密码认证；拒绝 JNA 本地传输、GSS、AWS、JMX、XA 和缺少提供者的 Parsec；普通连接关闭保留 |
| mongodb-core-android / mongodb-sync-android | 5.6.5-datasail.1 | 保留同步 TCP/TLS、SCRAM、BSON/Document 和 zlib；拒绝异步/Netty、本地传输、其它认证、SRV/TXT、snappy/zstd 和自动客户端字段加密 |

每个模块保存完整上游源码 JAR、来源及 SHA-256、源码补丁、修改后源码哈希和 class 清单。构建仅重编译修改源文件，与锁定上游 JAR 合并；其余 class/资源逐字节校验，失效上游签名移除，派生 JAR 使用自身版本和元数据。没有占位平台类，也不改写字节码规避依赖。

MariaDB 的 LGPL 源码和重新链接说明见该模块 README。真实 ANTLR、SLF4J、BSON 等运行依赖单独固定版本和验证元数据。AndroidTest 所需两个 Error Prone 注解使用逐字节校验的原始源码，仅进入测试 APK。

## R8 运行适配

- JDBC/TLS 反射入口保留必要类名和构造器。
- MariaDB/MySQL 按属性名反射绑定的 Builder 和配置字段保留；连接前再次核验账号、TLS、超时、本地文件和多语句策略，异常时在打开 socket 前拒绝。
- SQL Server 按名称加载的 `SQLServerResource*` 消息资源保留，避免驱动初始化时 `MissingResourceException`。
- `release-test-api.pro` 从实际测试调用点及外部覆盖接口生成精确共享 ABI 规则，独立测试 APK 与真实 Release 链接；Kotlin 默认接口适配器和协程探针保留必要名称。没有整库 keep。
- 使用正式签名、不可调试的真实混淆 APK 执行设备验证，合成夹具仅传入可丢弃 SDK 模拟器。Debug 通过不替代 Release 结果。

## 重建

参见 [正式构建说明](RELEASE_BUILD.md)。CI 对每个推送/PR 执行未签名 R8 构建及 DEX 基线检查；生产私钥不进入普通 CI。

后续仍需真实 Android/arm64 设备、其它服务器版本及扩展认证的独立验证。新增驱动功能必须补充来源、运行依赖、正负例和真实混淆 APK 验证，不能根据桌面驱动支持推断 Android 支持。
