# V0.13.0 实际测试报告

版本 0.13.0 / versionCode 21；2026-10-14（Asia/Bangkok）。交付 Debug APK，未通过 R8 的 Release 门禁。测试全部使用隔离合成数据；未访问用户业务数据库。

## 本次范围

- AI 设置直接保存当前地址/Key 并获取模型；选择模型自动保存，AI 未启用时仍可获取列表。
- 两个可连续问答的工作区、普通对话与表结构解释、固定结构化操作；本地 AES-GCM 加密上下文记忆，恢复、隔离、截限与清空。
- SQL 表格双击直接编辑草稿；确认前无数据库写入。分页、筛选排序、宽表、缩放、SQL Tab 补全及行操作菜单。
- SQL Server、MySQL、MariaDB、PostgreSQL、SQLite 私有副本的受限单行写入；Redis/Valkey 字符串、MongoDB 完整文档、Elasticsearch/OpenSearch 版本条件文档写入。
- SQL Server 当前表 DROP_TABLE 的固定语句、目标/元数据/触发器复核、取消及单次令牌；界面另需输入完整表名确认。

## 构建与环境

Temurin 21.0.8+9、Gradle 8.13、AGP 8.13.0、Kotlin 2.2.10、SDK/build-tools 36。Docker loopback 临时服务器及临时 CA；Android 8/API26 AOSP x86_64 软件模拟器，无真机测试。服务器固定镜像摘要见 DRIVER_MATRIX 与脚本。

执行：

```bash
export DATASAIL_TOOLS_DIR=/workspace/toolchains
source scripts/env.sh
./gradlew :sqlserver-android:check :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
./gradlew :app:assembleRelease --console=plain
export ANDROID_SERIAL=emulator-5556 DATASAIL_ANDROID_TESTS=1
bash scripts/test-ai-android.sh
bash scripts/test-sqlserver-local.sh
for engine in MYSQL MARIADB POSTGRESQL; do bash scripts/test-jdbc-workspace-local.sh "$engine"; done
for engine in REDIS VALKEY; do bash scripts/test-key-value-local.sh "$engine"; done
bash scripts/test-mongo-local.sh
for engine in ELASTICSEARCH OPENSEARCH; do bash scripts/test-search-local.sh "$engine"; done
bash scripts/test-android-upgrade.sh --reset-synthetic-emulator
```

本地 UI 单独 instrumentation 执行 `AiHistoryTest,SqliteDeviceTest,AiNavigationTest,WorkbenchInteractionTest,TablePresentationTest,TableFilteringTest`。不把 adb 进程返回 0 当成测试成功；核对每份 JUnit/仪器测试最终结果。最终额外修复带点号的 Schema/表名记忆隔离与搜索文档路径保留字符校验后，重新构建生产 APK 与 AndroidTest APK，对最终安装包重跑两种受影响的搜索引擎、AI、记忆与本地 UI，以及旧版覆盖升级。

构建、驱动 profile check、单元、Debug lint 通过（lint 0 issue），Debug 与 AndroidTest APK 实际产出。`assembleRelease` 退出 1，缺失类 193 个；没有压制缺失类，没有 Release APK，也没有生产签名。

## JVM：201 项

| 套件 / 引擎 | 数量 | 结果 |
|---|---:|---|
| core-unit | 96 | 通过 |
| app-unit | 17 | 通过 |
| driver-profile | 14 | 通过 |
| SQL_SERVER | 24 | 通过 |
| MYSQL | 10 | 通过 |
| MARIADB | 10 | 通过 |
| POSTGRESQL | 10 | 通过 |
| REDIS | 4 | 通过 |
| VALKEY | 4 | 通过 |
| MONGODB | 4 | 通过 |
| ELASTICSEARCH | 4 | 通过 |
| OPENSEARCH | 4 | 通过 |

九种网络引擎分别访问真实临时服务器。JDBC 覆盖参数化增改删、NULL/空值、数字保真、浮点原值比较、完整旧行并发冲突、元数据变化和单次确认。Redis/Valkey 验证 WATCH 争用及 TTL；MongoDB 验证完整原文及 BSON 类型的原子匹配；搜索引擎验证原生版本冲突与单文档删除。

SQL Server 实际 DROP 使用临时表，验证取消、元数据变化拒绝和确认后不存在；不是用户业务表。搜索传输的响应丢失另用故障 HTTPS 夹具：PUT/DELETE 到达一次后断开，必须 UNKNOWN、关闭连接、不重放；不冒充真实集群断网实验。

## Android：67 项

本表是本轮开发的完整通过记录。最终 APK 对搜索两引擎、AI、本地 UI 与升级共 38 项重新验证；SQL Server/JDBC/Redis/Valkey/MongoDB 共 29 项在上一验证构建 `05f8743f22260506c0695d8eac02ba8a627b84963c7b6f33c5e354ea714f89ff` 通过。这些模块的生产实现此后未变，最终仅增加搜索文档 ID 的路径校验；不将上一包测试写成全部在最终包重跑。各套件与 APK 的对应关系见 summary.json 的 androidArtifactCoverage。

