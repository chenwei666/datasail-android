# V0.10.0 实际测试报告

日期：2026-10-14。版本 0.10.0 / versionCode 16；包名与原签名保持连续。本次可交付的是 Debug APK；Release/R8 门禁仍失败，不能宣称正式生产版或零 bug。

## 完成内容与变更文件

- `WorkspaceScreen.kt`、`MainActivity.kt`：工作台固定数据库 / Schema / 对象入口，以及「数据 / SQL 编辑器 / 表结构」页签；分页与写入操作保留在底部。连接操作、收藏与导出收进菜单，选择单元格后的详情与编辑入口移至表格上方。切表 / 翻页恢复顶部；修复跨库表收藏返回时对象弹窗遮住已加载表格。
- `SearchPicker.kt`、`ConnectionsScreen.kt`：扫描结果改为紧凑选择入口、搜索与有界惰性列表，避免数百数据库撑长页面；手动填写与权限语义保留。
- `ResultGrid.kt`：双指 65%–200% 缩放列宽、字号、行高，支持百分比复位；单指滚动、原值 / 前导零 / NULL 保真。
- `SqlEditor.kt`、`InlineAiSql.kt`、`AiViewModel.kt`：SQL 输入框独立页签；WHERE / AND / OR / ON / FROM / JOIN 等上下文提示已加载字段或表名；硬件 Tab 接受候选或缩进，选中 SQL 时不会误替换整段。AI 使用设置中的服务，默认关闭；明确预览与授权后，在输入停顿 1.2 秒时请求，建议仅手动接受，绝不自动执行。离开编辑器或目标 / 模型 / 表结构变化时撤销授权并取消旧请求；不发送结果行。
- `SqlServerTemporal.kt`、`WorkspaceModel.kt`、`SqlServerSession.kt`：增加六种日期时间类型的精确文本参数绑定，避免 Android JDBC 缺失接口；校验范围、精度和隐式舍入，按二进制原值检查并发修改（包括相同 UTC 时刻但不同 offset）。未改变的日期输入不产生伪更新。生产标记连接仍默认只读，显式确认后可开启本次编辑，切库 / 断开复位。无稳定主键、视图、触发器、大字段或不支持的类型继续只读；其它引擎仍只读。
- `Model.kt`、`SettingsScreen.kt`：业务环境「测试」改为「常规」，保留「生产 / 只读 / 可写」状态；移除首页实验性宣传，但诊断与报告如实显示构建类型。保存格式、应用标识、签名和旧数据不变。

编辑入口：工作台「开启编辑」→ 确认当前目标 → 双击非主键可编辑字段（或选中后编辑）→ 检查原值 / 新值与参数化 SQL → 确认提交。用户截图的日期列只读原因由精确日期绑定解决；其它保护性只读条件仍会说明具体原因。

## 实际命令与结果

```bash
export DATASAIL_TOOLS_DIR=/workspace/toolchains
source scripts/env.sh
./gradlew :sqlserver-android:check :sasl-android:check :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
# 最终 SQL Tab 修复后重建并重跑 app 检查
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
# 分别选择 emulator-5556 / emulator-5554；设备操作串行
ANDROID_SERIAL=<selected-emulator> bash scripts/test-android-upgrade.sh --reset-synthetic-emulator
adb -s <selected-emulator> shell am instrument -w -e class com.example.datasail.SettingsUiTest,com.example.datasail.TablePresentationTest,com.example.datasail.AiNavigationTest,com.example.datasail.MobileWorkflowTest,com.example.datasail.WorkbenchInteractionTest com.example.datasail.test/androidx.test.runner.AndroidJUnitRunner
# API30 宽表测试同步修正后的完整单独重跑
adb -s emulator-5554 shell am instrument -w -e class com.example.datasail.TablePresentationTest com.example.datasail.test/androidx.test.runner.AndroidJUnitRunner
ANDROID_SERIAL=<selected-emulator> bash scripts/test-ai-android.sh
DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=<selected-emulator> bash scripts/test-sqlserver-local.sh
./gradlew :app:assembleRelease --console=plain
```

| JVM / 构建检查 | 结果 |
|---|---:|
| Core 单元测试 | 74 通过 |
| App 单元测试 | 14 通过 |
| SQL Server 驱动源码适配 | 14 通过 |
| SQL Server 2022 CU16 实连 | 18 通过 |
| Debug、androidTest APK、lintDebug | 构建通过；lint 0 issue |
| Release/R8 | 退出 1，193 个缺失类；未产生 Release APK |

共 120 项 JVM 检查，重复运行不重复计数。Release 缺失类未用批量 dontwarn 屏蔽。

## 最终 APK 的 Android 验收

Android 8/API26 与 Android 11/API30 AOSP x86_64 软件模拟器，一次运行一台，无 KVM。`evidence/v0100/devices.json` 记录系统指纹与实际安装 APK SHA；`summary.json` 绑定最终 APK、测试 APK 与执行记录。

