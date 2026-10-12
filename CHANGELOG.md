# 更新说明

## V0.1.0 — 2026-10-12（versionCode 1，技术验证交付）

- 从 README-only 仓库建立 Kotlin / Compose / ViewModel 原生工程，最低 API26，中文显示名称“数舟”。
- 增加 SQL Server 优先、MySQL 次优先的驱动验证表单和固定合成只读探针；未实现入口禁用。
- 建立类型模型、能力边界、凭据隔离、强制 TLS、错误脱敏、有界执行、取消联动和结果未知状态；AI 只保留独立草稿接口。
- MySQL 使用严格证书插件；覆盖错误主机名、不受信 CA、错误账号、Unicode/NULL/精确小数、查询超时及取消的隔离 JVM 实连回归。
- 提供锁定工具链安装脚本、官方 Wrapper/依赖校验元数据、单元测试、仪器测试源码、GitHub Actions 和不可覆盖版本归档脚本。
- 交付真实本地 Debug APK 及完整源码归档；未公开发布，未使用正式签名。

明确限制：Android 设备测试未执行，SQL Server 镜像拉取受网络策略阻止，Release/R8 失败且没有发布 APK。技术验证交付不代表全部验收通过，详见 TEST_REPORT 与 KNOWN_ISSUES。
