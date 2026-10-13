# V0.9.0 实际测试报告

日期：2026-10-13。版本：0.9.0 / versionCode 14，包名保持 com.example.datasail。交付为实验性 Debug APK，不是零缺陷或正式生产兼容声明。

主要变更文件：`AiClient.kt`、`AiViewModel.kt`、`AiStore.kt`、`AiScreen.kt`；`ResultGrid.kt`、`SettingsScreen.kt`、连接 / 设置存储与 SQL page 接口及其三个实现；对应 JVM / Android 测试和云环境脚本。

## 本轮实现和验证对象

- AI：OpenAI 兼容 Chat Completions、Anthropic Messages、Gemini generateContent；19 个国内外服务预设和自定义 HTTPS 地址、上游模型获取、模型筛选 / 手填 ID、多服务配置、独立加密密钥、开关和连接发送策略。
- 发送前显式选择表结构 / SQL，预览并手动脱敏；默认不附加数据行、连接串或密码。回答仅生成文本 / SQL 草稿，经过本地风险判断和当前目标绑定，用户确认后载入编辑器，不自动执行。
- SQL 宽表按每组 64 列读取后续字段，保留 100 行分页；全屏模式、可调整列宽、换行、列显隐与整行详情。设置持久化和连接 schema 5 向后兼容。

## 构建、静态检查和 JVM

