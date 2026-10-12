# V0.3.0 驱动与能力矩阵

当前实际实现五种连接类型；下表区分功能实现与测试证据。完整验收命令和系统范围见 TEST_REPORT。

| 引擎 | 驱动 | 工作台实现 | 图形写入 | 特有约束 |
|---|---|---|---|---|
| SQL Server | mssql-jdbc 13.6.0.jre8-datasail.1 / MIT | 库、Schema、表/视图、字段、分页、SELECT | 测试环境安全单行确认 | 现有保护保持，生产只读 |
| MySQL | MariaDB Connector/J 3.5.10 / LGPL-2.1-or-later | 库、表/视图、字段、主键分页、SELECT | 未开放 | 每操作服务器只读事务；无符号 BIGINT 用精确 Decimal |
| MariaDB | 同上，独立服务器验收 | 同上 | 未开放 | 不以复用驱动推断兼容 |
| PostgreSQL | pgjdbc 42.7.14 / BSD-2-Clause | Schema、表/视图、字段、主键分页、SELECT | 未开放 | 一连接一库；参数化系统目录绕开 Android 缺少的 closeOnCompletion |
| SQLite | Android 系统 SQLiteDatabase | 文件副本、表/视图、字段、主键分页、SELECT | 未开放 | 64 MiB、一致性非 WAL 副本、OPEN_READONLY |

本轮实测：SQL Server 15 项 JVM/每系统 2 项设备；MySQL/MariaDB/PostgreSQL 各 5 项 JVM/每系统各 2 项设备；SQLite 每系统 2 项设备。全部通过。另有 MySQL 原探针 7 项、单元 45 项、每系统 UI/升级 4 项。总计 82 项 JVM 与 28 项设备测试，见 summary.json。

所有网络连接均校验证书链与主机身份；SQL Server、PostgreSQL 通过严格 TrustManager，MySQL/MariaDB 通过 StrictTlsPlugin 的平台链校验与共享 SAN 匹配器。支持连接专用公共 CA；拒绝 CN-only，无信任全部或明文回退。新增网络引擎连接预算 15 秒，PostgreSQL 总登录预算 30 秒，socket 15 秒、工作台语句 10 秒。

服务端固定镜像：

- SQL Server 2022 CU16 Developer：`mcr.microsoft.com/mssql/server@sha256:45a1a9d13ca5574cf8e0fe4ae73ab77248b66d9c3132ac9658fb6c16dd72a8af`
- MySQL 8.4.6：`mysql@sha256:869218921e61d6c3c89820955d63cca42971f0e3e6c1e2792247bbd944ebc6e9`
- MariaDB 11.8.9：`mariadb@sha256:6422478cb8e159f080fb1d8ccf65101e26fe51385787fde7d16c3b165a331f15`
- PostgreSQL 18.3：`postgres@sha256:7e32e9833a6fb1c92c32552794cb6ed569d51b445a54907d35fc112ef39684db`

JVM 使用 Temurin 21.0.8+9；设备是 Android 8/API26 与 Android 11/API30 AOSP x86_64 软件模拟器。真机、arm64、云认证、其它服务器版本尚无验证。Release/R8 仍失败，不将 Debug 验收冒充混淆发行兼容。

其它引擎见 DATABASE_SCOPE，全部仍为规划项目。下一批优先 Redis/Valkey、MongoDB、Elasticsearch/OpenSearch、ClickHouse；Oracle、Db2、国产及兼容协议数据库逐项接入，不能将协议相似推断为已支持。
