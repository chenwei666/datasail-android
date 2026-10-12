# V0.1.0 测试报告

执行日期：2026-10-12。版本 0.1.0，versionCode 1。Linux x86_64 云机器，5 个 CPU、约 33 GiB 内存、工作区约 30 GiB 可用磁盘；无 KVM、无 ADB 设备。报告描述本次真实执行，GitHub 远端 CI 尚未运行。

## 命令、退出状态与证据

以下在仓库根目录执行，先 `export DATASAIL_TOOLS_DIR=/workspace/toolchains; source scripts/env.sh`。

| 实际命令 | 退出状态 | 结果 |
|---|---|---|
| `bash scripts/setup-cloud.sh` | 0 | 安装器重复执行成功，SDK/JDK/Gradle 可用 |
| `./gradlew :core:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain` | 0 | 15 项 JVM 单元测试通过；Lint 0 问题；两个真实 APK 生成 |
| `./gradlew :app:lintDebug --rerun-tasks --console=plain` | 0 | 修正 manifest 后清除旧增量结果，完整重跑通过 |
| `bash scripts/test-mysql-local.sh` | 0 | MySQL 8.4.6 实连 6 项 + 独立 JVM 不受信 CA 1 项，全部通过 |
| `./gradlew :app:assembleRelease --console=plain` | 1 | R8 缺失 Android 平台类/可选驱动依赖，Release 阻塞；未生成 Release APK |
| `apksigner verify --verbose --print-certs app/build/outputs/apk/debug/app-debug.apk` | 0 | Android Debug 签名验证通过（v2） |
| `aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk` | 0 | 包名 com.example.datasail，0.1.0/1，minSdk26，targetSdk36，应用名数舟 |
| `adb devices` | 0 | 列表为空；不能运行安装或 connected tests |
| `docker pull mcr.microsoft.com/mssql/server:2022-CU16-ubuntu-22.04` | 1 | 配置下载到 centralus.data.mcr.microsoft.com 时被 HTTP403 拒绝 |
| `bash -n scripts/env.sh scripts/setup-cloud.sh scripts/test-mysql-local.sh` | 0 | Shell 语法检查通过 |
| `python3 -m py_compile scripts/archive-version.py` | 0 | 归档脚本语法检查通过；实际归档结果由 versions 中 manifest/SHA256SUMS 记录 |

单元测试细分：ProbeRunnerTest 7 项、SafetyTest 8 项；失败 0、错误 0、跳过 0。实连 DatabaseIntegrationTest 6 项、UntrustedCertificateTest 1 项；失败 0、错误 0、跳过 0。Gradle 的 `NO-SOURCE` / 配置任务 `SKIPPED` 不计为测试通过。

## 经过失败验证后修正的内容

- 云工具最初尝试写只读用户目录；Android/Gradle 缓存迁移到工作区，另创建 ADB 必需目录。Java 使用平台代理与受信任根，没有关闭 TLS 校验。
- 初始测试编译错误（SSLHandshakeException 构造参数）修复后真正执行了测试，没有删断言。
- MySQL TLS fixture 的文件读取权限与就绪探测修复；账号、CA 与私钥均为一次性合成材料，结束删除。
- MySQL SLEEP 超时可以返回 1，探针在受限时间窗口内验证中断标记；正常返回 0 仍失败，未改为一概通过。
- Connector/J 主机名校验异常丢失 SSLException cause；根据该锁定版本的固定错误标记分类，并新增单元回归。
- 驱动原始日志默认可能输出账号/端点信息，已在初始化前关闭原始消息，新增回归；仅应用受控错误字段可用于诊断。
- MariaDB 默认 TLS 路径审查后改为严格 TrustManager 插件，不延后证书链验证；正确证书成功、错误主机名与不受信 CA 均拒绝。
- 取消完成竞态增加互斥保护与迟到取消回归；不会把成功终态覆盖成取消中。
- Lint 要求补齐旧版/新版备份规则与图标。仅对固定版本更新提示、指定第三方 JAR 未使用的 Trust-All 实现使用有依据的例外，见 lint.xml / THIRD_PARTY.md。

## 产物

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。
- 仪器测试 APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`（构建通过，测试未执行）。
- 完整交付：`versions/V0.1.0/`、`versions/V0.1.0.zip`。
- 源码：版本目录的 `source/`，来自 manifest.json 记录的干净本地 Git 提交。
- 签名证书 SHA-256：`1c0f10a1cc14cc44b1eefc18a78de23b34ce30761b70dae039bea7f302cc78db`，仅本次本地 Debug 身份。
- Debug APK SHA-256：`293db4cf1ad977c19f7e0e64c3bb3619571e2be8da82f97639ac1a4937f23597`。
- 所有归档文件实际哈希位于版本目录 `SHA256SUMS`；完整 ZIP 的哈希另存旁边 `.zip.sha256`。

安装、Android 仪器测试、Android 数据库实连、真机验收、覆盖升级、性能和 Release 验收均**未执行或未通过**，不能写“全部通过”。SQL Server 没有连接证据；MySQL 成功只限本报告记录的 JVM/服务端/认证/TLS 条件。详细分项见 DRIVER_MATRIX.md。

工具链实际版本：Temurin 21.0.8+9、AGP8.13.0、Gradle8.13、Kotlin/Compose compiler2.2.10、Compose BOM2025.08.01、SDK36 r2、Build Tools36.0.0、Command-line Tools19.0、Platform Tools37.0.1。Gradle `--version` 自身展示的内嵌 Kotlin2.0.21 不是项目 Kotlin 插件版本。
