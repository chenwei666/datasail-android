# V0.7.0 实际测试报告

版本 0.7.0 / versionCode 9；2026-10-13（Asia/Bangkok）。保留 com.example.datasail 与既有 Debug 签名；这是实验性 Debug 交付，无 Release APK。

## 本轮行为与变更

连接页支持目标库留空：先验证服务器并扫描可见数据库/逻辑库/索引，筛选选择后再连接工作台。SQLite 继续使用文件选择器。独立扫描任务在完成后关闭临时连接，修改参数、取消、页面离开及后台会作废旧列表和回调；密码不持久化。保留手动目标输入，权限不足不冒充空库。

主要变更：`core/.../DatabaseDiscovery.kt`、`JdbcConnector.kt`、`MongoSession.kt`、`KeyValueSession.kt`；`app/.../ConnectionsScreen.kt`、`WorkspaceViewModel.kt`；新增 DiscoveryContract/DiscoveryUiTest，更新夹具与升级测试。扫描语义、权限、列表上限及取消边界见 WORKSPACE。

## 构建与 JVM

实际命令：`./gradlew :sqlserver-android:check :sasl-android:check :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain`，退出 0。Debug、Lint 均通过（Lint 无 issue）。SQL Server 驱动模块 11 项单元测试为缓存 UP-TO-DATE，不计入本轮新增执行数；来源校验执行。

| 套件 / 真实引擎 | 通过测试数 |
|---|---:|
| coreUnit | 46 |
| appUnit | 7 |
| SQL_SERVER | 15 |
| MYSQL | 5 |
| MARIADB | 5 |
| POSTGRESQL | 5 |
| REDIS | 3 |
| VALKEY | 3 |
| MONGODB | 3 |
| ELASTICSEARCH | 3 |
| OPENSEARCH | 3 |

共 **98 项** JVM 测试通过，无失败、跳过。引擎合约新增扫描断言，断言数与重跑数不算独立用例。实际命令分别为 `scripts/test-jdbc-workspace-local.sh MYSQL/MARIADB/POSTGRESQL`（三个独立调用）、`scripts/test-key-value-local.sh REDIS/VALKEY`（独立调用）、`scripts/test-mongo-local.sh`、`scripts/test-sqlserver-local.sh`、`scripts/test-search-local.sh ELASTICSEARCH/OPENSEARCH`（独立调用），各退出 0。固定真实服务版本、镜像摘要见 DRIVER_MATRIX；只连接本机隔离 TLS 合成夹具。

扫描覆盖九种网络引擎；MySQL/MariaDB/SQL Server 不指定目标库，PostgreSQL 从 postgres 入口发现 synthetic，MongoDB reader 仅发现授权 synthetic，Redis/Valkey 发现有键的 0/5 号库、无 INFO 权限时报错，Elasticsearch 从默认范围发现 synthetic；OpenSearch 单索引 reader 对默认 * 明确返回权限错误，再以已授权范围发现 synthetic。已有 TLS/SAN/CA/认证、只读、类型、分页与安全写入合约随对应套件执行。新增扫描名称身份回归：带前后空格的元数据名称必须禁用，不能经表单 trim 转成另一个库。取消测试同时检查关闭任务、丢弃晚到回调与释放凭据；错误提示测试禁止透出未清洗的远端异常文本。

`./gradlew :app:assembleRelease --console=plain` 退出 1。R8 仍缺 193 类，相比 V0.6.0 新增 0 类；未增加 blanket dontwarn，无 Release APK。保留完整构建日志和缺失清单。

## Android：按实际 APK 区分

两个设备均为 AOSP x86_64 软件模拟器，无 KVM；API26/Android8 与 API30/Android11。型号与安装 SHA 见 devices.json。没有真机、arm64 或其它系统版本的验收。

最终 APK SHA-256：`a0783e3967e9925d44ba2d4d2cd13200798b6f4c6a59a9686d716897263ff3e2`。测试 APK：`7b959ecd700401702544c4a9dbcfe791e9ba199d293b0c8342014f87e623b996`。

