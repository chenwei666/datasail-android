# V0.2.0 实际驱动兼容矩阵

日期：2026-10-12。JVM、模拟器、真机分别记录，全部驱动仍为实验性。

| 项目 | SQL Server | MySQL |
|---|---|---|
| artifact | com.microsoft.sqlserver:mssql-jdbc | org.mariadb.jdbc:mariadb-java-client |
| 版本 / 许可 | 13.6.0.jre8 / MIT | 3.5.10 / LGPL-2.1-or-later |
| 服务端 | SQL Server 2022 CU16 Developer，隔离容器 | MySQL Community 8.4.6，隔离容器 |
| 认证 | SQL 账号；JVM/Android 实测 | caching_sha2_password；JVM 实测 |
| 严格 TLS | 平台链校验 + SqlServerTrustManager SAN 校验；系统或连接专用 CA | verify-full + StrictTlsPlugin；系统或连接专用 CA |
| JVM 连接/类型/错误证书/错误账号 | 通过 | 通过 |
| JVM 超时/取消 | 通过；取消仍标 UNKNOWN | 通过；SLEEP 超时返回中断标记 |
| JVM 工作台/写入/冲突/回滚 | 12 个集成测试通过；丢失提交确认故障注入返回 UNKNOWN | 本版未实现 |
| Android 11 x86_64 严格 TLS、表读取、增改删 | 软件模拟器通过，包含不受信 CA / 错误主机名拒绝和并发冲突 | 未执行数据库实连 |
| Android JDBC 入口类加载 | 通过 | 通过；不等于实连 |
| Debug D8 | 通过 | 通过 |
| Release/R8 | 同一构建失败：仍有缺失平台/可选 API | 同一构建失败 |
| API26 / Android 11 真机 / arm64 | 未执行 | 未执行 |

Android：API30 / Android 11 AOSP x86_64，软件 TCG，无 KVM。确切 build、最终命令和结果见 TEST_REPORT 与 docs/evidence/v020。JVM：Linux x86_64 / Temurin 21.0.8+9。

SQL Server 镜像：`mcr.microsoft.com/mssql/server@sha256:45a1a9d13ca5574cf8e0fe4ae73ab77248b66d9c3132ac9658fb6c16dd72a8af`。MySQL 镜像：`mysql@sha256:869218921e61d6c3c89820955d63cca42971f0e3e6c1e2792247bbd944ebc6e9`。临时端口只映射到 127.0.0.1；不使用业务账号。

Android 初次可信 TLS 连接暴露 `javax.naming.ldap.LdapName` 缺失；通过微软公开 `trustManagerClass` 扩展实现严格证书链与 SAN 校验后通过实连。不添加信任全部证书逻辑，也不声称修复 R8 所有缺失路径。只含 CN 的证书拒绝。

未支持：Windows/Kerberos/Entra、Always Encrypted、实例发现、IPv6、mTLS、其它数据库工作台。MariaDB 服务器未因复用驱动而被宣称已验证。硬断网、服务端取消后的最终请求状态、混淆 Release、真机性能仍需独立验收。
