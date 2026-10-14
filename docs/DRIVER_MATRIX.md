# V0.9.1 驱动与能力矩阵

当前实际实现十种连接类型；下表区分功能实现与测试证据。完整验收命令和系统范围见 TEST_REPORT。

| 引擎 | 驱动 | 工作台实现 | 图形写入 | 特有约束 |
|---|---|---|---|---|
| SQL Server | mssql-jdbc 13.6.0.jre8-datasail.2 / MIT | 库、Schema、表/视图、字段、分页、SELECT | 测试环境单元格/单行预览确认 | 现有保护保持，生产只读 |
| MySQL | MariaDB Connector/J 3.5.10 / LGPL-2.1-or-later | 库、表/视图、字段、主键分页、SELECT | 未开放 | 每操作服务器只读事务；无符号 BIGINT 用精确 Decimal |
| MariaDB | 同上，独立服务器验收 | 同上 | 未开放 | 不以复用驱动推断兼容 |
| PostgreSQL | pgjdbc 42.7.14 / BSD-2-Clause | Schema、表/视图、字段、主键分页、SELECT | 未开放 | 一连接一库；参数化系统目录绕开 Android 缺少的 closeOnCompletion |
| SQLite | Android 系统 SQLiteDatabase | 文件副本、表/视图、字段、主键分页、SELECT | 未开放 | 64 MiB、一致性非 WAL 副本、OPEN_READONLY |
| Redis | 自有 RESP2/TLS（Apache-2.0） | SCAN、类型、TTL、六类键预览 | 未开放 | 单节点、严格响应预算、只读 ACL |
| Valkey | 同上，独立服务器验收 | 同上 | 未开放 | 不以协议复用推断兼容 |
| MongoDB | MongoDB Java Driver 5.6.5 / Apache-2.0 | 集合、受限 JSON 筛选、文档游标分页、canonical Extended JSON | 未开放 | 单地址直连、SCRAM-SHA-256、authSource 独立；超限文档明确未展开 |

| Elasticsearch | 自有平台 HTTPS / 严格 JSON（Apache-2.0） | 索引、映射、受限 DSL、100 条分页 | 未开放 | Basic、指定索引范围、前 1000 条，单响应 2 MiB |
| OpenSearch | 同上，独立服务器验收 | 同上 | 未开放 | 不以 REST 接口相似推断兼容 |

V0.7.1 的完整引擎矩阵见 `docs/evidence/v071/summary.json`；V0.8.0 重做应用工作流，具体重跑范围见 TEST_REPORT 与 `docs/evidence/v080/summary.json`。历史设备结果不冒充本轮结果；正式 Release 和真机仍是独立门禁。

所有网络连接默认校验证书链与主机身份；SQL Server、PostgreSQL 通过严格 TrustManager，MySQL/MariaDB 通过 StrictTlsPlugin 的平台链校验与共享 SAN 匹配器。支持连接专用公共 CA；默认拒绝 CN-only。V0.7.1 新增显式服务器指纹模式，要求叶证书 SHA-256 相同且有效，以此替代链/SAN；没有信任全部或明文回退。MySQL/MariaDB/PostgreSQL 连接预算 15 秒，PostgreSQL 总登录预算 30 秒，socket 15 秒、工作台语句 10 秒。

服务端固定镜像：

- MongoDB 8.0.32：`mongo@sha256:d0d926f94df099bff534b7ee5b5986458131a22489dfff8664509af0c1e2ca9c`

- Redis 8.2.3 Alpine：`redis@sha256:08ad0b1d280850169a790dba1393ff7a90aef951fc19632cf4d3ce4f78e679ba`
- Valkey 8.1.5 Alpine：`valkey/valkey@sha256:918228e4ff7da6b3a4213cb18067f6e09d9f0503d0a08868699ba227cff71861`
- SQL Server 2022 CU16 Developer：`mcr.microsoft.com/mssql/server@sha256:45a1a9d13ca5574cf8e0fe4ae73ab77248b66d9c3132ac9658fb6c16dd72a8af`
- MySQL 8.4.6：`mysql@sha256:869218921e61d6c3c89820955d63cca42971f0e3e6c1e2792247bbd944ebc6e9`
- MariaDB 11.8.9：`mariadb@sha256:6422478cb8e159f080fb1d8ccf65101e26fe51385787fde7d16c3b165a331f15`
- PostgreSQL 18.3：`postgres@sha256:7e32e9833a6fb1c92c32552794cb6ed569d51b445a54907d35fc112ef39684db`

JVM 使用 Temurin 21.0.8+9；设备是 Android 8/API26 与 Android 11/API30 AOSP x86_64 软件模拟器。真机、arm64、云认证、其它服务器版本尚无验证。Release/R8 仍失败，不将 Debug 验收冒充混淆发行兼容。

其它引擎见 DATABASE_SCOPE，全部仍为规划项目。下一批优先 ClickHouse；Oracle、Db2、国产及兼容协议数据库逐项接入，不能将协议相似推断为已支持。

MongoDB 复用共享 TrustManager；默认启用驱动 endpoint identification，显式指纹模式在 TrustManager 校验具体证书后替代域名匹配；保留证书失败分类，不解析异常文本。登录预算 30 秒，后续读取驱动超时 10 秒、每页截止 12 秒，关闭实际 TLS socket；不启用自动重试、SRV 或自动副本集发现。仅验证表列版本，不推断 DocumentDB 或其它兼容服务。

搜索服务测试固定 Elasticsearch 8.19.5（官方 image amd64 digest `7a159fec8fc8a7b15ae74dcc01f418b39147a93ae097236774fec0b23e40364a`）及 OpenSearch 2.19.4（官方 image amd64 digest `16effbdfb018599a0f5cf5102e15b7ecce1aacbc9959a2203ff4769ff23d375f`）。这不是最新版本声明。APK 不打包这两个服务器或桌面 Java 客户端，只使用 Android 平台 HTTPS 和有界 JSON 实现；两个服务分别验收。V0.5.0/V0.6.0 历史实连证据保留在不可变归档；本轮扫描及工作台回归的实际执行范围以 TEST_REPORT 为准。

V0.7.1 增加九种网络引擎的服务器发现入口；SQLite 继续使用文件选择器。引擎扫描语义、权限与上限见 WORKSPACE。实测结果以本轮 TEST_REPORT 为准。

V0.8.1 没有变更驱动或协议；SQL 元数据导航与表收藏实际测试范围见本轮 TEST_REPORT。历史跨引擎结果不计为新界面的全部实连验收。

V0.9.0 SQL page 接口增加列窗口，所有 SQL 引擎复用相同的 64 列范围校验；SQL Server / SQLite 本轮实测最后一组字段，其它引擎以本轮测试报告为准，不沿用历史结果冒充新功能实测。AI 协议独立于数据库驱动，见 AI_ASSISTANT。

V0.9.1 SQL Server 源码适配更新到 datasail.2：修复 DDC 调用 Android 缺少的 java.sql / java.time 转换方法，避免含日期字段的表查看失败并断连。用真实 SQL Server 合成日期表验证；未变更 TLS 或写入策略。AI 设置导航和已加载字段属性展示同步完善。其它网络引擎未重跑；当前设备范围与实际结果见 TEST_REPORT。
