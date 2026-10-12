# 可复现验证

先激活 `scripts/env.sh`；本云机器先设置 `DATASAIL_TOOLS_DIR=/workspace/toolchains`。

```bash
./gradlew :sqlserver-android:check :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
bash scripts/test-sqlserver-local.sh
bash scripts/test-mysql-local.sh
```

数据库脚本只建立本机 loopback 临时容器，动态端口、临时 CA、合成密码；退出删除容器及所有秘密文件。SQL Server 2022 CU16 Developer 镜像锁定 digest，接受开发测试许可；账号为仅存在于这个隔离容器的合成 sa，用于建表/故障注入，不作为业务授权示例。应用应使用最小权限业务账号。没有测试配置的显式集成 Gradle 任务会失败，不会跳过或连接默认地址。

JVM SQL Server 验证：证书链与错误主机名、错误密码、固定类型/超时/取消探针、元数据/分页、单行增改删、并发冲突、文本大小写/尾空格精确冲突、旧确认失效、触发器变化拒绝、异常匹配数回滚、丢失提交确认保持 UNKNOWN。后两项通过包装真实 JDBC 连接注入客户端故障，不是声称实际中断网络。

## Android 11

```bash
sdkmanager 'emulator' 'system-images;android-30;default;x86_64'
echo no | avdmanager create avd -n datasail-api30 -k 'system-images;android-30;default;x86_64' -d pixel
emulator -avd datasail-api30 -no-window -no-audio -no-snapshot -accel off -gpu swiftshader_indirect -cores 2 -memory 2048
# 等待 sys.boot_completed=1；无 KVM 软件模拟可能需要数分钟。
adb devices
DATASAIL_ANDROID_TESTS=1 bash scripts/test-sqlserver-local.sh
./gradlew :app:connectedDebugAndroidTest
```

Android 数据库测试显式启用；不由默认 UI 测试顺带连接。脚本仅把合成凭据经 stdin 写入 app-private 文件，公有 CA 在 Android 原生 KeyStore 内建成专用信任库；测试完成清理。`adb reverse` 为 localhost 正向连接及 127.0.0.1 错误主机名反例提供同一临时端口映射，退出移除；避免模拟器宿主路由因 API/版本不同而变化，仍保留完整 TLS/SAN 校验。不要同时运行两组设备测试，Gradle connected 测试结束可能卸载 APK，干扰另一个 instrumentation。

`SqlServerDeviceTest` 的两个测试验证 ART 上可信 TLS、不受信 CA、错误主机名、表读取和真实单行增改删/冲突，以及固定探针超时/取消、关闭和无自动重连。V0.2.2 增加 ART 容量溢出参数检查与真实 SQLXML 往返。`SmokeTest` 验证中文导航、禁用的未实现入口、驱动入口加载及非秘密配置往返。均不等同完整手工操作、真机或性能验收。

## 覆盖升级

仅在可清空的合成模拟器执行（脚本拒绝真机）：

```bash
bash scripts/test-android-upgrade.sh --reset-synthetic-emulator
```

先卸载合成 app 数据，安装不可变 V0.2.1 APK，写入非秘密 app-private 标记及 schema=1 的合成连接配置，再用 `adb install -r` 覆盖到 V0.2.2，核对 versionCode 和标记保留，并运行三个 UI/配置 smoke 测试和一个升级配置测试，检查名称/主机/端口/库/账号/生产只读标记。当前 schema 未变化，不能据此宣称复杂格式迁移已验证。签名必须与本地 V0.2.1 相同，不支持随意更换密钥。脚本不证明可降级。

## Release 与未验证项

`./gradlew :app:assembleRelease` 仍是失败的 R8 门禁。不得批量忽略缺失类。修复后需运行真正的混淆 Release 设备测试；当前没有 Release APK或正式签名。Android 真机/arm64、MySQL Android TLS、前后台/弱网/进程重建/TalkBack/性能等仍需独立验收。

API26 使用同样步骤，将系统镜像改为 `system-images;android-26;default;x86_64`，AVD 名称设为 datasail-api26。多台模拟器时设置 ANDROID_SERIAL，数据库仪器测试和安装/升级过程必须串行。

驱动审查：`./gradlew :core:driverCompatibilityReport`，结果只用于适配评审，不修改驱动或屏蔽错误。

源码适配回归：`:sqlserver-android:check` 包含 11 个 JVM 用例与产物完整性检查；`:core:sqlServerTest` 新增 full buffering 超过 8 MB 的真实拒绝反例。原生应用仍用 adaptive，该预算不代表累计响应字节上限。


V0.3.0 多数据库验收（先构建 Debug 和 AndroidTest APK）：

```bash
bash scripts/test-jdbc-workspace-local.sh MYSQL
bash scripts/test-jdbc-workspace-local.sh MARIADB
bash scripts/test-jdbc-workspace-local.sh POSTGRESQL
# 选定可丢弃模拟器；逐引擎、逐设备执行，勿并行写同一 Gradle 目录。
ANDROID_SERIAL=emulator-5554 DATASAIL_ANDROID_TESTS=1 bash scripts/test-jdbc-workspace-local.sh POSTGRESQL
adb -s emulator-5554 shell am instrument -w -e class com.example.datasail.SqliteDeviceTest com.example.datasail.test/androidx.test.runner.AndroidJUnitRunner
```

每个网络引擎分别创建 TLS 临时容器，测试五项 JVM 工作台用例；开启 Android 模式时再跑两项设备测试。保留每种服务器独立 XML，避免后一个引擎覆盖前一个的证据。MySQL 原探针测试、SQL Server 工作台、升级与 SQLite 测试也要保留。设备 shell instrumentation 命令需检查 `OK (N tests)`，ADB 的退出码本身不代表测试通过。SQLite 两项测试已纳入默认 connected suite；三个网络 fixture 测试类和 UpgradeTest 需显式调用。

PostgreSQL Android 元数据使用参数化 information_schema/pg_catalog 查询，避开 pgjdbc 元数据内的 Android 缺失 closeOnCompletion 方法；普通 PreparedStatement/ResultSet 仍由 use 显式关闭。新增工作台分别断言错误 CA、错误 SAN、类型/分页、只读保护、超时与取消。驱动加载测试不能替代这些实连测试。
