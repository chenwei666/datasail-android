# 依赖、来源与再构建

本项目自己的代码采用根目录 Apache-2.0 许可。未修改第三方驱动字节码，不动态下载 JAR/dex。版本固定在 `gradle/libs.versions.toml`，下载内容 SHA-256 固定在 `gradle/verification-metadata.xml`；该文件是通过官方 HTTPS 仓库取得的校验基线，不是第三方安全审计结论。

| 组件 | 版本 | 许可 | 官方来源 |
|---|---|---|---|
| Microsoft JDBC | 13.6.0.jre8 | MIT | https://github.com/microsoft/mssql-jdbc/tree/v13.6.0 |
| MariaDB Connector/J | 3.5.10 | LGPL-2.1-or-later | https://github.com/mariadb-corporation/mariadb-connector-j/tree/3.5.10 |
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

LGPL 组件未修改，不能将其改称 Apache-2.0。允许为调试 LGPL 修改进行逆向工程。完整应用源码和 Gradle 工程一并交付，用户可以从对应标签取得上游 Maven 构建文件、修改并构建驱动，将 `core` 中的 MariaDB 依赖替换成自己构建的本地 JAR，审查并更新依赖校验，再重建 APK；不要求持有本项目正式签名密钥。自行签名包可能需要不同安装身份或卸载测试包，不能保证覆盖升级。正式公开分发前仍须完成全部依赖许可清单与再链接流程的发行审查。

默认 TLS 插件源码审查：3.5.10 的 `DefaultTlsSocketPlugin` 既提供 TRUST 模式，也可能用 `MariaDbX509DeferredIdentityTrustManager` 将验证延后至认证阶段。本项目固定使用自己的 `StrictTlsPlugin`，系统 TrustManagerFactory 验证证书链，驱动 HostnameVerifier 验证主机名；不选择上述路径。`lint.xml` 仅针对该确切 JAR 内未使用的 Trust-All 实现作有依据的例外，应用代码仍受该检查。错误主机名与不受信 CA 的实连回归均必须通过。

Release/R8 未加入生成的批量 `-dontwarn`。缺失 Android API 和可选认证依赖列表见 `docs/evidence/release-missing-rules.txt`，这个文件只作为失败证据，不参与构建。

V0.2.0 保持以上依赖版本与校验元数据不变。新增自有 `SqlServerTrustManager` 使用微软 JDBC 文档化的 `trustManagerClass` / `trustManagerConstructorArg` 扩展，实现平台链验证及 SAN 身份验证，避免 CN 解析依赖 Android 不具备的 JNDI。没有替换 JDBC 驱动、嵌入未经校验的二进制或压制 R8 缺失类。参考：https://learn.microsoft.com/en-us/sql/connect/jdbc/connecting-with-ssl-encryption?view=sql-server-ver17 。

V0.2.1 新增 ASM driverAudit source set，使用现有依赖校验基线中的 9.8 工件。该工具仅报告引用位置，不修改驱动或进入 Android APK。

V0.2.1 固定引入 `org.conscrypt:conscrypt-android:2.7.0`，AAR SHA-256 `86072ce711795e188e1e82214d023789d2983fafe342bcae83f046125cf64e7f`，与 Maven Central 同坐标 `.aar.sha256` 一致；Gradle 元数据也加入校验。Android minSdk21，包含 armeabi-v7a、arm64-v8a、x86、x86_64 原生库。`DataSailApplication` 仅在本进程注册 provider，启用 engine socket 处理 JDBC TDS 包装流；不提供 TrustManagerFactory，保留 Android CA 策略及应用严格链/SAN 校验。该 provider 同时提供加密算法，因此平台变化的 Android 回归单独记录。

Conscrypt 源码/许可证来自固定标签 2.7.0。AAR 内 `conscrypt.properties` 标明 BoringSSL 提交 `7c1efd8d6ffb36a57feba44e8c73cf674801f3cb`；对应 BoringSSL LICENSE、Conscrypt LICENSE/NOTICE、Harmony/Netty 许可均保存在 `docs/licenses/Conscrypt-2.7.0-*`。原生 arm64 ELF LOAD 段 0x4000 对齐，仅静态检查，不代表已通过 arm64/16 KiB 设备验收。

上游 AAR 自带 JNI keep 与 Android 隐藏/旧版 API 的定向 dontwarn；没有添加驱动缺失类的忽略规则。Release 仍按实际失败记录；上游规则本身也不是运行兼容证明。