| 原始证据（docs/evidence/v0130 下） | 数量 | 结果 |
|---|---:|---|
| ai-emulator-5556.txt | 5 | 通过 |
| local-ui-emulator-5556.txt | 16 | 通过 |
| upgrade-api26.txt | 5 | 通过 |
| SQL_SERVER/android-emulator-5556.txt | 4 | 通过 |
| MYSQL/android-emulator-5556.txt | 4 | 通过 |
| MARIADB/android-emulator-5556.txt | 4 | 通过 |
| POSTGRESQL/android-emulator-5556.txt | 4 | 通过 |
| REDIS/android-emulator-5556.txt | 4 | 通过 |
| VALKEY/android-emulator-5556.txt | 4 | 通过 |
| MONGODB/android-emulator-5556.txt | 5 | 通过 |
| ELASTICSEARCH/android-emulator-5556.txt | 6 | 通过 |
| OPENSEARCH/android-emulator-5556.txt | 6 | 通过 |

- AI 使用实际平台 HTTPS、合成上游和三种文本协议；界面输入新 Key 后取模型，AI 关闭时可配置、选模型保存；无数据库普通聊天、当前表解释、结构预览、SQL 草稿、请求隔离。问“我叫什么”确认上一轮记忆被传递；Activity 重建/存储重开验证恢复，切换模型隔离，清空生效。不是收费模型供应商验证。
- SQLite 检查私有副本增改删与冲突，原导入源不变；无主键/超宽/不支持类型保护保留。
- UI 覆盖 130 列最后窗口、全屏和整行详情；65%–200% 双指缩放及文字不裁切；500 库搜索选择；WHERE 字段提示与硬件 Tab；整表筛选后分页和状态恢复。
- SQL Server 在 ART 上实连及普通/全屏触摸编辑、NULL 修改、取消删除、旧令牌失效；新流程从只读状态直接双击打开草稿并提交，不通过测试直接调用 ViewModel 来替代点击。
- 其它网络引擎在 ART 上跑实际读写与冲突合同；MongoDB/搜索附带浏览界面用例。NoSQL 新写入表单的所有手工路径、AI 整表删除完整 UI/供应商联动尚未穷尽，不能称全 UI 验收。
- V0.12.2 → V0.13.0 使用相同包名/签名 `adb install -r`；5 项新版检查通过，另 1 项旧版种子准备不计入新版总数。保留连接、凭据绑定、证书信任、收藏、AI 配置和设置；不是卸载后新装冒充升级。

## 发现的问题与复测

1. MySQL/MariaDB 文本协议浮点参数与服务端 float 原值比较不一致，导致正常修改被误判冲突。改为服务器预处理与实际 float/double 类型绑定后，三个 JDBC 引擎分别通过真实回归。
2. HTTPS 复用服务端已关闭连接导致 EOF，隔离 socket-factory 缓存身份后通过搜索读写及取消/断响应测试；TLS 信任规则未放宽。
3. 宽表测试最初只滚动内部横向工具栏，未把按钮滚入外层视口；随后“整行详情”同时命中底层和全屏节点。使用可见工具栏与弹窗限定定位，保留全屏和完整值断言后完整套件通过。之前的软件模拟器 System UI ANR 不计为通过。
4. OpenSearch 第一次启动因临时文件系统空间不足失败；保留并迁移已停止的 API30 AVD 文件后重试。故障记录保存在 diagnostics，不计入通过总数。

5. 记忆键原先使用展示名称，`a.b` Schema 的 `c` 表与 `a` Schema 的 `b.c` 表可能冲突。改为分别编码 Schema 和表名，并在 Android 记忆隔离用例中验证两种对象的键不同。

6. 搜索服务器初始化失败于宿主镜像占用触发磁盘水位（账号创建等待超时，后续 OpenSearch 写入返回 429）以及 OpenSearch writer 缺少内部 bulk 动作权限。夹具数据改用有界 1 GiB 临时内存文件系统，保留服务端磁盘保护；初始化预算 30 秒、就绪轮询 2 秒，writer 仅有对应动作及目标索引权限。应用请求预算、reader 禁止写入及原生版本校验保持不变。
7. 搜索文档 ID 为 `.` / `..` 或包含路径保留字符时，HTTP 路径归一化可能改变目标。写入预览阶段拒绝这类 ID，并在两个真实搜索引擎的 JVM 与 Android 合同中验证三种写操作均拒绝、索引保持存在。索引映射 UI 断言同步新标签，仍检查切换选择不会误标旧映射。
8. 覆盖升级的旧版本种子最初调用了新版构造签名；改为反射调用不可变 V0.12.2 实际拥有的两参数构造函数，再执行真正的覆盖升级。此项只修改测试，交付 APK 不依赖反射。

最终证据见 [summary.json](evidence/v0130/summary.json)、[devices.json](evidence/v0130/devices.json) 和各引擎 XML/仪器日志。诊断失败与最终通过分开保留。

## 产物与未验证

- Debug APK SHA-256：`0f03dbd7408d2c015f0e12fa71a14e0f61403462624723e7e1a309242cdb72f5`。
- AndroidTest APK SHA-256：`dd7b2eb46ee2a78a9427c2c4b6d738618c4f794f8ad7df193bc8966ba0ff9a96`。
- 已安装 base.apk 的哈希与交付 APK 一致；源码提交、签名、ZIP/文件校验由归档 manifest 与校验清单记录。
- Android 11/API30 当前构建、真机/arm64、其它服务端版本、用户 dbo.iclock_transaction、真实 AI 供应商、弱网/性能完整矩阵与独立归档源码重建未通过本轮验收；不沿用历史版本结果冒充本轮。
- 不宣称零缺陷、全部数据库和完整 DBeaver。类型/对象限制见 [KNOWN_ISSUES](KNOWN_ISSUES.md)。后续最小任务是 Release/R8 缺失 API 适配及 Android 11/真机复测，再扩展复杂字段与未实现引擎。
