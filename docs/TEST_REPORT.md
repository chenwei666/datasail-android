# V0.5.0 实际测试报告

日期：2026-10-12。versionName=0.5.0、versionCode=7；applicationId 与 Debug 签名延续 V0.4.0。阶段性实验版本，不是全数据库最终版。结果汇总与 APK 绑定见 `evidence/v050/summary.json`、`devices.json`。

## 本轮交付

新增 MongoDB 8.0.32 实连文档工作台：TLS/严格 CA+SAN、SCRAM-SHA-256、独立认证数据库、集合、受限 JSON 筛选、服务器游标分页、canonical Extended JSON。分页请求批次 25、显示最多 100；停留阅读不消耗下一页的客户端预算。超大文档明确未展开，无写入/脚本/聚合/任意管理命令。保留其它七种连接类型。

Android 实测发现官方驱动依赖缺失的 javax.security.sasl 类型；`sasl-android` 引入 Apache Harmony 两份未改动 API 类型，编译核对固定上游 SHA-256，许可证与通知打包并核对。认证实现仍为官方驱动，未提供假认证或放宽证书验证。JVM 使用 JDK API，ART 的真实正负认证验证才覆盖该适配。

连接 schema 3 增加 authDatabase，旧 schema 1/2 兼容；SQL 收藏不接受文档引擎。页面切换重置滚动位置、收起键盘。会话构造失败清理凭据，证书失败在驱动丢失 cause 时仍保持安全分类。

## 命令与结果

所有命令从仓库根目录执行，先 `export DATASAIL_TOOLS_DIR=/workspace/toolchains; source scripts/env.sh`。以下通过总数只按独立用例/引擎/设备计一次；诊断失败与重复运行不计入通过数。

- `./gradlew :sqlserver-android:check :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain`：退出 0。核心 40、app 4、驱动 11 项，共 55 项；驱动源码未改动的测试任务可复用 Gradle 缓存。`sasl-android:verifyUpstream` 由编译依赖触发；同时校验 APK 内法律通知字节。
- `scripts/test-mongo-local.sh`：3 项 JVM。205 文档 100/100/5 分页、第一页后停留 11 秒、ObjectId 精确筛选、长整数/Decimal128/日期/二进制/NULL、超限提示；错误 SAN/CA/密码/authSource、read 角色拒绝 INSERT；真实服务器 failCommand 阻塞触发超时与取消。控制连接的较长预算仅用于等待测试故障点退出，读取预算未放宽；登录单独设为 30 秒，UI 验收等待预算与实际登录预算匹配。
- `scripts/test-mysql-local.sh`：7 项 JVM（其中未知 CA 反例 1 项）；`scripts/test-sqlserver-local.sh`：15 项 JVM。
- `scripts/test-jdbc-workspace-local.sh MYSQL|MARIADB|POSTGRESQL`：各 5 项 JVM。
- `scripts/test-key-value-local.sh REDIS|VALKEY`：各 3 项 JVM。
- 合计 **101 项 JVM**，无失败/跳过计入通过数。
- 两系统分别以 `DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=... scripts/test-mongo-local.sh` 执行：各 3 项共享合约和 1 项真实 Compose 流程。UI 从连接表单进入工作台，点集合、下一页、编辑筛选后禁用旧游标继续按钮，再查询精确文档。
- `ANDROID_SERIAL=... scripts/test-android-upgrade.sh --reset-synthetic-emulator`：每系统 4 项（Smoke 3 + Upgrade 1）。安装 V0.4.0/code6、写入旧 schema 2 元数据和 schema 1 收藏，再覆盖 code7；校验配置、生产策略、收藏和文件标记，保存/读回自定义 MongoDB authDatabase。仅重置可丢弃模拟器。
- 两系统另以 `adb shell am instrument -w -e class ...` 运行 ConnectionUiTest、QueryStoreTest、MobileWorkflowTest、SqliteDeviceTest：各 8 项。
- API30 额外重复 Redis 3、Valkey 3、SQL Server 2 项真实网络测试。
- 合计 **40 项 Android 检查**：API26 16 项、API30 24 项；MongoDB 两系统都是最终完整 4 项，不以 UI-only 诊断代替完整验收。

真实证据在 `evidence/v050`；原始本轮 JVM XML、设备 instrumentation 输出、升级输出、构建日志和未通过的诊断均分别保留。finalApkSha256 与设备读取的已安装 base.apk 相同；归档脚本拒绝哈希不一致。六个旧 ZIP 的哈希重新核对，旧归档未覆盖。

## 问题定位与限制

调试阶段分别发现并修复重复 GraalVM 资源打包冲突、测试 failpoint 返回数字类型/等待预算/重复 maxTimeMS 参数、Android 缺少 SASL API、异步 UI 断言/嵌套滚动定位，以及逐文档往返造成页面截止、带界面运行时登录预算过紧。保留 diagnostics，不将初始失败隐藏为一次通过。最后回归曾因宿主磁盘满导致 SQL Server 容器启动失败；清理已完成测试的 MongoDB 可重下载镜像缓存后重跑成功，源码/旧归档及不明数据卷未删除。游标超时模式也增加了实际停留后的分页验证。最初一文档一批改为 25，避免移动网络上百次往返；应用每页截止和内容预算仍保持。

`./gradlew :app:assembleRelease --console=plain` 仍退出 1，R8 报告 193 项缺失类（比 V0.4.0 增加 50 项，SASL 两个缺失类型已由固定 API 源码补齐）；未添加批量 dontwarn，没有 Release APK。新引擎的 Java 官方驱动不代表 Android 官方支持。

- 本轮 Android MySQL/MariaDB/PostgreSQL 网络测试未重复；V0.3.0 的设备证据是历史结果，本轮只重复其 JVM。Redis/Valkey 本轮仅 API30 重复，API26 设备证据来自 V0.4.0。
- 设备为 AOSP API26/API30 x86_64 软件模拟器（具体 fingerprint 在 devices.json）；物理设备、arm64、弱网长期稳定性、云认证、MongoDB SRV/副本集/分片拓扑和其它服务器版本未验收。没有接入用户业务数据库。
- MongoDB 仅单地址目标库只读；DocumentDB 等兼容品牌未验证。集合最多 200、每页 100 文档、单文档预览 32 KiB/8192 字、深度 24、节点 2048、页字符 262144；这些是应用预览预算，不是驱动网络缓冲或进程堆的硬上限。游标不是快照，服务端过期后需重新查询。
- UI 显示失败不会自动重连重试。当前无文档写入、聚合或导出；SQL Server 以外的图形写入、AI 和全量导出仍未完成。CSV 的完整第三方系统文件提供者交互尚未自动化验收。
- CI 配置已加入 MongoDB JVM 夹具；本轮仅本地执行，未声称 GitHub Actions、真机、归档源码重新构建或公开 Release 已完成。

下一最小任务：Elasticsearch/OpenSearch 索引与搜索工作台，各服务独立实连；继续推进 R8 与物理设备门禁。可复用云启动说明见 CLOUD_START，保存草稿与发布/新任务恢复是不同步骤。
