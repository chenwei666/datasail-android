# 数舟 · DataSail

中文原生 Android 数据库客户端，当前 **V0.1.0 / versionCode 1 技术验证版**。

已实现 Compose 中文导航、SQL Server/MySQL 固定只读驱动探针、严格 TLS、类型保真检查、超时/取消与结果未知状态。工作台、AI、收藏尚未实现，入口禁用。`com.example.datasail` 是开发占位包名。

**目前有真实 Debug APK；没有通过 Android 真机实连或 Release/R8 验收。** MySQL 的隔离实连结果来自 JVM，不能当作 Android 兼容认证。参见 [实际兼容矩阵](docs/DRIVER_MATRIX.md)、[测试报告](docs/TEST_REPORT.md) 与 [已知问题](docs/KNOWN_ISSUES.md)。

## 构建

Linux x86_64，需 curl、unzip、tar、Python 3；不要求预装 Gradle。安装器校验下载，锁定工具链，保留 TLS 验证。

```bash
bash scripts/setup-cloud.sh
source scripts/env.sh
./gradlew :core:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

本次 Codex 云机器使用共享工具目录 `/workspace/toolchains`，在上述命令前先 `export DATASAIL_TOOLS_DIR=/workspace/toolchains`；其它机器默认使用仓库忽略的 `.toolchains/`。详见 [云环境说明](docs/CODEX_CLOUD.md)。

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。仪器测试 APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。

## 验证与交付

```bash
# 仅创建本机 Docker 隔离 MySQL，生成合成凭据及测试 CA，结束后自动清理。
bash scripts/test-mysql-local.sh
# 需设备/模拟器；当前机器尚未执行。
./gradlew :app:connectedDebugAndroidTest
# 正式发行前必须解决的门禁；当前有明确 R8 缺失类失败。
./gradlew :app:assembleRelease
# 源码与文档提交到本地干净 Git 提交后，生成不可覆盖的版本归档。
python3 scripts/archive-version.py
```

GitHub Actions 构建/测试 Debug 与仪器测试 APK，并运行隔离 MySQL JVM 测试；手动 workflow_dispatch 额外运行 Release/R8 门禁，不自动签名或公开发布。CI 文件已编写，本次没有触发 GitHub 远端运行。

V0.1.0 完整本地交付位于 `versions/V0.1.0/` 和 `versions/V0.1.0.zip`，含独立 source、真实 APK、文档、测试报告、manifest 与 SHA256SUMS。该目录被 Git 忽略，不作为 Gradle 模块。

请先阅读 [AGENTS.md](AGENTS.md) 和 [架构说明](docs/ARCHITECTURE.md)。自有代码 Apache-2.0，第三方驱动许可与源码在 [THIRD_PARTY.md](docs/THIRD_PARTY.md)。
