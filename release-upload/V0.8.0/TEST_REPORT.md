# V0.8.0 实际测试报告

版本 0.8.0 / versionCode 12，2026-10-13（Asia/Bangkok）。保持 com.example.datasail 与原 Debug 签名；仅交付实验性 Debug 包。

## 改动

- PasswordVault、WorkspaceViewModel、ConnectionsScreen：可选 Keystore AES-GCM 密码，独立 noBackup 存储、端点/账号/连接绑定、异步自动填入、忘记/清除。
- AppSettings、SettingsScreen、MainActivity：持久化主题、网格密度/字号/类型、编辑器字号/补全、生产默认值及设置恢复。
- ResultGrid、SqlEditor、WorkspaceScreen：表格单元格查看/复制/原位编辑、NULL、预览/确认、已知结果只读刷新；本地关键字及已加载元数据 Tab 补全。SQL Server 测试会话之外的既有只读边界保持。
- WorkspaceRunner：重复断开清理合并，手动重连排在旧会话关闭后，保留有界串行操作与取消代次校验。
- 密钥/密码不进入连接 JSON、备份、日志或源码；复制和端点/账号变化不可复用。见 PASSWORD_STORAGE 与 WORKSPACE。

## 实际命令与结果

`./gradlew :core:test :sqlserver-android:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --rerun-tasks --console=plain` 通过，最终构建日志存于 evidence/v080。核心 55、应用 13、驱动 profile 11 个单测实际执行，无跳过；覆盖补全中间 token、引用转义、字符串/注释/美元引号排除及网格值保真。

`ANDROID_SERIAL=… bash scripts/test-android-upgrade.sh --reset-synthetic-emulator`：API26/API30 各 4 个通过，从不可变 V0.7.2/code11 覆盖至 code12，保留合成连接、生产只读、Mongo authSource、搜索范围、收藏与证书指纹。密码与设置是本轮新加，不把旧版不存在的功能算作升级迁移验证。

Android 通用 7 个测试类（ConnectionUiTest、MobileWorkflowTest、QueryStoreTest、SqliteDeviceTest、PasswordVaultTest、PasswordWorkflowTest、SettingsUiTest），两台设备各 13 个通过。覆盖引擎筛选、SQLite/收藏、加密往返与端点/账号/副本隔离、密文损坏拒绝、设置重读、密码保存/掩码自动填入/忘记、主题及补全开关/恢复确认。截图仅为合成夹具，测试内临时解除 FLAG_SECURE，生产仍禁止截屏。

`DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=emulator-5556 bash scripts/test-sqlserver-local.sh`：真实 SQL Server 2022 CU16 TLS 服务，JVM 15 个 + Android 3 个通过。包括原单行编辑/冲突/未知结果安全合约，以及界面保存密码后重新登录、真实硬件键 Tab 事件、单元格预览前未写入、提交文本保留前导零、NULL 写入、提交后刷新和任意 SELECT 结果不可编辑。

`DATASAIL_SQLSERVER_AUTOCERT=1 DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=emulator-5556 bash scripts/test-sqlserver-local.sh`：JVM 1 个 + Android 2 个通过，回归自签名/IP/证书处理/扫描连接。

本轮合计 95 项 JVM、39 项 Android 检查通过。精确计数与安装哈希见 `docs/evidence/v080/summary.json`、`devices.json`。

`./gradlew :app:assembleRelease --console=plain` 退出 1：仍有 193 个 R8 缺失类，与 V0.7.2 新增 0、移除 0。未添加批量忽略，未交付 Release APK。

## 发现与修正

初次密码自动填入 UI 测试错误地期待语义树暴露明文，因实际返回掩码超时。改为验证掩码、已保存状态及独立解密结果，再以真实 SQL Server 的重连认证证明密码可用。真实重连测试随后复现重复 disconnect 清理占满队列。WorkspaceRunner 合并清理、为一个清理和一个已受理操作保留有界空间，并增加阻塞旧连接关闭的确定性回归；仍串行关闭旧会话后才登录，不重试写入。网格仪器测试还修正了嵌套滚动定位，先滚动外层表格并断言单元格可见，再进行真实点击，并推进测试时钟等待双击识别窗口结束。全量重建还改变了 classes6.dex 和 APK 哈希，随后对最终包重新跑设备和实连矩阵。中间失败及中间包日志留在 diagnostics，不计入最终包通过数。KTX 风格检查建议的 SharedPreferences.edit 扩展会丢弃 commit 成功值，存储函数仅对该风格提示作精确注解，保留落盘失败处理。编译期实验 API 与跨模块 smart cast 问题已修正。

## 限制与交付

- 未测试物理设备、arm64、所有厂商 Keystore、实际用户服务器、强弱网络全矩阵或新云任务恢复；不宣称密钥一定硬件背书。没有生物认证/应用锁、密码导出或设备迁移恢复。
- 图形写入仅 SQL Server 测试会话中的可安全定位表；无批量粘贴、公式、任意 SQL 写入或多格事务。其它引擎网格当前只读。补全不是完整语言服务器，不解析全部别名/上下文，仍需核对手写 SQL 的对象归属。
- 完整九网络引擎矩阵来自 V0.7.1 历史证据，未计入本轮。密码/设置/新 SQL 网格是本轮新实现；不因界面共用就宣称其它数据库实连全部通过。
- 最终 APK SHA-256：`b90e3dba9afdfc882abe6542ec711f585baaa4a2d447953720f19e10802267ec`。测试 APK：`abc700c3c3eed0f8bddca1da40c33760e65ebe7608c21209b852197e149d51c8`。
- 交付 `versions/V0.8.0/` 与 `versions/V0.8.0.zip`，完整源码/安装包/文档/manifest/SHA256SUMS；干净本地提交，未推送或公开发布。独立源码归档重建未执行。
- 云环境 start_skill 更新为本轮入口并保存草稿，未发布。下一项最小任务为数据库对象树与对象搜索，再补多查询标签和各引擎写能力。
