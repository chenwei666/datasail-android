# 数舟 · DataSail

**数舟：AI 多数据库管理工具。把数据库查询、表格编辑和 AI 问答带到 Android。**

**简体中文** · [English](README.en.md) · [下载安装](https://github.com/chenwei666/datasail-android/releases/tag/V0.14.0) · [全部版本历史](docs/VERSION_HISTORY.md) · [使用说明](docs/WORKSPACE.md)

DataSail 是中文原生 Android 数据库客户端，采用 Kotlin、Jetpack Compose 与 ViewModel，在手机上直接连接数据库。不需要 Root、Termux、网页套壳或强制中转服务；关闭 AI 后仍可使用数据库功能。

> 当前版本 **V0.14.0 / versionCode 22**。APK 为 **独立正式签名、不可调试的混淆 Release**。功能、实际测试和限制分别列出；真机、arm64 与其它服务端版本仍需独立验收。

## 能做什么

- **连接和发现**：先连接服务器，扫描账号可见的数据库或索引，再搜索选择；支持历史连接、表收藏、可选加密保存密码。
- **像表格一样查看数据**：列头筛选排序、服务器端分页、全屏、双指缩放、列宽与显隐、整行详情。可编辑字段双击打开草稿，预览并确认后提交。
- **查询与 SQL 编写**：独立 SQL 编辑器、关键字和已加载字段补全、硬件 Tab、SQL 收藏与查询历史；可选 AI 建议手动接受。
- **多种数据库工作区**：关系型表、Redis/Valkey 键、MongoDB 文档、Elasticsearch/OpenSearch 索引使用各自的浏览与受限编辑流程。
- **AI 问答与操作**：「问答 / 写 SQL」和「操作当前表」两种模式，均能连续问答。模型服务统一在设置配置，填入 Key 即可获取上游模型。
- **本机上下文记忆**：AES-GCM 加密保存，按服务、模型、连接、数据库、表和模式隔离；最近 16 轮保存在本机，每次最多发送最近 8 轮，可清空。没有跨设备云同步。
- **结果导出**：通过系统文件选择器导出完整加载的当前页 CSV；当前页导出不是整库备份。

## 数据库支持

| 数据库 | 浏览与查询 | 当前编辑范围 |
|---|---|---|
| SQL Server | 库、Schema、表/视图、分页和 SELECT | 有稳定主键、完整可比较行的单条增改删；AI 删除当前表需另外输入完整表名确认 |
| MySQL / MariaDB | 库、表/视图、分页和 SELECT | InnoDB、稳定主键、可检查触发器状态、完整原行的单条写入 |
| PostgreSQL | Schema、表/视图、分页和 SELECT | 稳定主键与完整可比较原行的单条写入 |
| SQLite | 用户选择的一致性文件私有副本 | 修改应用内副本；不自动写回源文件，暂未提供修改后副本导出 |
| Redis / Valkey | SCAN、六类键、TTL、预览 | 完整 UTF-8 字符串增改删，保留 TTL，WATCH 冲突检测 |
| MongoDB | 集合、受限 JSON 筛选、文档分页 | 有界完整文档增改删，保留 `_id`，检查原文及 BSON 类型 |
| Elasticsearch / OpenSearch | 索引、映射、受限 DSL、分页 | 单文档增改删，通过 `_seq_no` / `_primary_term` 检查并发变化 |

共 **10 种已接入引擎**。视图、无主键、复杂类型、触发器、超限或不完整行等仍有限制。Oracle、Db2、ClickHouse 等在规划中；不声称所有数据库、所有版本或 DBeaver 全功能均已覆盖。详见 [能力矩阵](docs/DRIVER_MATRIX.md) 与 [接入规划](docs/DATABASE_SCOPE.md)。

## AI 服务

支持 OpenAI 兼容 Chat Completions、Anthropic Messages 和 Gemini generateContent 三类文本协议，并支持自定义 HTTPS 地址。预设包括 OpenAI、Claude、Gemini、DeepSeek、通义千问、豆包、智谱、Kimi、MiniMax、硅基流动、百度千帆、腾讯混元、Grok、OpenRouter、Groq、Mistral、Together、NVIDIA NIM、Perplexity。

预设不等于逐家实测；可用模型、地区与地址取决于服务商账号。暂不支持云 IAM、Azure 专有认证、Responses-only、图片/音频及流式输出。AI 费用由所选服务商收取，应用不内置公共密钥。

在 **设置 → AI 服务与模型** 选择服务、填写地址和 Key、点击「获取上游模型」、选择模型并启用 AI。助手默认不上传结果行；发送表结构或 SQL 前可预览和脱敏。模型只能提出本地校验的受限操作，写入和整表删除必须确认；旧对话不会自动重放操作。[AI 使用与记忆说明](docs/AI_ASSISTANT.md)

## 安装与开始使用

1. 在 [V0.14.0 发布页](https://github.com/chenwei666/datasail-android/releases/tag/V0.14.0) 下载 `DataSail-V0.14.0-release.apk`，可使用附件校验文件核对 SHA-256。
2. 安装 APK。当前 `minSdk` 为 Android 8/API26；本版分别使用 API26 与 API30 x86_64 模拟器验证，具体范围见测试报告；真机/arm64 尚未验收。
3. 添加你有权访问的连接，填写服务器、账号和 TLS 设置，扫描并选择数据库。手机须能通过已有可信网络访问服务器。
4. 打开表进行筛选、查询或编辑；编辑写入前会显示目标和变更预览。

**签名变更：V0.13.0 及之前使用 Debug 证书，不能直接覆盖安装本版。** 请先记录需要保留的连接、收藏、SQL 和设置，再决定是否卸载；卸载会删除本地数据及密钥，目前没有完整迁移工具。后续正式版沿用本次证书。详见 [正式构建与迁移说明](docs/RELEASE_BUILD.md) 和 [全部版本历史](docs/VERSION_HISTORY.md)。

IP 地址或自签名证书连接，可在证书设置中获取服务器证书，向管理员核对 SHA-256 后按连接确认；不会静默忽略证书或退回明文。[证书连接说明](docs/CERTIFICATE_TRUST.md)

## 数据与隐私

凭据与 AI 记忆由 Android Keystore/AES-GCM 保护，保存在本机且排除系统备份。AI 为可关闭功能，每个连接可禁止外部 AI。默认 TLS 身份校验、参数绑定、完整原值或版本检查、单次确认及未知提交不重试，帮助避免误覆盖；这些机制不能替代服务器权限与业务备份。卸载应用或丢失系统密钥可能导致凭据和记忆不可恢复。

## 验证与发行状态

本版完成 SQL Server、PostgreSQL、MariaDB/MySQL 与 MongoDB 的 Android 驱动源码适配；正常 R8 构建通过，不压制缺失类警告。最终 DEX 检查原 193 类缺失引用，并将设备测试记录绑定最终 APK 的 SHA-256。

JVM、Android、TLS 正负例、数据库读写、界面与 AI 的实际结果分别记录；不把编译成功或 JVM 成功当作 Android 兼容结论。未访问用户业务数据库，未使用真实计费 AI 账号。请查看 [本版测试报告](docs/TEST_REPORT.md)、[已知限制](docs/KNOWN_ISSUES.md)、[驱动适配说明](docs/DRIVER_RELEASE_PLAN.md) 和 [历史发布校验](docs/PUBLICATION_REPORT.md)。

## 开发与开源

```bash
bash scripts/setup-cloud.sh
source scripts/env.sh
./gradlew :sqlserver-android:check :postgresql-android:check :mariadb-android:check :mongodb-core-android:check :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

本云环境先执行 `export DATASAIL_TOOLS_DIR=/workspace/toolchains`。固定工具链、隔离数据库测试及 Android 回归步骤见 [TESTING](docs/TESTING.md)。

[工作台说明](docs/WORKSPACE.md) · [中英文版本历史](docs/VERSION_HISTORY.md) · [架构](docs/ARCHITECTURE.md) · [路线图](docs/ROADMAP.md) · [问题反馈](https://github.com/chenwei666/datasail-android/issues)

自有代码采用 [Apache-2.0](LICENSE)，第三方驱动与源码许可见 [THIRD_PARTY](docs/THIRD_PARTY.md)。应用标识沿用 `com.example.datasail`；本版首次使用独立正式签名，后续正式版本须复用该证书。历史 Debug 安装的迁移限制见上文。
