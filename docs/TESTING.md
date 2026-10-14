# 可复现验证

V0.13.0 当前套件：核心与 app 单元、SQL Server 24 项、MySQL/MariaDB/PostgreSQL 各 10 项、Redis/Valkey/MongoDB/Elasticsearch/OpenSearch 各 4 项 JVM 合同；各引擎独立 Android 验证。AI HTTPS 5 项、加密记忆 3 项、SQLite 3 项，以及工作台触摸、筛选排序、宽表和升级测试。实际执行与准确计数见 TEST_REPORT，不把下面历史版本说明当作本轮通过证据。

当前 Android 完整合同：SQL Server 4 项（2 个驱动 + 2 个 UI），JDBC 各 4 项，Redis/Valkey 各 4 项，MongoDB 5 项，搜索引擎各 6 项。新增写入只使用独立临时 writer 权限；reader 拒绝写入的反例保留。搜索的响应丢失测试另用本机故障服务器，验证写请求只到达一次；不冒充真实集群断网。

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

当前先安装不可变 V0.12.2/code20 APK，写入 schema 4 的 SQL Server/MongoDB/OpenSearch 合成连接、收藏、加密密码、AI 配置、表视图与私有文件标记，再 `adb install -r` 覆盖 V0.13.0/code21。测试核对旧连接、生产策略、MongoDB 自定义 authDatabase 与收藏保留，以及旧版保存的搜索引擎索引范围。保持 applicationId 和签名；不代表可降级。

## Release 与未验证项

`./gradlew :app:assembleRelease` 仍是失败的 R8 门禁。不得批量忽略缺失类。修复后需运行真正的混淆 Release 设备测试；当前没有 Release APK或正式签名。Android 真机/arm64、前后台/弱网/进程重建/TalkBack/性能等仍需独立验收。

API26 使用同样步骤，将系统镜像改为 `system-images;android-26;default;x86_64`，AVD 名称设为 datasail-api26。多台模拟器时设置 ANDROID_SERIAL，数据库仪器测试和安装/升级过程必须串行。

驱动审查：`./gradlew :core:driverCompatibilityReport`，结果只用于适配评审，不修改驱动或屏蔽错误。

源码适配回归：`:sqlserver-android:check` 包含 14 个 JVM 用例与产物完整性检查；`:core:sqlServerTest` 新增 full buffering 超过 8 MB 的真实拒绝反例。原生应用仍用 adaptive，该预算不代表累计响应字节上限。


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

## V0.4.0 Redis / Valkey 与移动流程

```bash
bash scripts/test-key-value-local.sh REDIS
bash scripts/test-key-value-local.sh VALKEY
DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=emulator-5556 bash scripts/test-key-value-local.sh REDIS
DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=emulator-5554 bash scripts/test-key-value-local.sh VALKEY
```

两个参数分别启动固定摘要的实际 Redis 8.2.3 / Valkey 8.1.5，TLS-only 端口仅绑定 loopback；合成 CA、哈希 ACL 和随机密码在退出时清理。预置 212 个键、600 字段哈希、二进制键及超限字段；脚本先证明 reader 的 SET/DEL 被 ACL 拒绝。共享 KeyValueContract 在 JVM 与 ART 运行：读取/游标/保真/限额，证书主机名与未知 CA / 错误密码，实际服务器 CLIENT PAUSE 注入的超时及取消。pauser 账号仅有测试用 CLIENT PAUSE 权限，不进入应用连接配置。不要与繁重编译同时执行 TCG 模拟器的定时测试。

默认设备测试新增 ConnectionUiTest、QueryStoreTest、MobileWorkflowTest：引擎筛选与表单、搜索与删除确认、收藏持久化和拒绝覆盖、跨实例并发保存/.bak 恢复、真实 SQLite 连接后自动导航、执行/收藏/载入/导出确认。CSV 字节级编码、公式防护、未加载字段拒绝由 PageCsvTest 单元测试覆盖；系统文件选择器和第三方文件提供者仍需真机验收。