实际执行的命令包括：

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
./gradlew :sqlserver-android:check :sasl-android:check
bash scripts/test-jdbc-workspace-local.sh MYSQL
bash scripts/test-jdbc-workspace-local.sh MARIADB
bash scripts/test-jdbc-workspace-local.sh POSTGRESQL
bash scripts/test-sqlserver-local.sh
DATASAIL_SQLSERVER_AUTOCERT=1 bash scripts/test-sqlserver-local.sh
./gradlew :app:assembleRelease
```

| 检查 | 实际结果 |
|---|---|
| Core JVM | 72 通过，包括新协议、安全边界和实际 HTTPS 合成服务 |
| App JVM | 13 通过 |
| SQL Server Android 驱动源码适配 | 11 通过；check / 固定上游校验通过 |
| SQL Server 实际服务器 JVM | 16 通过，含第 130 列与宽表禁止写入 |
| SQL Server 自动证书 JVM | 1 通过 |
| MySQL / MariaDB / PostgreSQL 实际服务器 JVM | 分别 6 通过，独立服务；均包含末尾字段读取 |
| Debug APK / androidTest APK | 构建成功 |
| Android lintDebug | 0 issue |
| Release/R8 | 退出 1，仍有 193 个缺失类；没有 Release APK，未批量压制警告 |

共 131 项 JVM 检查通过。SQL 引擎版本、镜像 digest、驱动和工具链沿用 DRIVER_MATRIX；每个引擎本轮 XML 独立保存在 evidence/v090 下，不将 MariaDB 与 MySQL 互相推断。

## Android 实际执行

使用 Android 8 / API26 与 Android 11 / API30 AOSP x86_64 软件模拟器，无 KVM。最终安装包 SHA 与设备记录绑定在 `evidence/v090/devices.json`；完整统计见 `summary.json`。

| 最终 APK 检查 | API26 | API30 |
|---|---:|---:|
| V0.8.1 → V0.9.0 覆盖升级、Smoke、原连接 / 证书 / 查询收藏 / 密码 / 设置 / 表收藏 | 5 | 5 |
| 连接、移动工作流、查询收藏、SQLite、密码、设置、表收藏、AI 存储、宽表展示 | 17 | 17 |
| AI 真实 HTTPS 三协议与实际页面流程 | 2 | 2 |
| SQL Server 实连与可见导航、表收藏、单元格预览提交 / NULL | 3 | 未重跑 |
| SQL Server 自动证书确认、扫描和连接 | 2 | 未重跑 |

共 53 项当前 APK 的 Android 检查通过；另外两个旧版本状态准备检查各 1 项，单独计数，不混入当前版本通过数。升级使用 `adb install -r`，没有用清空新版数据绕过迁移；只在脚本明确允许的合成模拟器中重置升级夹具。

实际设备命令：

```bash
ANDROID_SERIAL=emulator-5556 bash scripts/test-android-upgrade.sh --reset-synthetic-emulator
ANDROID_SERIAL=emulator-5554 bash scripts/test-android-upgrade.sh --reset-synthetic-emulator
ANDROID_SERIAL=emulator-5556 bash scripts/test-ai-android.sh
ANDROID_SERIAL=emulator-5554 bash scripts/test-ai-android.sh
DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=emulator-5556 bash scripts/test-sqlserver-local.sh
DATASAIL_SQLSERVER_AUTOCERT=1 DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=emulator-5556 bash scripts/test-sqlserver-local.sh
```

通用 17 项采用显式 `am instrument -e class` 运行 ConnectionUiTest、MobileWorkflowTest、QueryStoreTest、SqliteDeviceTest、PasswordVaultTest、PasswordWorkflowTest、SettingsUiTest、TableFavoriteStoreTest、AiStoreTest、TablePresentationTest。需要真实夹具或旧 APK 的类排除在默认 connected 测试之外，仍由独立脚本明确执行。

AI 页面测试实际点击获取模型、筛选 / 选择 / 保存、勾选结构、预览脱敏、确认发送和载入 SQL 草稿；检查数据行哨兵没有进入请求、草稿不会自动执行、旧上下文不能批准、禁止连接不能发送。ART 测试对三种协议实际发出 HTTPS 请求，并拒绝错误主机、不可信证书及重定向。JVM 另验证阻塞 socket 取消、超大响应、认证隔离、分页、截断 / 工具输出拒绝和本地 SQL 判断。

AI 服务是本机 Python / JVM 合成 HTTPS 上游，证书仅在测试创建的实例中显式信任，没有修改 Android 系统信任或生产默认校验。这不是供应商实际账号验收。没有真实供应商 API key 或计费请求；匿名公共端点预检未取得成功响应，OpenAI 诊断为云代理 CONNECT 403，不能据此判定服务商不可用。19 个预设不代表逐家实测，更不代表全部模型 / 专有云认证都受支持。

## 发现的问题及保留证据

1. AI 初始按钮行的横向滚动布局导致页面测试未把按钮滚入可见区；操作区改为可换行布局、模型选择独立显示，随后实际点击全流程通过。
2. 新增设置开关后，旧用例按序号点击了错误项；设置开关增加明确的无障碍名称，测试按名称操作，校验换行、补全、主题、取消恢复和确认恢复。
3. API26 测试框架不能截图 Dialog。保留所有功能断言，API30 执行全屏截图；API26 不将底层窗口截图冒充全屏截图。
4. MariaDB 初始合成宽表的 129 个 VARCHAR 在默认字符集下超过行宽限制，建表被服务器拒绝；夹具改为整数列和末尾字符串列，仍保留 130 列及末尾精确值断言，三引擎重跑通过。
5. API30 冷启动后首次安装触发 AOSP system_server watchdog（PackageManager / StorageManager 锁），旧 APK 安装中断，尚未进入应用测试。保留系统日志，等待服务恢复，使用先传输再安装的非流式 ADB 安装方式重跑；未禁用系统 watchdog 或修改系统信任。
6. 最终复查发现 OpenAI 兼容回复的空 `tool_calls` 数组被误判为实际工具调用；允许空数组 / null，继续拒绝非空工具调用，新增协议断言，并让实际 HTTPS 夹具返回空工具字段。重新构建最终 APK，重跑两套 Android 验收；前一包证据另存 `diagnostics/before-empty-tools-fix`。
7. 预览测试改为对多段完整文本执行子串断言。上述初始失败及修正记录保存在 `evidence/v090/diagnostics`，不计为成功测试，也未通过删除功能断言使测试通过。

8. 最终 API26 证书复测出现提示可见性失败；有界等待和标题定位尝试均未解决。两个同时冷启动的软件模拟器的系统窗口均确认 `Application Not Responding: com.android.systemui`；API26 弹窗覆盖应用，保留窗口和事件日志。撤回未证明有效的布局试改，保留有界等待及原可见性断言，停止两台合成模拟器，改为一次仅运行一台；单独重启后 API26 / API30 仍各自弹出 SystemUI 提示，分别保留截图并点击系统提供的 Wait，确认应用恢复前台后继续页面验收。未禁用 ANR 或修改系统配置。原失败与系统诊断保存在 `diagnostics/autocert-*.log` 和 `api26-systemui-*.txt`。

## 产物与限制

最终 APK、androidTest APK、源码、报告、驱动归档和校验清单在 `versions/V0.9.0/` 与 `versions/V0.9.0.zip`；清洁提交 SHA 与签名指纹由 manifest 记录。保留原 Debug 签名，SHA-256 指纹 `1c0f10a1cc14cc44b1eefc18a78de23b34ce30761b70dae039bea7f302cc78db`。APK SHA 见 `summary.json`，不重签或重编译发布附件。

未验证真机 / arm64、用户业务服务器、供应商实际模型权限和计费、专用云 IAM / Azure / Responses-only / 多模态协议，以及当前版本完整九网络引擎 ART 矩阵。宽表和大字段仍有明确预算：每组 64 列、每页 100 行，大字段按旧边界标记未加载；字段组切换不是一致性快照。除 SQL Server 测试环境安全单行编辑外仍只读，不宣称补全 DBeaver 全部功能。

云环境启动说明保存为可复用草稿并读回核对；没有声称已发布新环境快照或验证新任务恢复。下一项最小工作是解决 Release 驱动门禁并补真机验收；AI 扩展优先 Responses / Azure 认证及具体供应商账号的针对性验证。
