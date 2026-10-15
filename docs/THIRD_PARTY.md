# 依赖、来源与再构建

本项目自己的代码采用根目录 Apache-2.0 许可。SQL Server 从 V0.2.2 起使用可审计源码派生构建；V0.14.0 又为 MariaDB、PostgreSQL 和 MongoDB 提供可重建源码适配，不动态下载 JAR/dex。版本固定在 `gradle/libs.versions.toml`，下载内容 SHA-256 固定在 `gradle/verification-metadata.xml`；该文件是通过官方 HTTPS 仓库取得的校验基线，不是第三方安全审计结论。

| 组件 | 版本 | 许可 | 官方来源 |
|---|---|---|---|
| Microsoft JDBC | 13.6.0.jre8 | MIT | https://github.com/microsoft/mssql-jdbc/tree/v13.6.0 |
| MariaDB Connector/J | 3.5.10 | LGPL-2.1-or-later | https://github.com/mariadb-corporation/mariadb-connector-j/tree/3.5.10 |
| PostgreSQL JDBC | 42.7.14 | BSD-2-Clause | https://github.com/pgjdbc/pgjdbc/tree/REL42.7.14 |
| Kotlin / Coroutines | 2.2.10 / 1.10.2 | Apache-2.0 | https://github.com/JetBrains/kotlin ; https://github.com/Kotlin/kotlinx.coroutines |
| AndroidX / Compose | 版本目录与 BOM 2025.08.01 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Conscrypt Android | 2.7.0 | Apache-2.0，附带组件许可见下文 | https://github.com/google/conscrypt/tree/2.7.0 |
| ASM（仅构建审查） | 9.8 | BSD-3-Clause | https://asm.ow2.io/ |
| JUnit（仅测试） | 4.13.2 | EPL-1.0 | https://github.com/junit-team/junit4 |

驱动二进制 SHA-256：

```text
79da517f86a982f0c371f804ba06305708c43923786386c2582c81cb3e4ae648  mssql-jdbc-13.6.0.jre8.jar
919b8c1c771d9ee3465811462f242c9543ab401e140c64988ddbf1d8abcb18b2  mariadb-java-client-3.5.10.jar
```

许可全文位于 `docs/licenses/`。交付包包含 MariaDB Connector/J 完整发布源码 JAR：`docs/third-party/mariadb-java-client-3.5.10-sources.jar`，SHA-256 `72f28882a0f60a299c07272d573aebef171380d23ea3ee21860ad76807ee7982`，来自 Maven Central 对应发布坐标的 `-sources.jar`。源码上游构建仓库保留在表中。

V0.13.0 及以前的 LGPL 组件未修改；V0.14.0 的源码修改见 `mariadb-android/`。该组件仍为 LGPL-2.1-or-later，不能将其改称 Apache-2.0。允许为调试 LGPL 修改进行逆向工程。完整应用源码和 Gradle 工程一并交付，用户可以从对应标签取得上游 Maven 构建文件、修改并构建驱动，将 `core` 中的 MariaDB 依赖替换成自己构建的本地 JAR，审查并更新依赖校验，再重建 APK；不要求持有本项目正式签名密钥。自行签名包可能需要不同安装身份或卸载测试包，不能保证覆盖升级。V0.14.0 的依赖清单、修改声明和重新链接步骤随源码归档提供；这不等同第三方法律或安全审计。

默认 TLS 插件源码审查：3.5.10 的 `DefaultTlsSocketPlugin` 既提供 TRUST 模式，也可能用 `MariaDbX509DeferredIdentityTrustManager` 将验证延后至认证阶段。本项目固定使用自己的 `StrictTlsPlugin`，系统 TrustManagerFactory 验证证书链，V0.3.0 起由共享 SAN 匹配器验证主机名；不选择上述路径。`lint.xml` 仅针对该确切 JAR 内未使用的 Trust-All 实现作有依据的例外，应用代码仍受该检查。错误主机名与不受信 CA 的实连回归均必须通过。

Release/R8 未加入生成的批量 `-dontwarn`。缺失 Android API 和可选认证依赖列表见 `docs/evidence/v022/release-missing-rules.txt`，这个文件只作为失败证据，不参与构建。

V0.2.0 保持以上依赖版本与校验元数据不变。新增自有 `SqlServerTrustManager` 使用微软 JDBC 文档化的 `trustManagerClass` / `trustManagerConstructorArg` 扩展，实现平台链验证及 SAN 身份验证，避免 CN 解析依赖 Android 不具备的 JNDI。没有替换 JDBC 驱动、嵌入未经校验的二进制或压制 R8 缺失类。参考：https://learn.microsoft.com/en-us/sql/connect/jdbc/connecting-with-ssl-encryption?view=sql-server-ver17 。

