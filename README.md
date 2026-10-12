# 数舟 · DataSail

中文原生 Android 数据库客户端，当前 **V0.2.1 / versionCode 3，实验性 SQL Server 工作台**。

本版修复切库部分失败时的会话目标显示及 Android 8 SQL Server TLS 连接故障，补 Android 超时/取消及配置覆盖升级验证。Release 适配证据见 [驱动审查](docs/DRIVER_RELEASE_PLAN.md)。

可保存/复制/删除连接元数据、导入连接专用 CA、浏览数据库/Schema/表/视图和字段、按主键分页、运行受限单条 SELECT，以及预览并逐次确认单行新增/修改/删除。密码不持久化，默认只读，生产连接禁止图形写入。AI、收藏及其它数据库工作台尚未开放；MySQL 保留固定探针。

`com.example.datasail` 是开发占位包名。Debug APK 可用于授权测试；Release/R8 仍因驱动缺失 Android API 而失败，不能作为生产发行版。实际验证范围见 [测试报告](docs/TEST_REPORT.md)、[驱动矩阵](docs/DRIVER_MATRIX.md)、[限制](docs/KNOWN_ISSUES.md)。

```bash
bash scripts/setup-cloud.sh
source scripts/env.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
bash scripts/test-sqlserver-local.sh
bash scripts/test-mysql-local.sh
```

本云机器先设置 `export DATASAIL_TOOLS_DIR=/workspace/toolchains`。数据库测试脚本仅启动本机隔离容器、生成临时 CA 和合成凭据，退出后清理。SQL Server 使用 Developer 测试许可；不接入业务库。完整步骤见 [TESTING](docs/TESTING.md)。

APK：`app/build/outputs/apk/debug/app-debug.apk`。版本交付：`versions/V0.2.1/` 和 `versions/V0.2.1.zip`，含独立源码、APK、文档、manifest 和逐文件校验。V0.1.0 / V0.2.0 归档保留，不覆盖。干净本地提交后运行 `python3 scripts/archive-version.py` 生成归档；脚本拒绝覆盖已存在版本，不推送或发布。

阅读 [工作台使用说明](docs/WORKSPACE.md)、[AGENTS](AGENTS.md) 和 [架构](docs/ARCHITECTURE.md)。自有代码 Apache-2.0；依赖来源与许可见 [THIRD_PARTY](docs/THIRD_PARTY.md)。
