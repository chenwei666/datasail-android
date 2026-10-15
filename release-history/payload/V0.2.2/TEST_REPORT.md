# V0.2.2 实际测试报告

2026-10-12；versionName 0.2.2 / versionCode 4；包名 com.example.datasail，minSdk26。Debug 开发交付，未推送/发布。源码提交、APK、签名及派生驱动校验由版本 manifest 记录。

## 最终命令与结果

| 命令 | 实际结果 |
|---|---|
| `./gradlew :sqlserver-android:check :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain` | 退出 0；11 个驱动、26 个 core、3 个 app 单元测试通过；产物校验、lint、Debug / instrumentation APK 构建通过 |
| `ANDROID_SERIAL=emulator-5556 DATASAIL_ANDROID_TESTS=1 bash scripts/test-sqlserver-local.sh` | 退出 0；15 个 SQL Server JVM 集成测试及 API26 的 2 个 Android 实连测试通过 |
| `ANDROID_SERIAL=emulator-5554 DATASAIL_ANDROID_TESTS=1 bash scripts/test-sqlserver-local.sh` | 退出 0；15 个 SQL Server JVM 集成测试及 API30 的 2 个 Android 实连测试通过 |
| `bash scripts/test-mysql-local.sh` | 退出 0；6 个 MySQL JVM 测试 + 独立 JVM 的 1 个不受信 CA 反例通过 |
| `ANDROID_SERIAL=… bash scripts/test-android-upgrade.sh --reset-synthetic-emulator`（API26、API30 各一次） | 每次退出 0；V0.2.1 code3 覆盖安装 V0.2.2 code4，3 个 smoke + 1 个配置保留测试通过 |
| `./gradlew :app:assembleRelease --console=plain` | 退出 1；R8 缺失类从 147 减至 132，仍无 Release APK |
| `./gradlew :core:driverCompatibilityReport --console=plain` | 退出 0；固定 V0.2.1 基线：原始 SQL Server 143、派生 SQL Server 136、MariaDB 72 个方法；只定位引用，不证明可达性 |
| `git diff --check`、bash/Python 语法检查 | 通过 |

合计 62 个 JVM 测试（11 + 26 + 3 + 15 + 6 + 1）通过，重复执行的 SQL Server 测试不重复计入；API26/API30 各 6 个仪器测试通过。证据在 `docs/evidence/v022/`；归档包含各模块真实 JUnit XML 和 lint XML。

## 新增验证及重要边界

1. 驱动源码适配：上游源码/二进制、补丁后源码均锁定 SHA-256；只重新编译 5 个类，其余上游条目逐字节对比相等。移除失效的微软 JAR 签名并使用派生版本身份，未声称获得微软签名。另在隔离临时目录手动注入二进制和非修改类篡改，两次均被校验拒绝（不计入 JUnit 数量）。
2. 缓冲参数：空值/-1、8M 十进制字节、百分比、90% 堆上限、极大 K/M/G/T 溢出、无效/非正数参数回归。ART 实际调用最大整数 T 参数并连接数据库，验证不再依赖 JMX。
3. 8 MB 驱动保留缓冲预算：真实 SQL Server 返回 1,000 万字节的合成 Unicode 响应，full buffering 模式拒绝超限。最初测试错误假设 adaptive 累计响应同样受限而失败；结合上游 IOBuffer 的计数重置语义，修正为 full buffering 的真实预算测试，保留拒绝断言。正常应用保持 adaptive，行/列/字段限制独立；不宣传总网络响应或整个应用堆的硬上限。
4. 证书负例：JVM 验证 SAN 匹配成功、CN-only 拒绝、匹配 CN 不能覆盖错误 SAN；PKCS#1 客户端密钥明确异常，不加载可选 PEM 组件。证书测试仅含合成公共证书，不保留私钥。应用的独立严格链/SAN TrustManager 仍保留，两台 Android 的错 CA/错主机名拒绝继续实连回归。
5. SQLXML：JVM 验证 StAX 显式拒绝、流内容保真和单次消费；两台 Android 验证 createSQLXML → 参数绑定 → SQL Server XML → 读回字符串。未宣称 DOM/SAX 的所有解析器或 mTLS 经设备验收。
6. 原有真实增改删、冲突、回滚、未知提交不重放、切库失败、超时/取消继续通过。取消仍为 UNKNOWN，不证明服务端请求最终消失。
7. 升级通过 `adb install -r`，保留前版 schema=1 合成连接名称、主机、端口、库、账号、生产只读策略。夹具经 app-private 文件注入，不宣称复杂格式迁移或经旧 UI 录入。

首次接入时发现派生 JAR 缺少许可证条目、instrumentation 缺少显式驱动编译依赖，构建门禁均失败；修正后完整构建通过。上表仅列最终有效结果。

## 环境与限制

JVM Linux x86_64 / Temurin21.0.8+9；Gradle8.13、AGP8.13.0、Kotlin2.2.10、SDK36。API26 Android8 与 API30 Android11 AOSP x86_64 软件模拟器，无 KVM；具体指纹见 evidence。SQL Server2022 CU16 Developer、MySQL8.4.6 固定 digest，临时容器、loopback 端口、合成凭据和临时 CA，退出清理。

Debug 签名 SHA-256 沿用 `1c0f10a1cc14cc44b1eefc18a78de23b34ce30761b70dae039bea7f302cc78db`。旧 V0.1.0、V0.2.0、V0.2.1 归档不变。

Release/R8 未通过，混淆 APK、真机/arm64、MySQL Android、硬断网/服务端最终取消状态、完整生命周期与性能未验证。CI 已更新但未触发。下一步处理 SQLServerConnection 的 GSS/集成认证与普通关闭路径，继续减少缺失引用并最终验证混淆 APK。
