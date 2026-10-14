# V0.9.1 实际测试报告

日期：2026-10-14。版本：0.9.1 / versionCode 15，包名保持 com.example.datasail。本次交付实验性 Debug APK，正式 Release 门禁仍未通过。

## 修复与功能

- 用户录屏显示 SQL Server 连接、列库和切库正常，点击表的「查看」后报“不支持此能力”并断连。在隔离 SQL Server + API26 上新增日期读取用例，修复前复现 `NoSuchMethodError: Timestamp.valueOf(LocalDateTime)`，堆栈落在驱动 DDC 和 JdbcResultReader。没有访问用户业务库，也没有公开上传录屏、连接地址或业务结构。
- SQL Server 源码适配更新至 `13.6.0.jre8-datasail.2`。DDC 改用 Android 支持的 java.sql 构造方法，保留本地年月日、纳秒和原有时间舍入；time 文本直接格式化数值字段，避免 java.time Formatter 兼容问题与额外时区归一化。没有吞掉异常、清空结果冒充成功或放宽 TLS。
- AI 服务地址、协议、密钥、模型列表 / 模型选择和开关移至「设置 → AI 服务与模型」。助手未配置时只给出设置入口；配置后用于提问、上下文预览和 SQL 草稿。数据库和 AI 密钥仍在原来的独立加密域。
- 表结构使用有界字段属性列表；字段名 / 类型搜索、主键筛选、复制字段名、NULL / 主键 / 自增 / 生成列 / 默认值属性。仅使用当前已加载元数据。

主要变更文件：`sqlserver-android/patches/android-platform.patch` 与来源锁；`AiSettingsScreen.kt`、`AiScreen.kt`、`SettingsScreen.kt`、`WorkspaceScreen.kt`、`TableStructurePanel.kt`；对应驱动、SQL Server 实连、UI 和升级测试。

## 构建与 JVM

实际执行：

```bash
export DATASAIL_TOOLS_DIR=/workspace/toolchains
source scripts/env.sh
./gradlew :sqlserver-android:check :sasl-android:check :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=emulator-5556 bash scripts/test-sqlserver-local.sh
DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=emulator-5554 bash scripts/test-sqlserver-local.sh
./gradlew :app:assembleRelease --console=plain
```

| 检查 | 结果 |
|---|---|
| Core JVM | 72 通过 |
| App JVM | 13 通过 |
| SQL Server 驱动源码适配 | 14 通过；来源 / 补丁哈希、JAR 成员和未修改上游内容校验通过 |
| SQL Server 2022 CU16 实连 JVM | 17 通过 |
| Debug 与 androidTest APK | 构建通过 |
| lintDebug | 0 issue |
| Release / R8 | 退出 1；193 个缺失类，未生成 Release APK，未批量压制警告 |

共 116 项 JVM 检查，重复执行不重复计数。驱动测试比较 UTC、上海、纽约时区的 JDK 日期语义，包含闰日、早期日历日期、DST 边界、9999 年上限和纳秒 / 跨午夜时间。服务器用例验证 date、time、datetime、smalldatetime、datetime2、datetimeoffset 的精确文本、NULL，以及查看真实合成表后继续查询。日期修复不开放原先禁止的日期写入。

## Android 最终 APK

Android 8/API26 与 Android 11/API30，AOSP x86_64 软件模拟器；一次只运行一台，无 KVM。设备实际安装 APK 哈希见 `evidence/v091/devices.json`；汇总见 `summary.json`。

| 检查 | API26 | API30 |
|---|---:|---:|
| V0.9.0 → V0.9.1 覆盖升级、Smoke、状态保留 | 5 | 5 |
| 设置、宽表字段属性、AI 设置导航、移动工作流 | 4 | 4 |
| AI 三协议 HTTPS 及实际页面流程 | 2 | 2 |
| SQL Server 实连、日期读取、表格 UI、原有安全编辑和取消 | 3 | 3 |

共 28 项最终 APK 的 Android 检查通过；另有两项旧版状态准备测试，不混入当前版本通过数。

设备命令除上述 SQL Server 脚本外，对两台分别执行：

```bash
ANDROID_SERIAL=<selected-emulator> bash scripts/test-android-upgrade.sh --reset-synthetic-emulator
adb -s <selected-emulator> shell am instrument -w -e class com.example.datasail.SettingsUiTest,com.example.datasail.TablePresentationTest,com.example.datasail.AiNavigationTest,com.example.datasail.MobileWorkflowTest com.example.datasail.test/androidx.test.runner.AndroidJUnitRunner
ANDROID_SERIAL=<selected-emulator> bash scripts/test-ai-android.sh
```

