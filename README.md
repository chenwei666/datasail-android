# 数舟 · DataSail

中文原生 Android 数据库客户端，当前 **V0.7.0 / versionCode 9，多数据库实验性工作台**。

保留 SQL Server 的浏览、查询和安全单行编辑，新增 **MySQL、MariaDB、PostgreSQL 只读工作台**，以及 **SQLite 文件副本导入与查询**。现支持 **Redis / Valkey 原生 TLS 键值工作台**（六类键、游标、TTL、有界预览）。新增 **MongoDB 集合与文档只读工作台**：认证数据库、受限 JSON 筛选、游标分页、保留 BSON 类型的 Extended JSON。新增 **Elasticsearch / OpenSearch 搜索工作台**：索引范围、映射查看、受限 DSL、保留 JSON 数值原文和有界分页。首页提供连接卡片、搜索、按引擎分步填写、默认端口、字段校验及证书设置；连接后进入工作台。支持 SELECT 收藏与当前页 CSV 导出；密码不持久化。

本轮新增 **先连接服务器并扫描，再选择数据库连接**。不需要预先知道目标库名；列表支持筛选，扫描失败可手动填写目标。PostgreSQL 需可访问的入口库，Redis/Valkey 仅列出有键的逻辑库，搜索引擎列出索引。

产品目标覆盖主流关系型、文档、键值、搜索、分析、时序与图数据库。当前十种引擎不等于已经覆盖全部；尚未实现的引擎见 [接入范围](docs/DATABASE_SCOPE.md)，应用设置页也明确标注状态。AI、跨引擎写入暂未开放。

`com.example.datasail` 是开发占位包名。Debug APK 可用于授权测试；Release/R8 仍因驱动缺失 Android API 而失败，不能作为生产发行版。实际验证范围见 [测试报告](docs/TEST_REPORT.md)、[驱动矩阵](docs/DRIVER_MATRIX.md)、[限制](docs/KNOWN_ISSUES.md)。

```bash
bash scripts/setup-cloud.sh
source scripts/env.sh
./gradlew :sqlserver-android:check :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
bash scripts/test-sqlserver-local.sh
bash scripts/test-mysql-local.sh
bash scripts/test-jdbc-workspace-local.sh MYSQL
bash scripts/test-jdbc-workspace-local.sh MARIADB
bash scripts/test-jdbc-workspace-local.sh POSTGRESQL
bash scripts/test-key-value-local.sh REDIS
bash scripts/test-key-value-local.sh VALKEY
bash scripts/test-mongo-local.sh
```

本云机器先设置 `export DATASAIL_TOOLS_DIR=/workspace/toolchains`。数据库测试脚本仅启动本机隔离容器、生成临时 CA 和合成凭据，退出后清理。SQL Server 使用 Developer 测试许可；不接入业务库。完整步骤见 [TESTING](docs/TESTING.md)。

APK：`app/build/outputs/apk/debug/app-debug.apk`。版本交付：`versions/V0.7.0/` 和 `versions/V0.7.0.zip`，含独立源码、APK、文档、manifest 和逐文件校验。V0.1.0 / V0.2.0 / V0.2.1 / V0.2.2 / V0.3.0 / V0.4.0 / V0.5.0 / V0.6.0 归档保留，不覆盖。干净本地提交后运行 `python3 scripts/archive-version.py` 生成归档；脚本拒绝覆盖已存在版本，不推送或发布。

阅读 [工作台使用说明](docs/WORKSPACE.md)、[AGENTS](AGENTS.md) 和 [架构](docs/ARCHITECTURE.md)。自有代码 Apache-2.0；依赖来源与许可见 [THIRD_PARTY](docs/THIRD_PARTY.md)。
