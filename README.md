# 数舟 · DataSail

中文原生 Android 数据库客户端，当前 **V0.12.0 / versionCode 18，多数据库工作台**。

保留 SQL Server 的浏览、查询和安全单行编辑，新增 **MySQL、MariaDB、PostgreSQL 只读工作台**，以及 **SQLite 文件副本导入与查询**。现支持 **Redis / Valkey 原生 TLS 键值工作台**（六类键、游标、TTL、有界预览）。新增 **MongoDB 集合与文档只读工作台**：认证数据库、受限 JSON 筛选、游标分页、保留 BSON 类型的 Extended JSON。新增 **Elasticsearch / OpenSearch 搜索工作台**：索引范围、映射查看、受限 DSL、保留 JSON 数值原文和有界分页。首页提供连接卡片、搜索、按引擎分步填写、默认端口、字段校验及证书设置；连接后进入工作台。支持 SELECT 收藏与当前页 CSV 导出；支持可选的 Android Keystore 加密密码保存、单元格表格编辑、SQL Tab 补全与持久化设置。

支持 [AI 助手](docs/AI_ASSISTANT.md)：三种文本协议、国内外服务预设、自定义地址、上游模型列表、加密密钥、发送预览和 SQL 草稿。表格增加全屏、列宽、换行、列显隐和整行详情；宽表可按 64 列分组读取后续字段。保留表收藏、历史连接一键进入工作台。V0.9.1 修复 SQL Server 查看含日期字段的表时断连的问题，并将 AI 配置统一收纳到「设置 → AI 服务与模型」，助手专注问答；字段属性支持搜索、仅看主键和复制字段名。

V0.10.0 重整为固定数据 / SQL / 结构页签，搜索选择数据库，表格双指缩放；SQL Server 日期字段可精确编辑，生产标记连接可显式开启本次编辑。SQL 子句即时提示与 Tab 补全，实时 AI 建议需单独确认发送范围，建议不自动执行。

V0.11.0 增加 DBeaver 风格的列头筛选和排序，查询覆盖整张表后再分页。AI“操作当前表”可自然语言查询，并为 SQL Server 单条增删改生成确认预览；[操作说明](docs/WORKSPACE.md)与[AI 能力边界](docs/AI_ASSISTANT.md)列出具体支持范围。

V0.12.0 修复错误后表格清空、筛选回填和无符号大整数筛选，完善全屏维护；增加单元格快捷筛选、追加排序、进程内工作视图恢复与查询历史。旋转保留会话，后台仍断开并提供显式重连。

支持 **先连接服务器并扫描，再选择数据库连接**。不需要预先知道目标库名；列表支持筛选，扫描失败可手动填写目标。PostgreSQL 需可访问的入口库，Redis/Valkey 仅列出有键的逻辑库，搜索引擎列出索引。

产品目标覆盖主流关系型、文档、键值、搜索、分析、时序与图数据库。当前十种引擎不等于已经覆盖全部；尚未实现的引擎见 [接入范围](docs/DATABASE_SCOPE.md)，应用设置页也明确标注状态。AI 接口支持范围见专门说明；跨引擎写入暂未开放。

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

APK：`app/build/outputs/apk/debug/app-debug.apk`。版本交付：`versions/V0.12.0/` 和 `versions/V0.12.0.zip`，含独立源码、APK、文档、manifest 和逐文件校验。V0.1.0 / V0.2.0 / V0.2.1 / V0.2.2 / V0.3.0 / V0.4.0 / V0.5.0 / V0.6.0 / V0.7.0 / V0.7.1 / V0.7.2 / V0.8.0 / V0.8.1 / V0.9.0 / V0.9.1 / V0.10.0 / V0.11.0 归档保留，不覆盖。干净本地提交后运行 `python3 scripts/archive-version.py` 生成归档；脚本拒绝覆盖已存在版本，不推送或发布。

阅读 [工作台使用说明](docs/WORKSPACE.md)、[AGENTS](AGENTS.md) 和 [架构](docs/ARCHITECTURE.md)。自有代码 Apache-2.0；依赖来源与许可见 [THIRD_PARTY](docs/THIRD_PARTY.md)。

自签名证书或 IP 地址校验失败：连接页展开「证书设置」，在授权访问后点击「获取并核对服务器证书」，向管理员独立核对 SHA-256 后确认，再手动扫描/连接。适用于全部已接入网络引擎；服务端仍须支持 TLS。详见 [证书连接说明](docs/CERTIFICATE_TRUST.md)。
