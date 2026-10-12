# 路线图

用户当前目标是主流数据库通用管理工具，并明确保留已有 SQL Server。产品完成标准见 [PRODUCT_ACCEPTANCE](PRODUCT_ACCEPTANCE.md)，范围与接入标准见 [DATABASE_SCOPE](DATABASE_SCOPE.md)；规划项不算当前能力。

| 阶段 | 范围 |
|---|---|
| V0.1.0–V0.2.2 已归档 | 原生工程、SQL Server 工作台、安全单行编辑与驱动源码适配 |
| V0.3.0 已归档 | 统一多数据库会话和连接配置；MySQL/MariaDB/PostgreSQL 只读工作台；SQLite 文件副本浏览；旧配置迁移 |
| V0.4.0 本轮 | Redis/Valkey 只读键值工作台；首页连接体验；查询收藏、当前页 CSV 导出 |
| 下一批 | MongoDB、Elasticsearch/OpenSearch、ClickHouse 各自真实协议和工作台 |
| 后续接入 | Oracle、Db2、国产数据库、兼容协议服务、分析/时序/图/文件数据库；逐项验收 |
| 能力补全 | 各 SQL 方言安全编辑、全量受控导出、对象搜索和 AI 草稿 |
| 持续发行门禁 | 驱动 R8 适配、真机/arm64、稳定性、正式身份/签名、升级回归 |

SQL Server 功能保留并回归。新增数据库优先扩大真实读能力，再按引擎补全写能力；主流覆盖与稳定发行是两条同时推进的工作，不将实验性 Debug 当作正式兼容声明。
