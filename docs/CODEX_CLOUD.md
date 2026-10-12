# 可复用云环境

现有检出目录：`/workspace/datasail-android`。每个云任务已隔离，沿用现有检出；除非用户明确要求，不创建 Git worktree。

```bash
cd /workspace/datasail-android
export DATASAIL_TOOLS_DIR=/workspace/toolchains
bash scripts/setup-cloud.sh
source scripts/env.sh
./gradlew :sqlserver-android:check :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

安装器提供 Temurin JDK、Android command-line tools/SDK 与 ADB；Gradle 由仓库 Wrapper 提供。工具与缓存位于仓库之外，脚本不会写入源码或锁文件。开发任务主动更新依赖时须审查版本目录与 verification-metadata；正常构建不加 `--write-verification-metadata`。

`scripts/env.sh` 设置 Java/SDK/Gradle 路径及可写 Android 缓存。如果平台提供 HTTPS_PROXY，它为 Java 设置对应代理。平台注入的 `CODEX_PROXY_CERT` 仅作为额外受信任根导入独立本地 truststore，保留发行 JDK CA；不禁用 TLS、包校验或覆盖全局 JDK 配置。不存在平台变量时使用 JDK 默认信任。

ADB 本身仍需要用户主目录中的 `.android` 目录；本次已创建，不含正式签名材料。Debug 自动签名与测试 CA 不是正式发行签名。无需业务密码、模型密钥或额外 GitHub Token；现有平台 Git 读取正常。

运行应用需要 Android 设备或模拟器，Android 11 是首要目标。本机无 `/dev/kvm`，本轮安装 API30 x86_64 系统镜像并用软件模拟启动，已执行 SQL Server Android 实连。模拟器是临时运行进程，恢复环境后需重新启动。核心开发/构建无需常驻服务器。

仅在运行实连测试时启动 `scripts/test-mysql-local.sh` 、`scripts/test-sqlserver-local.sh` 或 `scripts/test-jdbc-workspace-local.sh MYSQL|MARIADB|POSTGRESQL`；需要 Docker 与 openssl，脚本建立只绑定 loopback 的临时数据库 并自动停止。不要把该进程视为随快照持久化。应用 TLS 使用系统或连接明确选择的 CA；云端 Java 下载代理信任配置不打包进 APK。

网络需求：Google SDK/Maven、Maven Central、Gradle/GitHub 发行资产已通过已有 package_managers 预设访问。官方说明页面所需 `developer.android.com`、`learn.microsoft.com`、`docs.gradle.org`、`mariadb.com` 以及 SQL Server 镜像实际重定向域名 `centralus.data.mcr.microsoft.com` 已记录为环境草稿新增域名；保存草稿不等于当前网络已生效。

环境设置中的 install_script 对应安装和 Debug 验证命令；start_skill 对应环境激活、复核与可选测试。用户需要在环境设置审阅保存后发布环境，才会形成供新任务使用的配置/文件快照。本次不宣称已发布，也没有验证全新云任务的快照恢复。