V0.2.1 新增 ASM driverAudit source set，使用现有依赖校验基线中的 9.8 工件。该工具仅报告引用位置，不修改驱动或进入 Android APK。

V0.2.1 固定引入 `org.conscrypt:conscrypt-android:2.7.0`，AAR SHA-256 `86072ce711795e188e1e82214d023789d2983fafe342bcae83f046125cf64e7f`，与 Maven Central 同坐标 `.aar.sha256` 一致；Gradle 元数据也加入校验。Android minSdk21，包含 armeabi-v7a、arm64-v8a、x86、x86_64 原生库。`DataSailApplication` 仅在本进程注册 provider，启用 engine socket 处理 JDBC TDS 包装流；不提供 TrustManagerFactory，保留 Android CA 策略及应用严格链/SAN 校验。该 provider 同时提供加密算法，因此平台变化的 Android 回归单独记录。

Conscrypt 源码/许可证来自固定标签 2.7.0。AAR 内 `conscrypt.properties` 标明 BoringSSL 提交 `7c1efd8d6ffb36a57feba44e8c73cf674801f3cb`；对应 BoringSSL LICENSE、Conscrypt LICENSE/NOTICE、Harmony/Netty 许可均保存在 `docs/licenses/Conscrypt-2.7.0-*`。原生 arm64 ELF LOAD 段 0x4000 对齐，仅静态检查，不代表已通过 arm64/16 KiB 设备验收。

上游 AAR 自带 JNI keep 与 Android 隐藏/旧版 API 的定向 dontwarn；没有添加驱动缺失类的忽略规则。本版 Release 通过情况以最终 DEX 审计与设备测试记录为准；上游规则本身不是运行兼容证明。

V0.2.2 SQL Server 派生版本 `13.6.0.jre8-datasail.1` 的源码来源、精确 patch、校验、签名移除原因、MIT 许可与未适配范围见 `sqlserver-android/README.md`。上表及原驱动 SHA-256 表示上游输入，不代表最终 APK 内未修改的 SQL Server 二进制；交付 manifest 另记录派生 JAR、源码和补丁校验值。


V0.3.0 新增 pgjdbc 42.7.14，上游二进制 SHA-256 `73914527305a40cce504b0d3d90b23caf565136912d607ae8ae7c5895512332c`，POM SHA-256 `b7e66ccf3088cba04d68ec374da4e981686fafe426e4ca69d6d276427175dc63`，从 Maven Central 官方 HTTPS 独立下载复核，与 Gradle 校验一致。上游 LICENSE 保存在 `docs/licenses/pgjdbc-42.7.14-LICENSE`。驱动使用固定 `PostgreSqlTlsFactory`，用户不能指定 JDBC 参数/自定义 factory。已审阅 sources JAR 中 `NonValidatingFactory.NonValidatingTM` 和 `SingleCertValidatingFactory.SingleCertTrustManager`：这些不是本应用选择的证书路径。lint 的 TrustAllX509TrustManager 例外只匹配此版本 JAR；应用自有 TrustManager 继续受检查，必须通过错误 CA/主机名实连反例。没有添加驱动 R8 dontwarn。

V0.3.0 Android 首次 MySQL 证书错误反例暴露上游 HostnameVerifier 的 JNDI CN 回退。本项目插件改为共用已验证的 SAN 匹配逻辑，保留平台链校验，拒绝 CN-only，不再调用上游依赖 LdapName 的方法；并非降低身份校验。新的 JDBC 器件连接阶段最多十五秒（包含 TLS/认证），PostgreSQL 整体登录最多三十秒，以涵盖首次 ART 类加载及软件模拟器握手；SQL Server 现有参数不变。

## V0.4.0 键值协议

Redis / Valkey 使用自有 Apache-2.0 的 RESP2/TLS 实现，不打包服务器代码或第三方 Redis 驱动。协议参考：https://redis.io/docs/latest/develop/reference/protocol-spec/ 与 https://valkey.io/topics/protocol/ 。测试服务器分别使用 Redis 8.2.3 Alpine 和 Valkey 8.1.5 Alpine 的固定镜像摘要，许可证遵循各服务器上游；服务器仅是隔离测试工具，不在 APK 内分发。所有现有运行时依赖版本保持不变。

## V0.5.0 MongoDB

