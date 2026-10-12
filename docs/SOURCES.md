# 工具链来源与查证记录

查证日期：2026-10-12。下面选择的是固定稳定兼容基线，不声称所有依赖为最新版本。

| 组件 | 实际固定版本 | 验证来源 |
|---|---|---|
| JDK | Eclipse Temurin 21.0.8+9 / Linux x86_64 | GitHub adoptium/temurin21-binaries 对应发行资产及官方 SHA-256 |
| Gradle | 8.13 | services.gradle.org 官方发行 ZIP 与 SHA-256；官方 Wrapper JAR SHA-256 另行核对 |
| AGP | 8.13.0 | Google Maven 官方 POM，实际插件配置与构建通过 |
| Kotlin / Compose compiler plugin | 2.2.10 | 同版本 Kotlin Gradle 与 Compose compiler 插件 |
| Compose BOM | 2025.08.01 | Google Maven 官方 BOM，具体依赖由版本目录与校验元数据固定 |
| SDK / Build tools | API 36 platform r2 / 36.0.0 | Google repository2-1.xml 和 sdkmanager 安装结果 |
| Command-line tools | 19.0 / build 13114758 | Google 官方 XML SHA-1 核对后额外记录 SHA-256 |
| Platform tools | 37.0.1 | Google 官方 SDK 源与安装元数据 |
| 编译输出 | Java/Kotlin bytecode 1.8，minSdk 26 | Gradle 配置和最终 APK badging |

官方 Gradle 8.13 兼容表确认 Java 21 从 Gradle 8.5 起可运行：
https://raw.githubusercontent.com/gradle/gradle/v8.13.0/platforms/documentation/docs/src/docs/userguide/releases/compatibility.adoc

Gradle 发行 ZIP SHA-256：`20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`。
Wrapper JAR SHA-256：`81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`。

AGP 官方兼容说明入口：
https://developer.android.com/build/releases/past-releases/agp-8-13-0-release-notes

该 HTML 页面在本次机器上 HTTP 403，未把它当作成功读取的证据。Google Maven 的 8.13.0 POM、插件自身兼容检查及实际 Gradle 8.13 / JDK 21 / API 36 编译是本次可验证依据。相同限制也影响 learn.microsoft.com 与 docs.gradle.org HTML；已将所需域名加入环境草稿，保存草稿并不会立即改变当前网络。

SQL Server 驱动官方标签变更记录已成功读取，包含 Android/Conscrypt 握手修复：
https://raw.githubusercontent.com/microsoft/mssql-jdbc/v13.6.0/CHANGELOG.md

Maven Central `mssql-jdbc` 与 `mariadb-java-client` 官方元数据确认 13.6.0.jre8 与 3.5.10 均为正式发布坐标。Java 发布声明、编译成功及这项修复都不代表本项目 Android 实连已通过。
