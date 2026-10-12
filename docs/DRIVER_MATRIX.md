# V0.1.0 实际驱动兼容矩阵

证据日期：2026-10-12。**JVM 实连结果不代表 Android 实连。** 全部 Android 驱动状态仍为实验性，未宣称生产可用。

| 项目 | SQL Server | MySQL |
|---|---|---|
| artifact | com.microsoft.sqlserver:mssql-jdbc | org.mariadb.jdbc:mariadb-java-client |
| 版本 / 许可 | 13.6.0.jre8 / MIT | 3.5.10 / LGPL-2.1-or-later |
| 服务端 | 未运行；计划 SQL Server 2022 CU16 Developer 隔离容器 | MySQL Community Server 8.4.6，官方容器固定 digest |
| 认证 | 固定 SQL 账号路径；未实测 | caching_sha2_password 合成账号，JVM 实测 |
| TLS 策略 | encrypt=true，trustServerCertificate=false | verify-full + StrictTlsPlugin，系统证书链验证及主机名校验 |
| 连接 / 断开 | 未执行 | JVM 通过，客户端资源释放 |
| SELECT 1 / Unicode / NULL / 精确小数 | 未执行 | JVM 通过，中文、越南语、前导零、DECIMAL(24,8) |
| 正向 TLS / 服务端确认加密 | 未执行 | JVM 通过，测试 CA 信任链；Ssl_cipher 非空 |
| 错误主机名 / 不受信 CA | 未执行 | JVM 均拒绝，分类 TLS |
| 错误账号 | 未执行 | JVM 拒绝，分类 AUTHENTICATION |
| 查询超时 | 未执行 | JVM 通过，约 3 秒；MySQL SLEEP 中断标记为 1 |
| 取消 | 未执行 | JVM 请求 cancel/close 并完成清理；服务端最终状态仍标为 UNKNOWN |
| Debug D8 打包 | 通过 | 通过 |
| Release/R8 | 失败：缺失 Android/JDBC/管理/认证 API 与可选依赖 | 同一次 R8 失败，详见缺失类证据 |
| Android 8/API26、Android 11/API30、ABI | 均未执行 | 均未执行 |
| 真机 / 覆盖升级 / 性能 | 未执行 | 未执行 |

实连宿主是 Linux x86_64 / Temurin JDK 21.0.8+9，不是 Android/ART。APK 声明 minSdk 26；APK 可构建不构成任何 Android 版本/ABI 兼容认证。仪器测试 APK 已构建，设备执行尚未进行。

MySQL 镜像：`mysql@sha256:869218921e61d6c3c89820955d63cca42971f0e3e6c1e2792247bbd944ebc6e9`。容器只映射到 127.0.0.1 动态端口，每轮生成合成账号和临时 CA，结束删除容器及凭据；不会扫描网络或使用企业/生产账号。

SQL Server 容器清单可访问，但镜像配置下载重定向至 `centralus.data.mcr.microsoft.com` 被代理 HTTP 403 拒绝。没有登录 SQL Server、没有推断服务端兼容性。域名已记录在环境草稿；继续步骤见 TESTING.md。

未支持：Windows/Kerberos/Entra、Always Encrypted、实例发现、自定义 CA 导入、IPv6、事务编辑、任意 SQL、业务元数据、其它数据库。MariaDB 服务器也未因为复用驱动而被宣称已验证。