- 升级采用上一版本原件和 `install -r`；验证原数据库连接 / 密码、证书信任、查询收藏、表收藏、显示设置及 AI 服务 / 模型 / 加密密钥保留。只在明确的合成模拟器重置旧版夹具，不删除新版数据规避迁移。
- SQL Server UI 经真实登录、切库、Schema 和表按钮打开含六种日期时间类型的表，检查单元格可见且仍保持连接，随后继续收藏和原有单元格预览 / 提交 / NULL / 只读查询。服务器独立临时 TLS 证书和合成账号，脚本清理自身资源。
- 表属性测试在真实 SQLite 130 列表中搜索末列、切换主键筛选、恢复全部字段并验证剪贴板；保留字段分组与全屏断言。
- AI 测试使用本机合成 HTTPS 上游；实际从设置获取 / 选择 / 保存模型，返回助手预览发送、脱敏、产生草稿并手动载入。助手页不存在密钥 / 地址 / 模型输入控件；未配置和系统返回键路径独立验证。三协议、严格证书、认证隔离和不自动执行的既有断言保留。这不是供应商账号或计费实测。

## 初始失败与证据

- 修复前 API26 日期读取明确失败，日志保存在 `diagnostics/sqlserver-before-fix-api26.log`，不计为通过。原普通字符串表流程当时仍通过，说明旧测试缺少日期字段覆盖。
- 初次新增导航测试引用未安装的 Espresso，instrumentation 编译失败；使用 Android 自带返回键输入，没有增加依赖。初始构建诊断保留。
- 初次 API26 导航返回键被系统窗口拦截，报 `INJECT_EVENTS`；确认 SystemUI ANR 覆盖模拟器后，选择系统提供的 Wait 恢复，保留原测试与可见性断言后重跑。没有修改系统信任、禁用 ANR 或删除断言。
- 最终 time 文本格式化改为直接格式化时分秒数值，避免把纯时间绑定到 1970 年默认时区；最终构建后重新跑设备流程。此前结果另存 diagnostics，不混入最终 APK 验收。

- 新增日期表查看断言通过后，UI 脚本切换下一张表时没有重新展开自动收起的对象列表，导致找不到筛选控件；补上真实「浏览表 / 视图」点击并重跑 SQL Server 完整流程。保留 `diagnostics/sqlserver-ui-reopen-list-api26.log`，没有删减日期单元格 / 连接状态或编辑断言；主 APK 未变，只更新测试 APK。

- API30 冷启动约 210 秒后仍有 SystemUI ANR 与短暂 FallbackHome 启动画面；记录系统事件和截图，等待安装服务恢复并选择系统提供的 Wait 后继续。没有修改设备系统配置或屏蔽错误提示。

- API30 宽表测试的原始 StateFlow 等待超时；补充诊断确认同一 ViewModel 的实际页面已显示 64 列结果且仍连接。等待条件改为 Compose 可见语义节点，并保留 10 秒轮询限制、全部数据 / 属性 / 分组 / 全屏断言；仅修改测试同步，主 APK 未变。诊断保存在 `diagnostics/api30-wide-*`，本轮成功结果见 mobile-api30.txt。API26 原断言已在相同主 APK 上通过，测试同步修正后的用例在 API30 验证。

## 交付与边界

`versions/V0.9.1/` 与 `versions/V0.9.1.zip` 包含源码快照、APK、测试 APK、报告、驱动、manifest 和逐文件校验；source SHA 及 APK SHA 由归档脚本绑定。原 Debug 签名 SHA-256 `1c0f10a1cc14cc44b1eefc18a78de23b34ce30761b70dae039bea7f302cc78db` 保持不变，支持覆盖安装；历史归档不覆盖。

未验收用户业务库、未知字段类型、真机 / arm64、其它 SQL Server 版本、供应商真实模型账号、其它数据库引擎本轮实连矩阵，以及完整 DBeaver 功能。Release/R8 仍是已知门禁，不能宣称正式生产版或零 bug。下一项最小工作为 Release 驱动兼容与真机验收。

云环境按 onboarding setup 技能保存启动草稿，保留原安装与网络配置；保存不代表发布环境快照或通过新任务恢复验证。