历史 V0.4.0 验收使用 V0.3.0/code5 基线；当前基线见上方“覆盖升级”；仍保持相同包名和签名。显式测试 SQLite、UI、收藏流程应在升级后分别运行。旧 schema 1 的兼容读取保留。

版本归档要求 `docs/evidence/vNNN/summary.json` 中的 version/versionCode/finalApkSha256 与实际 APK 一致；devices.json 中记录各设备已安装 base.apk 的 SHA-256。归档脚本检查这些绑定再生成交付，禁止把旧构建的设备证据附到新 APK。总数按独立套件/引擎去重汇总，诊断失败另存，不加入通过计数。

## V0.5.0 MongoDB

```bash
bash scripts/test-mongo-local.sh
DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=emulator-5556 bash scripts/test-mongo-local.sh
DATASAIL_SKIP_JVM=1 DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=emulator-5554 bash scripts/test-mongo-local.sh
```

脚本启动固定 digest 的 MongoDB 8.0.32，仅 loopback 动态端口，requireTLS、临时 SAN=localhost CA、SCRAM-SHA-256。reader 在 synthetic 库只有 read；controller/root 与 enableTestCommands 仅用于隔离夹具的 failCommand 注入。无连接真实服务或固定密码。退出 `docker rm -fv` 清理本夹具数据卷、app-private 凭据、端口映射与临时文件。

MongoContract 三项分别覆盖 205 文档的 100/100/5 游标分页（第一页后停留 11 秒）、EJSON 类型与过滤、超大文档提示，错误 SAN/CA/密码/authSource 和服务端拒绝写入，以及真实 find 阻塞下客户端截止与已借出 socket 取消。测试控制连接的 30 秒预算仅用于等待服务器释放 20 秒故障点；读取仍为 10 秒驱动/12 秒每页总截止，登录单独为 30 秒预算。MongoUiTest 通过真实连接测试首页认证数据库、集合选择、下一页、修改筛选禁用续页和精确查询。不要在定时 ART 测试同时跑重编译。

构建时排除 MongoDB 两份重复 GraalVM 元数据，正式 R8 单独失败记录，不等同 Android Debug 已通过即可发行。CI 添加此 JVM 夹具，本轮本地执行不代表 GitHub Actions 已运行。

MongoDB 脚本在安装前比较本机构建与设备 APK 的 SHA-256，相同则复用已验证安装；不同才覆盖安装。`DATASAIL_MONGO_UI_ONLY=1` 仅供定位界面问题，完整验收必须去掉此标志；V0.13.0 应得到 5 项通过。相关诊断失败保存在 evidence 的 diagnostics 下，不计入通过总数。

## V0.6.0 Elasticsearch / OpenSearch

```bash
bash scripts/test-search-local.sh ELASTICSEARCH
bash scripts/test-search-local.sh OPENSEARCH
DATASAIL_ANDROID_TESTS=1 DATASAIL_ANDROID_SERIALS='emulator-5556 emulator-5554' bash scripts/test-search-local.sh ELASTICSEARCH
DATASAIL_ANDROID_TESTS=1 DATASAIL_ANDROID_SERIALS='emulator-5556 emulator-5554' bash scripts/test-search-local.sh OPENSEARCH
```

两种参数启动各自固定摘要镜像。单节点仅 loopback 动态 HTTP 端口，HTTP TLS、SAN=localhost、临时 CA 和随机 Basic 密码；reader 仅有 synthetic 的读/监控/映射权限，服务端验证 PUT 文档被拒绝。管理员只用于夹具初始化，不进入手机。OpenSearch 使用镜像自带安全配置 schema，替换默认用户和角色，Python 3.11 crypt/BLOWFISH 生成随机密码的 hash；未启用演示密码或绕过客户端证书检查。`node.store.allow_mmap=false` 是测试服务器设置，不更改 Android 或宿主系统。

