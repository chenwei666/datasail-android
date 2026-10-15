# SQL Server Android 源码适配 · 13.6.0.jre8-datasail.3

这是基于微软 JDBC 13.6.0.jre8 的本地派生构建，不是微软发布的 Android 驱动，也不是完整 Android 兼容声明。

## 构建与来源

`./gradlew :sqlserver-android:check`。需要 Python3、GNU patch、锁定的 JDK21（Java `--release 8`）。源码来自 https://repo.maven.apache.org/maven2/com/microsoft/sqlserver/mssql-jdbc/13.6.0.jre8/mssql-jdbc-13.6.0.jre8-sources.jar ，完整上游源码随项目保存；SHA-256 与官方同坐标 `.sha256` 一致，见 upstream.lock.json。原始二进制仍由 Gradle 固定坐标和 verification-metadata.xml 校验，不运行时下载。

构建从已校验源码选取锁定清单中的 17 个文件，统一 CRLF 为 LF，应用 `patches/android-platform.patch`（fuzz=0），再核对每个补丁后源码的 SHA-256。只编译这些源码文件，生成清单中的 81 个类，其余上游 class/资源保持逐字节一致。verifyProfile 检查 JAR 成员集合、重复条目、未修改部分、修改类的禁用 API 引用与来源记录；所有消费此产物的构建必须先通过检查。

派生 JAR 移除已经失效的上游 JAR 签名及原 manifest，使用自己的 manifest，并加入 MIT 许可与 `META-INF/datasail-profile.json`。不声称保留微软二进制签名。上游源码版权保留；补丁生成的驱动源码修改沿用 MIT 许可。构建脚本按仓库 Apache-2.0 许可。

## 本阶段行为

- MaxResultBufferParser 使用 Runtime.maxMemory，去掉 JMX 依赖；在乘法前比较上限，防止极大 K/M/G/T 参数溢出成负值。沿用上游空值/-1、百分比和 90% 堆上限语义。
- SQLServerCertificateUtils 不再解析 CN，仅允许 SAN 身份路径；无 SAN 或 SAN 不匹配继续拒绝。PKCS#1 客户端私钥明确抛 IOException，不加载可选 BouncyCastle。应用仍用独立的严格链/SAN TrustManager；不因此开放 mTLS。
- DDC 日期转换改用 Android 提供的 java.sql 构造方法，保持 JDK valueOf(java.time) 的本地年月日时分秒与纳秒语义；不向 Android Formatter 传入 LocalTime。修复查看含 date / datetime / datetime2 等字段的表时 NoSuchMethodError 导致断连。NULL 与 datetimeoffset 仍沿原读取路径，不开放日期写入。
- SQLServerConnection 在创建套接字前拒绝集成认证、Entra/访问令牌和池化连接；只接受普通 SQL 密码直连。Azure Key Vault、enclave attestation 分支明确抛出不支持异常，未触碰正常 TLS 登录、回滚和关闭实现。
- 移除 Android 不具备的 SQLType 重载，保留整数 JDBC 类型 API；移除 GSSCredential 数据源 API 与 JavaBeans 注解。
- SQLServerSQLXML 保留字符串、流、DOM/SAX 原有路径与 XML 安全设置；StAX 类型请求进入不支持异常路径，删除 Android 没有的 StAX 私有实现。没有返回空成功对象。

应用配置 maxResultBuffer=8M（8,000,000 字节，最多最大堆容量的 90%）。它限制驱动保留的缓冲区，不限制自适应流累计字节，也不保证整个应用堆只有 8 MB；行/列/字段读取限制仍独立执行。实连反例显式选择 full buffering，以确认超限被拒绝；正常查询保持 adaptive。

15 个本模块 JVM 用例覆盖溢出/非法参数、SAN/CN 负例、PKCS#1/StAX 拒绝、XML 流单次消费以及日期精度、日历边界、默认时区和时间舍入；公共测试证书仅含合成公钥，不包含私钥。Android 与真实数据库证据见 docs/TEST_REPORT.md。

## 能力边界

此派生驱动只为数舟已实现的 SQL 账号直连路径提供 Android 适配，不提供桌面数据源/JNDI、GSS/Kerberos/Entra、XA、Azure Key Vault 或 enclave 功能。其余上游字节保留，R8 根据实际入口移除不可达路径；没有批量 dontwarn 或假平台实现。

`verifyProfile` 的原平台引用检查继续保护最初的堆预算、TLS、XML 和日期补丁；新增源码通过精确哈希、完整 class 清单、未修改字节比较及最终 APK 的缺失引用检查验证。实际设备与数据库测试范围见 `docs/TEST_REPORT.md`，不能从 JVM 单测推断 Android 已验收。