| 检查 | API26 | API30 |
|---|---:|---:|
| V0.9.1 → V0.10.0 覆盖升级、Smoke、状态保留 | 5 | 5 |
| 设置、130 列 SQLite、导航、移动流程、500 库选择、真实双指缩放、Tab | 7 | 7 |
| 三协议合成 HTTPS AI、设置 / 助手、实时建议授权与不执行 | 3 | 3 |
| SQL Server ART / UI、日期读取写入、生产标记编辑、收藏、取消 | 3 | 3 |

合成工作台截图：`evidence/v0100/workbench-api26.png` 与 `evidence/v0100/table-landscape-api30.png`，不含用户业务数据。

Android 11 首批界面检查 6 项通过、宽表 1 项失败；同一主 APK 上修正测试的键盘 / 滚动同步后，单独完整重跑宽表用例通过（`wide-filter-api30.txt`）。没有重复计数或改写原始失败日志；API26 已通过相同原有断言，新的选中状态等待仅用于 API30 重跑。

共 36 项最终 APK 检查；另有每台 1 项旧版状态准备，不计入当前版本通过数。覆盖安装使用 V0.9.1 原件与 install -r，验证连接 / 加密密码、证书、查询收藏、表收藏、设置及 AI 服务 / 模型 / 独立密钥保留。重置仅针对明确的合成模拟器旧版夹具，没有删除新版配置规避迁移。

SQL Server 使用隔离临时 TLS 服务和合成账号；验证真实登录、切库、浏览日期表、单元格预览 / 提交 / NULL、并发冲突回滚、结果刷新及取消。AI 使用本机合成 HTTPS 上游及测试注入的临时 CA，未修改系统信任或生产证书规则；验证三种协议、身份校验、认证隔离、发送预览、默认不开启实时建议、接受草稿不执行与离开关闭。它不是供应商账号 / 计费实测。

## 初始失败与修复证据

失败日志保留在 `evidence/v0100/diagnostics/`，不计为通过：

- 初始 Compose 手势测试 API 引用和 fixture 参数编译失败，修正至实际 API；lint 提示使用窗口尺寸 API 后改用 LocalWindowInfo / LocalDensity。
- 导航改成固定控件和弹窗后，旧测试对其调用外层滚动；修正真实交互及唯一搜索结果匹配，保留可见性 / 内容断言。
- 新 SQL Server 日期用例发现 ISO `T` 与驱动空格分隔文本导致未修改值误判；改为比较解析后的原值并增加无变化拒绝断言。
- 收藏跨库回到工作台时，加载结果完成后对象弹窗未关闭，导致表格被遮挡；修复结果与弹窗状态联动，保留跨库收藏、实际可见与后续编辑断言。
- API30 宽表测试在横屏软键盘显示 / 外层滚动期间，先出现主键筛选未变化、随后复现目标字段存在但不可见。测试在输入搜索词后显式收起软键盘，等待 IME 隐藏、控件可见及主键选中状态，再保留空结果 / 复制 / 字段组 / 全屏等全部原断言完整重跑通过。应用筛选逻辑与最终 APK 未变，两次失败日志均保留。
- API30 SQL Server 界面首次在 NULL 预览等待超时，普通值提交与服务器核对此前已通过。补充 NULL 勾选 / 待提交文字断言，在编辑框消失后等待软键盘收起，再点击可见预览按钮并等待实际确认弹窗；保留原服务器 NULL / 只读断言完整重跑。应用 APK 未变，原失败保存为 `sqlserver-api30-null-preview.txt`。
- 选中整段 SQL 按 Tab 时，Android 输入框默认插入制表符替换全部文字；拦截该按键并保留内容，新增真实硬件 Tab 回归。
- 升级校验用例会清理其合成状态，不能不重新准备旧版而单独重跑；完整旧版 install / seed / install-r 流程通过后才计数。
- API30 在资源竞争下冷启动出现 system_server / NetworkStack 失败，发生于 app 测试前；保留系统证据，停止其它模拟器与构建后重新启动，确认 package / activity / window 服务可用后验收。后续单独启动仍有 SystemUI / 输入法 ANR；从 UI 层级确认系统的 Wait 控件后选择等待恢复，并保存 `api30-systemui-wait.xml`。没有禁用系统保护、放宽 TLS 或删除失败断言。

## 交付边界

归档 `versions/V0.10.0/` 与 `versions/V0.10.0.zip` 包含已提交完整源码、APK、测试 APK、报告、驱动、manifest 和逐文件校验；实际 source SHA 与 APK SHA 由归档脚本绑定。原签名 SHA-256 `1c0f10a1cc14cc44b1eefc18a78de23b34ce30761b70dae039bea7f302cc78db` 不变；历史版本不覆盖。

未验证：用户业务服务器、未知字段类型 / 其它 SQL Server 版本、真机 / arm64、真实 AI 供应商模型与计费、其它数据库引擎本轮完整实连矩阵、全引擎写入及完整 DBeaver 能力。外部 AI 可能产生服务商费用；需用户主动配置与授权，数据库基本功能不依赖 AI。

正式发行仍受 Release/R8、受保护的正式签名和真机验收阻塞。下一项最小任务是修复 Release 驱动兼容并完成 Android 11 真机 / arm64 验收；不能通过更改页面文字宣称通过。云启动草稿保存与环境快照发布、新任务恢复是不同事项；本轮未验证全新任务恢复。
