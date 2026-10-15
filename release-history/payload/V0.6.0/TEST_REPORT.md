# V0.6.0 实际测试报告

日期：2026-10-13。versionCode 8，applicationId `com.example.datasail`；沿用本地 Debug 签名。此次新增 Elasticsearch / OpenSearch；当前十种引擎是阶段性实现，不是全数据库或正式 Release 兼容声明。

## 主要变更文件

- `core/.../SearchSession.kt`、`SearchJson.kt`、`SearchRunner.kt`：独立 HTTPS 搜索会话、有界严格 JSON/DSL、安全取消与线程执行；Model/DatabaseCatalog/JDBC 路由区分搜索能力。
- `app/.../SearchScreen.kt`、`ConnectionsScreen.kt`、`WorkspaceViewModel.kt`：原生索引/映射/查询界面、索引范围连接表单和状态隔离。
- `scripts/test-search-local.sh`、`scripts/fixtures/search-control.py`、SearchContract/Integration/Device/Ui/Policy 测试：独立真实服务、对抗传输与移动验收；升级和归档脚本、CI、当前版本文档同步。

## 构建与静态检查

实际执行 `./gradlew :sqlserver-android:check :sasl-android:check :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain`，退出 0。映射归属修正后执行 `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain`，退出 0；此后 APK 保持冻结并用于最终设备检查。SQL Server profile 与两份 Harmony SASL 源码完整性检查执行通过；原有 SQL Server 11 项驱动测试任务 UP-TO-DATE，复用缓存，不计入本轮 53 项重新执行数。

`:app:assembleRelease` 退出 1，R8 缺失 **193** 个类型，与 V0.5.0 的集合完全一致；未加 blanket dontwarn，无 Release APK。完整构建/缺失类日志见 evidence/v060。修正映射标题归属后，重新构建最终 APK 并重跑两个引擎全部 Android 检查和覆盖升级；先前安装包的通过日志单列 diagnostics/pre-mapping-fix，不计入最终数量。

## 本轮 JVM（53 项，0 失败/跳过，最终选定套件）

| 套件 | 数量 | 实际执行范围 |
|---|---:|---|
| core 单元 | 43 | 原有安全/会话/类型规则与严格 JSON/DSL、数值原文、重复键/非法 Unicode/深度拒绝 |
| app 单元 | 4 | 状态与按引擎字段验证，包括索引范围和 Basic 用户名 |
| Elasticsearch 8.19.5 | 3 | 真实索引/映射/分页/筛选/类型、TLS/认证/权限；独立对抗 HTTPS 传输测试 |
| OpenSearch 2.19.4 | 3 | 同上，独立服务；最终读取用例连续十轮，仍按 3 个测试计数 |

实际命令：`bash scripts/test-search-local.sh ELASTICSEARCH`；`bash scripts/test-search-local.sh OPENSEARCH`；诊断增强 `DATASAIL_SEARCH_READ_REPEATS=10 bash scripts/test-search-local.sh OPENSEARCH`。两个 image 的平台 manifest 固定 SHA256，服务版本见各自 server-version.json。服务管理员仅用于合成夹具；服务端验证 reader 的 PUT 文档返回 403。

## 本轮 Android（最终 APK 28 项）

| 设备 | Elasticsearch | OpenSearch | 覆盖升级/Smoke | 合计 |
|---|---:|---:|---:|---:|
| Android 8 / API26 / x86_64 | 5 | 5 | 4 | 14 |
| Android 11 / API30 / x86_64 | 5 | 5 | 4 | 14 |

真实运行 `DATASAIL_ANDROID_TESTS=1 DATASAIL_ANDROID_SERIALS='emulator-5556 emulator-5554' bash scripts/test-search-local.sh ELASTICSEARCH`；OpenSearch 同命令加 `DATASAIL_SKIP_JVM=1` 复用已完成 JVM 结果。每引擎每设备 3 项核心/传输合约 + 1 项真实 Compose 流程 + 1 项映射归属的纯 UI 回归，均 `OK (5 tests)`。传输故障项来自明确分开的本机 HTTPS 对抗服务，不能冒称真实引擎慢查询测试。Compose 实测连接、索引、100 条翻页、编辑条件禁用旧续页、term 筛选和映射。

每设备执行 `ANDROID_SERIAL=... bash scripts/test-android-upgrade.sh --reset-synthetic-emulator`。在一次性模拟器安装不可变 V0.5.0/code7，写入 schema3 SQL Server/MongoDB（自定义 authDatabase）和 schema1 收藏，然后 `adb install -r` 安装 code8，保留私有标记、连接、生产只读策略与收藏；没有把清空安装当作覆盖升级。映射标题修正前已在两设备显式运行 ConnectionUiTest、QueryStoreTest、MobileWorkflowTest、SqliteDeviceTest，分别 `OK (8 tests)`，其 APK 为 `f51f1b59117d68893bbe69111857e4b6c3379745bee41a58de299afc0d358f7e`；相关源码未因映射标题修正改变，未重复这些 16 项，且不算作最终 APK 的 28 项。日志放在 diagnostics/pre-mapping-fix。Smoke 包含旧 SQL 驱动加载，早一构建的 16 项包含真实 SQLite 文件工作流；**不替代其它七种网络引擎本轮重新实连**。

## 失败、限制与证据边界

- OpenSearch 首次 JVM 运行在最后一次查询出现 `Unexpected end of file from server`，该轮 3 项中 1 项失败，原 XML/日志保留于 diagnostics，不计入通过数。增加服务端日志采集后复测及十轮完整读取未再现；根因未确认，不宣称已修复。客户端关闭会话并分类网络错误，无应用层自动重试。长期断连/弱网稳定性继续作为已知问题。
- 官方 tarball URL 在此环境返回 403，改用 Docker Hub 固定 digest。vfs 复制镜像层曾耗尽磁盘；仅移除已确认闲置的可重下载镜像缓存，保留全部旧档案、源码和数据库卷；搜索服务顺序验证。无系统参数或证书绕过。
- 前八种引擎的历史真实网络验收仍在 V0.5.0 等不可变档案中，本轮未重跑 SQL Server/MySQL/MariaDB/PostgreSQL/Redis/Valkey/MongoDB 服务端套件。安全/路由单元、驱动完整性与加载、移动流程、SQLite 已回归。
- 未验证物理/arm64、其它服务版本、PIT/scroll、云认证、多节点故障转移、所有主流引擎、系统 CSV 文件提供者、正式身份/签名和长期性能。当前搜索只读，限制见 KNOWN_ISSUES。
- 未执行远程 CI、归档源码独立重建、push、公开 Release 或云环境发布。start_skill 已保存草稿，未发布/验证新任务恢复。

## 产物绑定

最终 APK SHA256：`bf4c94cba29d9730f48475295bfa7c565ebc87543a7d4240b14df795c56d8a01`。两台设备 installed base.apk 均独立核对相同哈希，见 devices.json；源码提交及签名 SHA 由归档 manifest 记录。Debug 构建、源码快照、实际测试 APK 和版本必须相符，旧版本 ZIP 哈希另验。

交付路径：`versions/V0.6.0/apk/DataSail-V0.6.0-debug.apk`、`versions/V0.6.0.zip`。归档必须由干净本地提交生成并校验；本报告不等同发布声明。下一最小任务：ClickHouse 只读表与查询工作台，同时继续处理 Release 与真机门禁。