固定 `org.mongodb:mongodb-driver-sync`、`mongodb-driver-core`、`bson` 5.6.5，Apache-2.0；源码与许可证来源 [MongoDB Java Driver r5.6.5](https://github.com/mongodb/mongo-java-driver/tree/r5.6.5)。许可证保存在 `docs/licenses/MongoDB-Java-5.6.5-LICENSE.txt`。选择固定 5.6 维护线进行本版验证，未声称使用最新主版本。JAR/POM/source JAR 均经 Maven Central HTTPS 官方 SHA-1 对照，再将 SHA-256 写入依赖验证及 `docs/evidence/v050/mongo-artifacts.json`；新增 netty-bom 4.1.87.Final 仅依赖管理 POM，并非引入 Netty 运行时。

主动排除 `bson-record-codec`（Java records 不适用于本版 Android 文档模型），没有引入 Netty、云认证、加密、Unix socket、压缩扩展。上述 V0.5.0 默认 BSON registry 的可选探测曾引用缺失 API；V0.14.0 源码适配明确排除不支持的 provider，不使用批量 dontwarn。APK 排除 `META-INF/native-image/**` GraalVM 构建配置，解决两个依赖重复资源；不删除许可证或运行代码。官方 Java 驱动不提供本项目的 Android 兼容承诺，须以独立 ART 实测为准。

MongoDB Community 8.0.32 服务端仅用于隔离测试（SSPL）；镜像 `mongo@sha256:d0d926f94df099bff534b7ee5b5986458131a22489dfff8664509af0c1e2ca9c`，不打包进 APK。测试专用管理员/failpoint 命令仅在测试源码。

Android SASL API 补充模块 `sasl-android`：只包含 Apache Harmony 的未修改 `SaslClient` 接口与 `SaslException`，固定提交 `02970cb7227a335edd2c8457ebdde0195a735733`（Apache-2.0）；原始源码、LICENSE、NOTICE 和 SHA-256 锁文件纳入仓库，每次编译校验，许可通知随模块资源打包。模块没有认证算法或 SASL provider；SCRAM 仍完全由 MongoDB 官方驱动实现，不提供空实现/假成功。仅 app runtime 引入，JVM 使用自身 SASL API，因此必须以 ART 真实正负认证验证模块。

## V0.6.0 搜索实现

APK 未新增第三方依赖：HTTP/TLS 使用 Java/Android 平台，JSON/DSL 与搜索能力代码为本项目 Apache-2.0。验证服务器来自 Docker Hub 官方 `library/elasticsearch:8.19.5`（Elastic License 2.0 的发行包）和 `opensearchproject/opensearch:2.19.4`（Apache-2.0 项目，镜像含各组件许可证），固定 amd64 manifest SHA256 见 DRIVER_MATRIX/evidence。服务器镜像仅为隔离测试环境，不打包或重新分发进 APK/源码 ZIP；未把 Elasticsearch 服务端发行版称作 Apache 许可客户端依赖。版本验证不代表最新版或所有服务版本支持。

V0.9.1 派生版本更新为 `13.6.0.jre8-datasail.2`，同一锁定上游增加 DDC 日期转换源码适配；修改沿用上游 MIT 许可。精确源码和补丁哈希见 upstream.lock.json 与交付 manifest。

## V0.14.0 Android 驱动与混淆适配

SQL Server 更新为 `13.6.0.jre8-datasail.3`。新增 `mariadb-android`、`postgresql-android`、`mongodb-core-android`、`mongodb-sync-android` 源码适配模块。每个模块保留完整上游源码、校验锁、补丁与重建说明，未修改字节通过构建校验。原始与派生版本不得混称。详见各模块 README。

运行时补充 ANTLR4 runtime 4.9.3（BSD-3-Clause，微软 JDBC SQL 参数解析所需）、SLF4J API/no-op 2.0.17（MIT，避免驱动日志输出）、JSR305 3.0.2（Apache-2.0）及 Checker Framework annotations 3.55.1（MIT）。这些是真实上游组件，不是平台占位实现；新增二进制及元数据经 Maven Central HTTPS 校验并固定 SHA-256。原依赖自带许可保留，版本目录和验证元数据是确切依赖清单。

MariaDB 已发生源码修改，完整来源为 `mariadb-android/upstream/`，差异为 `patches/android.patch`；修改及重新链接步骤见该模块 README。应用的 Apache-2.0 许可不覆盖 LGPL 组件。

仪器测试 APK 包含 Error Prone 2.30.0 的两个未修改编译器注解源码（CanIgnoreReturnValue、MustBeClosed，Apache-2.0），精确来源及 SHA-256 见 `app/src/androidTest/upstream-annotations.json`，构建校验原始字节。仅补充 AndroidX Test 所需的真实上游注解，不引入依赖 javac 的其余注解，不进入正式应用 APK。

独立发布附件 `THIRD_PARTY_NOTICES.txt` 汇集随包提供的许可全文和第三方声明；新增 ANTLR、SLF4J、Checker Qualifier 原始许可的来源和 SHA-256 见 `docs/licenses/v0140-license-sources.json`。
