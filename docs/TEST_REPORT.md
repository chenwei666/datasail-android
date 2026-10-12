# V0.2.0 实际测试报告

日期：2026-10-12；versionName 0.2.0 / versionCode 2；包名 com.example.datasail。代码提交 SHA、APK SHA-256 和签名指纹由版本 manifest 记录。产物是本地 Debug，不是正式 Release。

## 实际命令与结果

| 命令 | 结果与范围 |
|---|---|
| `./gradlew :core:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain` | 退出 0；25 个 JVM 单元测试通过，lint 无错误，真实 Debug / instrumentation APK 已生成 |
| `bash scripts/test-sqlserver-local.sh` | 退出 0；12 个 SQL Server JVM 集成测试通过，无跳过 |
| `DATASAIL_ANDROID_TESTS=1 bash scripts/test-sqlserver-local.sh` | 退出 0；重跑 12 个 JVM 测试，再在 Android 11 执行 1 个实连仪器测试，通过 |
| `bash scripts/test-mysql-local.sh` | 退出 0；6 个真实 MySQL JVM 测试及独立 JVM 的 1 个不受信 CA 反例通过 |
| `bash scripts/test-android-upgrade.sh --reset-synthetic-emulator` | 从 V0.1.0（code 1）用 `adb install -r` 覆盖到 V0.2.0（code 2），私有文件标记保留；3 个 UI/类加载/配置测试通过 |
| `./gradlew :app:assembleRelease --console=plain` | 退出 1；R8 缺失平台与可选 API，保留完整日志及建议规则清单；没有采用批量忽略，没有 Release APK |
| `git diff --check`、脚本 bash/Python 语法检查 | 通过 |

证据在 `docs/evidence/v020/`；归档另外复制 core 的真实 JUnit XML 和 lint XML。早期设备测试因两个安装/测试流程重叠而中断，已改为串行并重新验证；未将中断结果当作通过。默认 `connectedDebugAndroidTest` 不连接数据库，显式设备 fixture 需单独运行。

## 环境

- JVM：Linux x86_64 / Temurin 21.0.8+9。Gradle 8.13、AGP 8.13.0、Kotlin 2.2.10、Compose BOM 2025.08.01；compile/target SDK36、minSdk26、build-tools36.0.0。
- Android：API30 / x86_64 / `Android/sdk_phone_x86_64/generic_x86_64:11/RSR1.210722.013.A2/10067904:userdebug/test-keys`。软件 TCG，无 KVM，不是物理设备。
- SQL Server 2022 CU16 Developer 与 MySQL 8.4.6 官方镜像固定 digest，详见 DRIVER_MATRIX。只运行本机 loopback 容器及合成账号，临时 CA/密码/容器结束后清理。
- Debug 证书 SHA-256：`1c0f10a1cc14cc44b1eefc18a78de23b34ce30761b70dae039bea7f302cc78db`，与 V0.1.0 相同。

## 覆盖与边界

单元测试覆盖语法门禁、参数/标识符处理、精确类型/范围/NULL、SAN 匹配、错误脱敏、凭据清理、有界执行、取消、迟到回调和不可编辑对象策略。

SQL Server JVM 覆盖元数据/分页/文本预算不跳行、Unicode/前导零/小数/时间类型、增改删、参数化、旧确认失效、并发冲突、大小写/尾空格精确比较、触发器变更拒绝、异常匹配数回滚、未知提交结果不重试、错误证书/账号、超时/取消。异常计数和丢失提交确认通过真实 JDBC 包装故障注入，不能当作实际网络断开的证据。

Android 实连覆盖链验证、错误 CA、错误主机名、表读取、单行增改删、并发冲突和重复确认拒绝。最初由于微软证书 CN 解析依赖 `javax.naming.ldap.LdapName` 而失败；通过严格 TrustManager 扩展修复，仍验证链与 SAN，没有 trust-all。另修正 SQL Server 登录错误码分类及 datetimeoffset 读取。保留失败原因以供后续 Release 适配。

升级验证来自先安装旧 APK 再覆盖安装，保留 app-private 合成标记；不是卸载重装冒充升级。V0.1.0 没有持久连接配置，不声称验证了复杂迁移或降级。V0.1.0 ZIP SHA-256 仍为 `37f3b665a7774c18bb5f46919421652a813a66e3b766f0e7f32c1a7e818966e7`。

## 未通过 / 未执行

Release/R8 未通过。API26、Android 11 真机/arm64、MySQL Android 实连、MariaDB 服务器、硬断网、服务端取消后最终状态、完整生命周期/键盘/TalkBack/性能测试均未执行。SQL Server Android 超时/取消探针尚未单独验收，不能沿用 JVM 结论。

GitHub Actions 未触发，未推送或公开发布。云环境启动说明已保存草稿，未发布环境，也未验证全新云任务恢复本地提交。下一步应解决 Release 驱动适配门禁并在真机验证同一套安全读写流程。
