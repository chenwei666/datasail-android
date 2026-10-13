# V0.7.2 实际测试报告

版本 0.7.2 / versionCode 11；2026-10-13。原应用 ID 与 Debug 签名保持。仅交付实验性 Debug APK。

## 本轮变更

连接页改为响应式引擎卡片、分类筛选、搜索、分段表单和数据库整行单选。证书操作直接可见，扫描 TLS 失败自动定位处理卡片；首次信任仍需独立核对，不自动忽略证书错误。压缩顶部状态区，实际版本来自 BuildConfig；更新明暗主题与导航图标，提交连接时收起键盘。

主要文件：ConnectionsScreen.kt、ConnectionComponents.kt、WorkspaceScreen.kt、MainActivity.kt、WorkspaceViewModel.kt、app/build.gradle.kts。驱动、TLS 验证实现、配置 schema 和数据库能力没有变化。

## 实际执行

- `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain`：通过。
- `./gradlew :core:test :app:testDebugUnitTest :sqlserver-android:test :app:lintDebug --rerun-tasks --console=plain`：通过；核心 54、应用 9、驱动 profile 11 个单测。本轮实际执行，未跳过。
- 压缩顶部后重新执行 `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug`：通过。
- `DATASAIL_SQLSERVER_AUTOCERT=1 DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=emulator-5556 bash scripts/test-sqlserver-local.sh`：SQL Server 2022 CU16 无配置证书、IP 地址的真实场景，JVM 1 个、最终 APK 的 Android UI 2 个通过。扫描拒绝未信任证书、自动定位入口、不展开 CA 设置即可获取/确认、持久化、扫描选库和连接；取消及修改端点不得复用批准。
- `ANDROID_SERIAL=… bash scripts/test-android-upgrade.sh --reset-synthetic-emulator`：API26 与 API30 各 4 个通过；先安装不可变 V0.7.1/code10，再 install -r code11，保留合成连接、生产只读策略、Mongo authSource、OpenSearch 范围、SQL 收藏及绑定服务器的证书指纹。只重置指定的临时模拟器数据。
- Android ConnectionUiTest、MobileWorkflowTest、QueryStoreTest、SqliteDeviceTest：API26 与 API30 各 8 个通过，覆盖引擎分类/搜索、SQLite 表单、删除确认、收藏导出和只读浏览。截图仅包含合成数据，测试内临时解除 FLAG_SECURE；生产代码始终保留该标记。
- Android 11 额外横屏检查：1600×900、density 240、字体 1.3 倍、深色模式，单个引擎分类/搜索/表单用例通过；测试结束恢复显示配置。两台设备竖屏和此横屏截图位于 evidence/v072。测试脚本最初把未设置的 font_scale 当作字面量 null 恢复，被系统拒绝；随后删除该设置并恢复浅色模式、默认尺寸与密度，检查恢复成功，见 diagnostics/wide-display-cleanup.txt。
- 最终 APK Android 合计 27 个；JVM 合计 75 个。具体计数、设备信息及安装 SHA-256 在 `docs/evidence/v072/summary.json` 与 `devices.json`。
- `./gradlew :app:assembleRelease --console=plain`：退出 1，仍为 193 个 R8 缺失类；与 V0.7.1 比较新增 0、移除 0。未添加 blanket dontwarn，未交付 Release APK。

## 真实性与限制

完整九网络引擎矩阵是 V0.7.1 的历史结果，本轮没有重新跑其它八引擎实连，不能把历史结果算作本轮通过。早期界面包的通过日志留在 diagnostics；最终计数仅使用 summary 所绑定 APK。测试均为 x86_64 软件模拟器，非用户实际服务器、物理手机或 arm64；未验证全套 TalkBack、所有大字体和厂商键盘。DBeaver 式对象树、多查询标签、补全、ER 图仍未实现。

## 交付

- 最终 APK SHA-256：`9fe9bcd48b550be7d90781c29dc285bb964e242e5a7cc961f0d6a01cd7fa0619`。
- AndroidTest APK SHA-256：`16688e6baac0000bfa13a3187a2b86f6fa3949286ab0a5bc13fafcf4257cdf47`。
- 归档：`versions/V0.7.2/`、`versions/V0.7.2.zip`，完整源码/文档/安装包/manifest/SHA256SUMS；来自干净本地提交，没有推送或公开发布。独立源码归档重建未执行。
- 云环境 start_skill 已按本轮测试入口更新并保存草稿，未发布、未在新任务恢复验证。
- 下一步最小任务：移动端数据库对象树与对象搜索，再推进 SQL 编辑器多标签；保留各引擎独立验收。
