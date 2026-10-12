# V0.2.1 实际测试报告

2026-10-12；versionName 0.2.1 / versionCode 3；包名 com.example.datasail，minSdk26。Debug 开发交付，未公开发布；源码提交、APK 校验和与签名由版本 manifest 记录。

## 命令与结果

| 命令 | 实际结果 |
|---|---|
| `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false --console=plain` | 退出 0；26 个 core 单元测试、3 个 app 状态测试通过，lint 与 Debug / instrumentation APK 构建通过 |
| `DATASAIL_ANDROID_TESTS=1 bash scripts/test-sqlserver-local.sh`（分别指定 API26/API30） | 每次退出 0；14 个 SQL Server JVM 集成测试通过，每台设备各 2 个 Android 实连测试通过 |
| `bash scripts/test-mysql-local.sh` | 退出 0；6 个 MySQL JVM 测试和独立 JVM 的 1 个错误 CA 反例通过 |
| `bash scripts/test-android-upgrade.sh --reset-synthetic-emulator`（分别指定 API26/API30） | 每次退出 0；V0.2.0 code2 覆盖安装 V0.2.1 code3，3 个 smoke 测试 + 1 个旧配置保留测试通过 |
| `./gradlew :app:assembleRelease --console=plain` | 退出 1，R8 缺失平台/可选 API；没有 Release APK |
| `./gradlew :core:driverCompatibilityReport --console=plain` | 退出 0；215 个相关方法（SQL Server 143，MariaDB 72），仅诊断，不修改驱动或生成忽略规则 |
| `git diff --check`、bash/Python 脚本语法检查 | 通过 |

合计 50 个 JVM 测试通过（26 + 3 + 14 + 6 + 1），API26 和 API30 上各 6 个仪器测试通过（合计 12 次）。报告按平台分别计数，不用 JVM 成功代替 Android 成功。证据在 `docs/evidence/v021/`；归档另外包含真实 core / app JUnit XML 及 lint XML。

## 本轮新增回归

- API26 修复前两项数据库仪器测试失败，严格 TLS 报 Socket closed；改用 adb reverse 仍失败。应用内注册固定 Conscrypt 2.7.0 engine socket 后，两项通过；Android 11 同样回归。可信连接、错 CA/主机名拒绝、真实读写冲突和超时/取消同时验证；断言 TLS provider 与系统 TrustManagerFactory 分工，没有信任全部证书。修复前证据保存在 api26-before-tls-fix.log。
- 真实 JDBC 包装注入“服务器切库完成、客户端收到异常”，会话立即关闭且不能继续查询。
- 组合操作切库成功后刷新 Schema 失败，SessionReply 仍报告实际新库和只读状态；界面清除旧 Schema、表、结果与预览。
- 成功结果伴随连接关闭时，界面不会保留可写会话。
- Android 上固定 WAITFOR 超时探针按预期结束；取消结果仍为 UNKNOWN；断开检查通过，每次只连接一次，不自动重连。没有据此声称服务端请求已消失。
- 先在已安装 V0.2.0 的 app-private 目录写入其 schema=1 的合成连接配置，再用 `adb install -r` 覆盖；检查名称、主机、端口、库名、账号及生产只读标记保留。配置为测试脚本注入，不宣称是通过旧 UI 录入或复杂迁移；不包含密码或实际业务数据。

原有严格 TLS、错证书/主机名、类型保真、分页、参数化增改删、冲突/回滚/未知提交等继续回归。初期同目录并行构建导致一次中间类/APK读取失败，最终构建和设备验证已串行重跑；后续 Kotlin daemon 增量缓存也曾报错并回退，最终检查显式使用进程内编译并关闭增量缓存，通过后按默认脚本验证。上表只列最终有效结果。

## 环境与限制

JVM Linux x86_64 / Temurin21.0.8+9；Gradle8.13、AGP8.13.0、Kotlin2.2.10、SDK36。Android8 API26 与 Android11 API30 x86_64 软件模拟器，无 KVM；build 指纹在 device-api26.txt / device-api30.txt。SQL Server 2022 CU16 Developer / MySQL8.4.6 固定 digest、loopback 临时容器、合成凭据与临时 CA，退出清理。

签名沿用本地 Debug 证书 `1c0f10a1cc14cc44b1eefc18a78de23b34ce30761b70dae039bea7f302cc78db`。V0.1.0 与 V0.2.0 不可变归档校验保持不变。

APK 签名验证与 zipalign 16 KiB 对齐检查通过；Conscrypt arm64 ELF LOAD 段为 0x4000 对齐。它们是静态检查，不等同 16 KiB/arm64 设备实测。APK 中包含 Conscrypt，不含 ASM driverAudit 类。

Release/R8 未通过；真机/arm64、MySQL Android、硬断网与服务端取消后最终状态、完整生命周期、无障碍和性能未验证。GitHub Actions 未触发，未推送/发布。下一步按 DRIVER_RELEASE_PLAN.md 实施可审计的 Android 驱动源码适配并验证混淆 APK。
