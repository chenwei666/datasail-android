# 依赖、来源与再构建

本项目自己的代码采用根目录 Apache-2.0 许可。未修改第三方驱动字节码，不动态下载 JAR/dex。版本固定在 `gradle/libs.versions.toml`，下载内容 SHA-256 固定在 `gradle/verification-metadata.xml`；该文件是通过官方 HTTPS 仓库取得的校验基线，不是第三方安全审计结论。

| 组件 | 版本 | 许可 | 官方来源 |
|---|---|---|---|
| Microsoft JDBC | 13.6.0.jre8 | MIT | https://github.com/microsoft/mssql-jdbc/tree/v13.6.0 |
| MariaDB Connector/J | 3.5.10 | LGPL-2.1-or-later | https://github.com/mariadb-corporation/mariadb-connector-j/tree/3.5.10 |
| Kotlin / Coroutines | 2.2.10 / 1.10.2 | Apache-2.0 | https://github.com/JetBrains/kotlin ; https://github.com/Kotlin/kotlinx.coroutines |
| AndroidX / Compose | 版本目录与 BOM 2025.08.01 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| JUnit（仅测试） | 4.13.2 | EPL-1.0 | https://github.com/junit-team/junit4 |

驱动二进制 SHA-256：

```text
79da517f86a982f0c371f804ba06305708c43923786386c2582c81cb3e4ae648  mssql-jdbc-13.6.0.jre8.jar
919b8c1c771d9ee3465811462f242c9543ab401e140c64988ddbf1d8abcb18b2  mariadb-java-client-3.5.10.jar
```

许可全文位于 `docs/licenses/`。交付包包含 MariaDB Connector/J 完整发布源码 JAR：`docs/third-party/mariadb-java-client-3.5.10-sources.jar`，SHA-256 `72f28882a0f60a299c07272d573aebef171380d23ea3ee21860ad76807ee7982`，来自 Maven Central 对应发布坐标的 `-sources.jar`。源码上游构建仓库保留在表中。

LGPL 组件未修改，不能将其改称 Apache-2.0。允许为调试 LGPL 修改进行逆向工程。完整应用源码和 Gradle 工程一并交付，用户可以从对应标签取得上游 Maven 构建文件、修改并构建驱动，将 `core` 中的 MariaDB 依赖替换成自己构建的本地 JAR，审查并更新依赖校验，再重建 APK；不要求持有本项目正式签名密钥。自行签名包可能需要不同安装身份或卸载测试包，不能保证覆盖升级。正式公开分发前仍须完成全部依赖许可清单与再链接流程的发行审查。

默认 TLS 插件源码审查：3.5.10 的 `DefaultTlsSocketPlugin` 既提供 TRUST 模式，也可能用 `MariaDbX509DeferredIdentityTrustManager` 将验证延后至认证阶段。本项目固定使用自己的 `StrictTlsPlugin`，系统 TrustManagerFactory 验证证书链，驱动 HostnameVerifier 验证主机名；不选择上述路径。`lint.xml` 仅针对该确切 JAR 内未使用的 Trust-All 实现作有依据的例外，应用代码仍受该检查。错误主机名与不受信 CA 的实连回归均必须通过。

Release/R8 未加入生成的批量 `-dontwarn`。缺失 Android API 和可选认证依赖列表见 `docs/evidence/release-missing-rules.txt`，这个文件只作为失败证据，不参与构建。

V0.2.0 保持以上依赖版本与校验元数据不变。新增自有 `SqlServerTrustManager` 使用微软 JDBC 文档化的 `trustManagerClass` / `trustManagerConstructorArg` 扩展，实现平台链验证及 SAN 身份验证，避免 CN 解析依赖 Android 不具备的 JNDI。没有替换 JDBC 驱动、嵌入未经校验的二进制或压制 R8 缺失类。参考：https://learn.microsoft.com/en-us/sql/connect/jdbc/connecting-with-ssl-encryption?view=sql-server-ver17 。
