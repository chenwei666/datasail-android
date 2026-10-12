# 数舟 · DataSail

中文原生 Android 数据库客户端，当前 **V0.3.0 / versionCode 5，多数据库实验性工作台**。

保留 SQL Server 的浏览、查询和安全单行编辑，新增 **MySQL、MariaDB、PostgreSQL 只读工作台**，以及 **SQLite 文件副本导入与查询**。统一连接保存/复制/删除、CA 导入、对象/字段浏览、主键分页和受限 SELECT；密码不持久化。

产品目标覆盖主流关系型、文档、键值、搜索、分析、时序与图数据库。新增四种引擎不等于已经覆盖全部；尚未实现的引擎见 [接入范围](docs/DATABASE_SCOPE.md)，应用设置页也明确标注状态。AI、收藏、跨引擎写入暂未开放。

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
```

本云机器先设置 `export DATASAIL_TOOLS_DIR=/workspace/toolchains`。数据库测试脚本仅启动本机隔离容器、生成临时 CA 和合成凭据，退出后清理。SQL Server 使用 Developer 测试许可；不接入业务库。完整步骤见 [TESTING](docs/TESTING.md)。

APK：`app/build/outputs/apk/debug/app-debug.apk`。版本交付：`versions/V0.3.0/` 和 `versions/V0.3.0.zip`，含独立源码、APK、文档、manifest 和逐文件校验。V0.1.0 / V0.2.0 / V0.2.1 / V0.2.2 归档保留，不覆盖。干净本地提交后运行 `python3 scripts/archive-version.py` 生成归档；脚本拒绝覆盖已存在版本，不推送或发布。

阅读 [工作台使用说明](docs/WORKSPACE.md)、[AGENTS](AGENTS.md) 和 [架构](docs/ARCHITECTURE.md)。自有代码 Apache-2.0；依赖来源与许可见 [THIRD_PARTY](docs/THIRD_PARTY.md)。
