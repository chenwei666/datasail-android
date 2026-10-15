# V0.14.0 正式混淆版实际测试报告

版本 0.14.0 / versionCode 22；2026-10-15 UTC。使用新的 RSA-4096 正式证书、R8 混淆、资源收缩，关闭调试，包名保持 `com.example.datasail`。所有数据库使用隔离合成数据，未访问用户业务数据库。

## 交付与环境

- Release APK SHA-256：`2decdb05b4d0b6382095ed96b6f2c220d3e56b5a813626a4444c17fbb0a6b5f1`。
- 正式证书 SHA-256：`86d3ec30bb7bb3ab44a41b1268d40003a746c2ecda078b7db5ad249ce02ae93c`。
- Temurin 21.0.8+9、Gradle 8.13、AGP 8.13.0、Kotlin 2.2.10、compileSdk/build-tools 36、minSdk 26。
- Android 8/API26 与 Android 11/API30 的 AOSP x86_64 软件模拟器；没有 KVM、真机或 arm64 验证。普通安装和使用不需要 Root；测试夹具仅使用 SDK 模拟器已有的 su 传送临时数据，并恢复 SELinux 标签。
- 签名、manifest、R8 mapping、最终 DEX 与逐套实际安装 APK 哈希分别保留。原有 193 类缺失引用基线在最终 DEX 中剩余 0，必要反射入口缺失 0。此扫描针对已知基线，不代表证明所有未知运行路径均兼容。

## 实际执行

```bash
export DATASAIL_TOOLS_DIR=/workspace/toolchains
source scripts/env.sh
# 签名目录只在私有发布机提供，不包含在源码或归档中。
./gradlew :sqlserver-android:check :postgresql-android:check :mariadb-android:check \
  :mongodb-core-android:check :mongodb-sync-android:check :core:test \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew -Pdatasail.testBuildType=release :app:assembleRelease :app:assembleReleaseAndroidTest
python3 scripts/verify-release-dex.py app/build/outputs/apk/release/app-release.apk
bash scripts/test-mysql-local.sh
# 分别对 emulator-5556/API26、emulator-5554/API30 执行：
export DATASAIL_TEST_VARIANT=release DATASAIL_ANDROID_TESTS=1
bash scripts/test-sqlserver-local.sh
DATASAIL_SQLSERVER_AUTOCERT=1 bash scripts/test-sqlserver-local.sh
for engine in MYSQL MARIADB POSTGRESQL; do bash scripts/test-jdbc-workspace-local.sh "$engine"; done
for engine in REDIS VALKEY; do bash scripts/test-key-value-local.sh "$engine"; done
bash scripts/test-mongo-local.sh
for engine in ELASTICSEARCH OPENSEARCH; do bash scripts/test-search-local.sh "$engine"; done
bash scripts/test-ai-android.sh
python3 scripts/summarize-release-verification.py
```

本地 UI 另以 instrumentation 执行 15 个测试类，共 33 项/系统；具体类和结果保存在每个平台 `local-ui.txt`。不以 adb 返回 0 代替测试通过；核对 `OK (N tests)` 与安装后的 app/test APK 哈希。相同 JVM 套件在多个设备流程中重跑时不重复计数。

## 最终通过结果

构建、签名校验、R8 基线扫描和 Debug lint 通过（lint 0 issue）。最终包完成 **217 项 JVM 检查、162 项 Release Android 检查**，另有 **3 项 Debug 设备冒烟**与 **24 项发布/完整性脚本回归**。

| JVM 套件 | 数量 |
|---|---:|
| core | 97 |
| app-debug | 17 |
| sqlserver-profile | 15 |
| postgresql-profile | 2 |
| mariadb-profile | 2 |
| mongodb-profile | 2 |
| SQL_SERVER | 24 |
| SQL_SERVER_AUTOCERT | 1 |
| MYSQL | 10 |
| MARIADB | 10 |
| POSTGRESQL | 10 |
| REDIS | 4 |
| VALKEY | 4 |
| MONGODB | 4 |
| ELASTICSEARCH | 4 |
| OPENSEARCH | 4 |
| driver-probes | 7 |