SearchContract 前两项为真实服务：205 条 100/100/5 分页、映射、term/range、Long/小数原文/前导零/NULL、超限文档，错误 SAN/CA/密码/无权限。第三项明确为本机对抗 HTTPS fixture：不跟随重定向、超限响应、重复 JSON 键、非法 UTF-8、阻塞超时、取消关闭实际 socket、拒绝部分搜索结果；不将模拟阻塞称为服务端真实慢查询测试。UI 流程通过真实服务测试连接表单、索引选择、翻页、条件改变禁用续页、筛选和映射。另增加纯界面回归：切换到新索引但保留旧映射时，旧映射标题仍绑定原索引，防止误标。每个引擎应有 JVM `3 tests` 与每设备 `OK (5 tests)`；JVM 与 Android 独立记录。

`DATASAIL_SKIP_JVM=1` 仅允许已有 JVM 结果时避免重复；不会产生新的 JVM 通过证据。脚本串行遍历设备，安装前核对 APK SHA。默认 connected 测试排除 SearchDeviceTest/SearchUiTest，需由夹具显式调用。脚本退出清理自己的容器/数据卷、证书、密码、对抗 HTTP 进程、ADB 反向端口和 app-private 测试文件。

V0.6.0 历史覆盖升级基线为 V0.5.0/code7，保留 schema3 SQL Server/MongoDB 自定义 authDatabase 及 SQL 收藏后安装 code8。归档只收集 summary.requiredJvmReports 中本轮明确要求的 JVM 报告及 app/driver 报告；旧引擎历史证据仍在原不可变档案中，不把遗留 build 目录测试报告算作本轮执行。

`DATASAIL_SEARCH_READ_REPEATS=10 bash scripts/test-search-local.sh OPENSEARCH` 可诊断瞬态问题：同一 JVM 读取测试重复十轮完整流程，仍报告 3 个测试，不能把重复断言当作新增独立测试。首次 EOF 失败单独归档；复测不等于根因已修复。服务端日志仅落临时 `/tmp/datasail-search-server-ENGINE.log`，需检查敏感信息后才可摘录到证据。


## V0.7.0 服务器数据库扫描

- `:core:test` 包含扫描取消关闭、过期回调丢弃、凭据释放、重复请求拒绝和列表上限测试；`:app:testDebugUnitTest` 验证无需目标库名即可扫描，但服务器及 PostgreSQL 入口库仍须有效。
- 现有九种网络引擎的真实服务读取合约加入 `DiscoveryContract.verify`，在 JVM 与 Android 执行同一断言：从服务器元数据发现合成库/索引。OpenSearch 单索引 reader 明确验证默认 * 被拒绝、已授权范围成功；不假设两个搜索引擎具有相同目录权限策略。Redis/Valkey 额外在 5 号库写入合成标记，验证只发现 0/5、不虚构空库，并验证缺少 INFO 权限时报权限错误。MongoDB reader 应只发现获授权的 synthetic。
- `DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=emulator-5556 scripts/test-jdbc-workspace-local.sh MYSQL`（或 MARIADB、POSTGRESQL）同时执行 JdbcWorkspaceDeviceTest 和 DiscoveryUiTest，期望 `OK (4 tests)`。界面从目标库名留空开始，扫描、筛选、选中、连接；另一用例验证修改主机使列表、授权、密码失效。默认普通 connected tests 排除依赖服务器的 DiscoveryUiTest。
- 当前脚本报告写入 `docs/evidence/v070/`，不覆盖历史报告。测试数量保持各引擎原合约数量（新增的是合约内断言），不要把断言或重跑次数算作独立测试数。
- 本轮覆盖升级以 V0.6.0/code8 为基线，安装 code9；核对 SQL Server 生产策略、MongoDB authDatabase、OpenSearch 索引范围与 SQL 收藏。
