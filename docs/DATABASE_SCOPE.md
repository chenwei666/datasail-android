# 通用数据库管理目标与接入标准

根据用户确认，保留现有 SQL Server，产品继续向 DBeaver / DbGate 类通用管理工具发展，覆盖主流关系型、文档、键值、搜索、分析、时序和图数据库。该目标不是“当前已支持所有数据库”的声明。

V0.13.0 已有 SQL Server、MySQL、MariaDB、PostgreSQL、SQLite、Redis、Valkey、MongoDB、Elasticsearch 和 OpenSearch 的独立工作台。关系型表可在主键、完整原行及元数据条件满足时进行单行增删改；SQLite 修改私有副本；Redis/Valkey 仅完整 UTF-8 字符串可写；MongoDB 限有界完整文档；搜索引擎限带版本校验的单文档。不是所有对象和字段都可编辑。服务器版本、Android 与 JVM 的实际验证分别见 DRIVER_MATRIX 和 TEST_REPORT。

| 类别 | 后续接入范围（尚未实现） |
|---|---|
| 关系型 | Oracle Database、IBM Db2、SAP HANA、SAP ASE/Sybase、Firebird、Informix、Teradata、InterBase |
| 兼容协议及国产 | TiDB、OceanBase、CockroachDB、YugabyteDB、Amazon Aurora、Redshift、Greenplum、openGauss、GaussDB、达梦 DM、KingbaseES、PolarDB、TDSQL |
| 文档 | Amazon DocumentDB、CouchDB、Couchbase、Azure Cosmos DB |
| 键值/宽列 | KeyDB、Memcached、Cassandra、ScyllaDB、HBase、DynamoDB |
| 搜索 | Solr |
| 分析/数仓 | ClickHouse、DuckDB、Doris、StarRocks、Trino、Presto、Hive、Snowflake、BigQuery、Databricks SQL、Vertica |
| 时序 | InfluxDB、TimescaleDB、QuestDB、TDengine、IoTDB |
| 图/多模型 | Neo4j、NebulaGraph、ArangoDB、OrientDB |
| 文件 | H2、HSQLDB、Derby、Access |

这是持续扩充的接入清单，不是数据库市场的穷尽列表。设置页可搜索范围，规划项目明确显示“尚不可连接”。兼容 MySQL/PostgreSQL 协议的产品必须单独测试其认证、元数据、方言及事务行为，不能换一个名称就宣称已支持。云平台托管的同一引擎还需要按服务端版本、认证及网络接入方式验收。

每个引擎分别开放：连接/TLS与认证、对象浏览、受限查询、类型保真、分页、取消/超时、编辑、导出。连接成功不等于其他能力可用。SQL 引擎使用 SqlWorkspaceSession；文档、键值、搜索实现各自的工作台与能力接口，不强行模拟成可编辑 SQL 表。APK 内直接使用固定版本的驱动或协议实现，不运行时下载任意驱动，不要求强制代理。

接入门槛：固定驱动来源和许可；验证严格 TLS 正负例；隔离合成库 JVM 与 API26/API30 实连；有界结果、取消和错误分类；新增编辑必须独立实现参数化、定位键、并发检查和事务确认；混淆 Release 与真机证据分开记录。默认使用最小权限账号，语法过滤和事务只读不能代替服务器授权，也不能约束所有外部副作用。

下一批最小闭环：ClickHouse 表与查询；随后独立接入企业及国产驱动。每批都产出实际 APK 与验证矩阵，不用目录数量代替能力完成度。

搜索引擎使用 SearchSession：索引、映射、受限条件与前 1000 条分页，保留 JSON 数值原文。新增单文档创建、版本校验修改和删除；暂无聚合、脚本、PIT/scroll、批量写入、导出或云 IAM。