| Release Android 套件 | API26 | API30 |
|---|---:|---:|
| SQL Server | 4 | 4 |
| SQL Server 自动证书 | 2 | 2 |
| MySQL | 4 | 4 |
| MariaDB | 4 | 4 |
| PostgreSQL | 4 | 4 |
| Redis | 4 | 4 |
| Valkey | 4 | 4 |
| MongoDB | 5 | 5 |
| Elasticsearch | 6 | 6 |
| OpenSearch | 6 | 6 |
| AI HTTPS 合成上游 | 5 | 5 |
| 本地 UI / SQLite / 存储 | 33 | 33 |

全部 Release Android 结果绑定同一个最终应用 APK。53 项已完成的网络用例使用保留的测试包；仅 UI 等待和可见工具栏定位随后调整，剩余 109 项使用最终测试包，受影响的全部 UI 套件在两个系统均重跑。各测试包、精确源码差异和逐套哈希均归档。

- 最终测试 APK SHA-256：`a6982509ce0e9c78cb4090b694d9e44b579e776287034813f00e61a45ab50790`。
- 汇总及逐套哈希：[summary.json](evidence/v0140/summary.json)、[devices.json](evidence/v0140/devices.json)。
- 签名与缺失引用：[release-signature.txt](evidence/v0140/release-signature.txt)、[release-dex.json](evidence/v0140/release-dex.json)。
- 失败候选、测试流程错误与模拟器 ANR 保存在 `docs/evidence/v0140/attempts/`，不计入上述通过数。

## 覆盖的行为

- SQL Server：TLS/身份校验、数据库与 Schema 浏览、参数化单行增改删、并发冲突、取消/超时、固定当前表 DROP 的取消及元数据变更拒绝；真实操作均针对临时合成表。普通与全屏界面双击编辑、NULL 修改、确认令牌失效均通过触摸测试。
- MySQL/MariaDB/PostgreSQL：扫描后搜索选择目标、严格 TLS、读写与并发校验、浮点原值比较、NULL/空值、数字类型保真及元数据变化拒绝。
- Redis/Valkey：受限字符串读写、WATCH 争用及 TTL；MongoDB：完整 BSON 文档原子比较和浏览；Elasticsearch/OpenSearch：版本条件单文档读写、保留路径字符拒绝及浏览。不是任意类型或管理命令的兼容性承诺。
- SQLite：应用私有副本读写、冲突和源文件不变；无稳定主键、不完整读取、不支持类型继续受保护。
- AI：真实平台 HTTPS 连接合成服务，覆盖 OpenAI 兼容、Claude、Gemini 文本协议；取模型、普通问答、当前表解释、结构预览、草稿、加密记忆恢复/隔离/清空。不等于真实商业供应商逐个验证。
- 本地 UI：导航、密码/配置存储、历史、收藏、500 库选择、130 列宽表、全屏、缩放、筛选排序、SQL 提示及硬件 Tab。

## 本次实际发现并修复

