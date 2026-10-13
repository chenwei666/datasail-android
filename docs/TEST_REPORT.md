# V0.8.1 实际测试报告

版本 0.8.1 / versionCode 13，2026-10-13 UTC。保持 com.example.datasail 和原 Debug 签名。本次仍为实验性 Debug 预发布，不能承诺零缺陷或生产正式就绪。

## 改动及问题原因

- 原工作台连接成功只请求 catalog/schema，没有选中 Schema 或加载表，且入口是默认空白下拉框。原 SQL 界面测试直接调用 ViewModel 加载表，绕过了真实导航入口，因此漏掉此交互问题。本次 SqlNavigation、WorkspaceViewModel、WorkspaceScreen 自动加载默认 Schema 和对象列表，切库/Schema 后同步刷新；列表可直接点击、筛选，空列表、部分失败和刷新入口均可见。
- 原 `catalogs() to schemas()` 的后一步失败会丢弃整个结果。现在分别处理非致命元数据失败，保留成功部分及已认证当前库；网络、超时、失联仍传播到会话失效处理，不冒称空列表成功。
- TableFavoriteStore、QueryLibrary 增加最多100项本机表收藏，绑定连接ID/端点/账号及库/Schema/名称/表或视图类型；不保存数据行、密码或旧object_id。打开时重新查询元数据，匹配目标后读取；可切回原库，不自动开启写入。修改服务器、账号或复制连接后不能用原收藏打开新目标。
- ConnectionsScreen、WorkspaceViewModel 的历史连接可一键使用已存加密密码登录并进入工作台；缺少密码或解密失败进入配置。延迟读取回调受连接代次约束，取消不会复活旧连接。严格TLS、显式证书指纹信任和生产只读保持。
- 真实SQL Server回归另发现ProbeRunner取消线程与finally清理线程可能并发close。现在一次连接只执行一次close，两条路径共享真实关闭结果，关闭失败仍为UNKNOWN，不重放查询。新增阻塞关闭回归在原实现上失败，在修复后通过，见diagnostics。

## 实际验证

`./gradlew :sqlserver-android:check :sasl-android:check :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain` 通过。修复close后再次执行core/app单测、lint及两个Debug APK构建；最终日志build-final.log。核心62、应用13、驱动profile11个JVM单测通过，lint 0问题。驱动未变更，另以 `:sqlserver-android:test --rerun` 明确重跑11项，避免把缓存报告当作本轮执行。

`DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=emulator-5556 bash scripts/test-sqlserver-local.sh`：SQL Server 2022 CU16真实TLS服务，JVM15项和Android3项通过。UI实际点击历史连接重登、库选择、Schema选择、表筛选/打开及跨库表收藏；没有使用model.tables()/model.page()绕过首个对象入口。回归Tab、预览前未写入、文本/NULL提交、已知结果刷新及任意SELECT只读。设备测试包含超时、取消、只连接一次、确实完成关闭；失败断言未放宽。

`DATASAIL_SQLSERVER_AUTOCERT=1 DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=emulator-5556 bash scripts/test-sqlserver-local.sh`：未配置证书的SQL Server，JVM1项、Android2项通过，包含IP、自签名证书预览确认与扫描连接。

Android8/API26与Android11/API30 x86_64软件模拟器：`bash scripts/test-android-upgrade.sh --reset-synthetic-emulator` 各5项通过，从不可变V0.8.0/code12覆盖到code13，保留连接/生产只读、查询收藏、证书信任、加密密码与设置。旧包安装后各运行1项合成状态准备检查，不计入新包通过数；未清除真实用户数据。TableFavoriteStore是新增文件，旧版本没有表收藏，不将其算作旧版迁移。

同两台设备使用明确class参数运行ConnectionUiTest、MobileWorkflowTest、QueryStoreTest、SqliteDeviceTest、PasswordVaultTest、PasswordWorkflowTest、SettingsUiTest、TableFavoriteStoreTest，各14项通过。包含SQLite一键连接后自动出现表、实际点击读取、收藏/打开、持久化及同名异库/Schema/端点隔离；以及密码、原查询收藏和设置回归。

新包合计102项JVM、43项Android检查通过。精确计数、日志与安装包哈希见 `evidence/v081/summary.json`、`devices.json`。先前中间包及原close实现的失败保存在diagnostics，不计入最终包通过数。原实现阻塞关闭测试退出1，修复后全套core测试退出0。

`./gradlew :app:assembleRelease --console=plain` 退出1，仍有193个R8缺失类；未添加批量dontwarn，没有生成或交付Release APK。Debug成功不是Release通过。

## 边界与交付

- 最终APK SHA-256：`41b2060a94f861d095cc2f6b7bdb8ce66c356a49f51a81269cd524160f01b471`；测试APK：`6b9c30b26953ecacad9194789f8932c26be91332bc781b9b7d3b6ab12a602e95`。devices.json记录从两台设备实际已安装base.apk读取并核对的哈希。
- 版本归档：`versions/V0.8.1/` 与 `versions/V0.8.1.zip`，来自干净提交。保留12个旧版ZIP/归档，包名及签名不变。独立归档源码重建未执行。归档manifest中的published=false代表打包时状态，后续GitHub发布另有回执。
- 没有访问用户业务数据库；真实服务为本机隔离合成夹具。用户服务器、物理设备、arm64、大库/弱网全矩阵和其他网络引擎的新界面实连未验证。历史十引擎证据不算本轮测试。
- PostgreSQL每连接一库；默认Schema空时需手动选择其它Schema。表收藏仅SQL表/视图，不含文档集合、键或搜索索引；打开前需连接原连接。生产保持只读，其他引擎写入、完整DBeaver功能仍未完成。
- 已按cloud-environment-onboarding:setup保存更新后的start_skill草稿（docs/CLOUD_START.md），未发布/激活新环境快照。修正emulator路径及恢复后残留锁的处理，进程需重新启动；未声称V0.8.1新云任务恢复验证通过。
- 下一项最小任务：解决Release驱动适配并补真机/arm64验证，再讨论正式生产发行。
