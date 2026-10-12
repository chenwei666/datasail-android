# 可执行测试与未执行验收

## 本机 JVM / 构建

激活 `scripts/env.sh` 后执行：

```bash
./gradlew :core:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
bash scripts/test-mysql-local.sh
```

单元测试包含参数注入拒绝、TLS 固定配置、内存凭据清理、脱敏分类、原始驱动日志禁用、精确类型、资源关闭、错误证书、取消联动、忙碌拒绝、关闭失败和迟到取消不覆盖结果。动态代理仅验证资源/并发契约，不是实连。

MySQL 脚本使用官方 8.4.6 固定 digest 容器，不使用业务账号；合成数据全部是常量表达式。首次检查失败曾发现测试证书文件对容器 UID 不可读、驱动主机名异常丢失 cause，以及 MySQL SLEEP 超时返回 1 的行为，均修正并复核。正向测试单独信任临时 CA；负向 CA 测试在不同测试 JVM 使用发行 JDK CA，不信任该 CA。不输出或归档临时密码/私钥。

默认测试不访问网络数据库。`:core:integrationTest` 与 `:core:untrustedTlsTest` 是显式实连任务，没有配置会失败而不是跳过。配置仅由隔离脚本生成，运行完成删除。

## SQL Server 隔离验证计划（本次未执行）

1. 在环境设置允许 `centralus.data.mcr.microsoft.com` 并保存，重试 `docker pull mcr.microsoft.com/mssql/server:2022-CU16-ubuntu-22.04`；记录拉取的真实 digest，不能把标签冒充固定镜像证据。
2. 确认 SQL Server Developer 测试许可后，在本机隔离容器中配置 SQL 账号认证、最小权限合成账号和固定端口，只映射 127.0.0.1。证书 SAN 使用 localhost，由临时测试 CA 签发，`mssql.conf` 指向该证书和私钥；不使用 `trustServerCertificate=true`。
3. 仿照 MySQL 临时配置生成 `kind=SQL_SERVER`、`host=localhost`、port、合成库和账号，敏感值只存受限临时文件；导入测试 CA 到临时 JVM truststore，设置 `DATASAIL_TEST_CONFIG`、`DATASAIL_TEST_TRUSTSTORE`。
4. 执行 `./gradlew :core:integrationTest :core:untrustedTlsTest`，正向/错误证书与服务器加密查询分开核验。还需核实实际 SQL Server 产品版本、权限错误、取消后的服务端请求状态。
5. 清理容器及所有合成凭据。然后在 Android 11 重复同样验收，不能把 JVM 结果带入 Android 列。

以上是待实施方案，未宣称 SQL Server 容器、证书配置或 Android 驱动成功。

## Android 11 / 真机验收（本次未执行）

在有 KVM 的 Linux CI 或本地 Android Studio 创建 API30 x86_64 模拟器，或连接用户授权 Android 11 真机。当前 UI 只信任平台证书，尚无私有 CA 导入；使用具有匹配 DNS/SAN 且受平台信任证书的授权测试端点，或在隔离可管理模拟器中配置测试信任链，不能关闭验证。

```bash
adb devices
./gradlew :app:connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.datasail/.MainActivity
```

两个仪器测试分别验证诚实中文导航/禁用入口、Android JDBC 入口类加载。它们不代表数据库连接已通过。手动填写测试目标及密码、确认授权后分别运行三个探针；记录 Android build/ABI、服务器版本、认证、TLS/证书条件、每个结果和资源清理。还需测试前后台、旋转、进程重建、网络中断、键盘和 TalkBack；没有连接不得显示成功。

SQL Server 先验收，再对 MySQL 验收。MySQL 主机名错误、CA 不可信、过期证书、账号错误分别验证。只有取消请求返回不代表服务端取消，需从隔离服务端核对。

## Release 与升级

`./gradlew :app:assembleRelease` 是真实 R8 门禁，目前会失败，错误清单已保存。先修复 Android API/驱动适配，再在 Release 真机重跑，不能批量 `-dontwarn`。Release 未配置正式签名，普通开发不读取正式私钥。

这是首个测试版本，没有上一版升级基线，覆盖升级未执行；本地 Debug 密钥也不是跨云任务的长期签名承诺。