- API26：PostgreSQL 实连及扫描界面 4、通用移动/SQLite/配置持久化 8、升级与启动 4，共 16 项。
- API30：通用移动/SQLite/配置持久化 8、升级与启动 4，共 12 项。
- 最终 APK 共 **28 项** Android 检查通过。PostgreSQL 界面从库名留空开始完成扫描、筛选、选择、连接，并验证修改主机会清除扫描列表、授权与密码。真实 PostgreSQL 夹具同时创建 synthetic 与带前后空格的独立库，界面断言后者不可选择，前者仍可连接，防止错连。
- 另有提示完善前 APK `4fdfe450cf23d12b86eb0e5bb25ea850838d33699a897ad39ce81a6987c38717` 的 **22 项**通过：API26 MySQL/MariaDB/PostgreSQL 各 4、Redis/Valkey 各 3；API30 MySQL 4。它们包含真实扫描及原有工作台回归，保留于 diagnostics/pre-scan-feedback，**不计入最终 APK 数**。之后只调整扫描错误解释与入口参数错误提示，核心协议代码未改变。MySQL API26 首轮测试 APK 早于强化空密码断言，其后 MariaDB/PostgreSQL/API30 MySQL 使用强化后的断言。另有加入名称身份保护前 APK `d4c191846a20feafb523ddce88c8bc0c61fe2f8920c9e8a93824e888ff1cd83f` 的 **44 项**通过，包含 API26 PostgreSQL/SQL Server/MongoDB/Elasticsearch/OpenSearch 实连、两设备通用移动及覆盖升级；保留于 diagnostics/pre-name-identity，**不计入最终 APK 数**。核心协议代码没有改变；最终 APK 针对新增安全保护重新运行 PostgreSQL 实连/UI，以及两个系统的通用移动和覆盖升级。最终 APK 未重跑全部引擎/系统的排列组合。

设备夹具命令加 `DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=emulator-5556`；搜索脚本指定 `DATASAIL_ANDROID_SERIALS=emulator-5556`。通用移动用 `adb shell am instrument -w -e class com.example.datasail.ConnectionUiTest,com.example.datasail.MobileWorkflowTest,com.example.datasail.SqliteDeviceTest,com.example.datasail.QueryStoreTest com.example.datasail.test/androidx.test.runner.AndroidJUnitRunner`，每设备 OK(8)。搜索对抗 HTTP 测试是独立模拟 peer，不冒充真实服务慢查询。

升级实际使用 `scripts/test-android-upgrade.sh --reset-synthetic-emulator`，只重置这两个专用模拟器。先安装不可变 V0.6.0/code8，写入合成元数据和私有标记，再 `adb install -r` 安装 code9；核对 SQL Server 生产策略、MongoDB custom_auth、OpenSearch logs-* 与 PostgreSQL 查询收藏保留。不是卸载后新装冒充升级；不承诺降级。

## 限制与交付

扫描不代表服务器全部资产或后续一定可登录。SQL/MongoDB 受权限影响，PostgreSQL 入口库/HBA、Redis 空库/INFO 权限、搜索普通索引/范围限制均需遵守。库名限制仍为字母、数字、下划线、横线；不支持的名称不可选择。JDBC 未返回连接期间不能承诺即时中止 DNS/握手；过期回调立即失效，连接返回后关闭，依赖驱动登录超时收尾。

本轮 OpenSearch 首次 JVM 读取用例因测试错误假设“单索引 reader 可枚举 *”而失败；服务端日志确认目录权限拒绝。随后测试显式要求宽范围权限失败、授权范围成功，不更改应用或扩大服务账号权限；初始 XML 和原因保留在 diagnostics/opensearch-wildcard-permission。

V0.6.0 曾有一次 OpenSearch EOF，历史根因仍未确认，不因本轮通过宣称已修复。未执行长时间弱网、物理设备/arm64、大型目录压测、所有云认证和全主流数据库验收。当前十种引擎不是全部数据库；AI/跨引擎写入及正式发行门禁仍在路线图。

交付由干净本地提交生成 `versions/V0.7.0/` 与 ZIP，完整源码、APK、文档、manifest、SHA256SUMS；不覆盖既有归档。归档校验结果在交付后另记录，不把源归档独立重构建当作已执行。没有推送、公开发布或远端 CI 声明。云环境 start_skill 已保存为待发布草稿；没有发布或新任务恢复验证。

下一项最小任务：ClickHouse 表与查询工作台；继续推进 R8/真机发行门禁。