1. 上游驱动包含 Android 缺失的桌面管理、认证、异步、原生与云扩展引用。新增可重建的源码适配、上游校验和精确补丁；未支持入口明确失败，保留已验证的 TCP/TLS、密码/SCRAM 和同步路径。没有 blanket `-dontwarn` / `-ignorewarnings`。
2. SQL Server 通过字符串加载资源包，混淆后入口被删。增加精确资源包保留规则，最终包通过 Android 两系统真实连接。
3. MariaDB Configuration 反射依赖 Builder 字段与方法名，混淆后丢失凭据及 TLS 配置。增加精确保留规则和建连前的有效配置核验；不得在配置丢失时发起网络连接，单元与真实 MySQL/MariaDB 回归通过。
4. 独立混淆测试 APK 的共享接口、Kotlin 合成入口及 AI TLS 夹具反射构造函数需要准确保留。自动生成实际调用成员规则，并保留必要反射入口；不把测试 APK 链接失败当成功。
5. API26 合成夹具文件曾缺少应用 SELinux 类别标签。仅修正模拟器夹具的文件写入与 `restorecon`，没有关闭 enforcing，也没有改变应用证书信任。
6. 证书 UI 测试仍按旧版展开列表点击 master；新增打开搜索选择弹窗这一步，保留 TLS 拒绝、证书绑定、扫描与连接的全部断言。宽表测试等待实际剪贴板值后再做内容断言。随后系统诊断确认模拟器 System UI ANR 弹窗抢占焦点，Android 拒绝应用读取剪贴板；保留日志、重启隔离模拟器，并关闭 SDK System UI 故障提示后核对正常窗口焦点；没有改变系统剪贴板权限。未证实的应用滚动修改已撤回。宽表测试随后发现同一字段值在底层表格与详情弹窗中都存在，不能用于判断点击回调完成；改为先将横向工具栏整体滚入外层纵向可见区、确认按钮可见后真实点击，再等待实际弹窗标题并检查弹窗内的内容。连接表单测试等待键盘收起及页面自动滚动完成；可见性断言保留。应用 APK 未变。
7. 云端 Docker vfs 的大镜像同时运行曾挤占磁盘；大夹具改为互斥调度，数据库数据仍为有界临时目录。早期候选包、夹具及测试脚本失败保存在 `attempts/`，不计入最终通过数量。

## 签名迁移与验证边界

- V0.13.0 及此前的 Debug 证书与新证书不同。实际覆盖安装收到预期 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；该记录来自同一正式证书的较早候选包，属于签名迁移失败证据，不是最终包成功升级记录。
- 正式版首次安装后，今后更新必须复用该证书。当前没有完整配置迁移工具；先保存所需连接、收藏、SQL 与设置，再决定是否卸载旧版。卸载会删除本地数据和加密密钥。
- 本轮 Debug 冒烟与 Release 使用可丢弃模拟器中不同签名的独立安装，不将清除合成测试数据后的新装称为升级。
- 未验证真机/arm64、所有服务端版本、用户业务表、真实 AI 供应商、完整弱网/性能矩阵以及独立归档源码重建。云 IAM、GSS/Kerberos、XA、MongoDB SRV/自动字段加密等扩展明确不在支持范围。
- 不承诺零缺陷、全部数据库或完整 DBeaver。最小下一步是使用本次正式包进行 Android 真机/arm64 验收，再扩展其它引擎和复杂字段。

## English

V0.14.0 is a production-signed, non-debuggable APK with R8 minification and resource shrinking. The exact delivered APK was exercised against isolated real database servers on Android 8/API26 and Android 11/API30 x86_64 emulators. JVM checks, device tests, synthetic AI HTTPS fixtures, signature verification and known missing-reference scans are recorded separately in the linked evidence.

The certificate-picker test now opens the current database chooser, and the wide-table test waits for the actual clipboard value before asserting its content. A System UI ANR on the software emulator was diagnosed separately and the emulator restarted. A full Kotlin/R8 rebuild changed the APK bytes, so earlier candidate results were retained only as diagnostics and the complete final device matrix was rerun against the delivered APK. Completed network suites retain their recorded test APKs. Subsequent changes only synchronize UI assertions and bring the horizontal toolbar into its outer vertical viewport before actual touch injection, and isolate the software keyboard state before engine selection. The full UI suites run with the final test runner on both systems; all test APKs and exact source differences are archived. JVM results are counted once per engine; repeated Android device runs do not require duplicating the same host-side checks.


Older Debug-signed APKs cannot be overwritten by the new production signature. There is no complete configuration migration tool. Physical/arm64 devices, every server version, live commercial AI vendors and independent archive rebuilds were not verified. Supported operations and types remain bounded; passing this matrix is not a guarantee of zero defects or full DBeaver parity.
