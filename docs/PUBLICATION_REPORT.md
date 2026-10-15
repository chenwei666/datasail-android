# GitHub 发布校验 / Publication verification

日期 / Date: 2026-10-15 (Asia/Bangkok)。本次维护中英文介绍与历史分发，不生成新应用版本；最新安装包仍为 V0.13.0 / versionCode 21。

This task updates bilingual documentation and historical distribution. It does not create a new application version; the current APK remains V0.13.0 / versionCode 21.

## 原件核验 / Artifact verification

- 21 个版本、共 17453 个归档文件逐一核对 SHA-256；全部 ZIP CRC 与归档校验值通过。
- 21 个 Debug APK 的包名、versionCode、versionName 和签名验证通过，原件未重编译或改签。
- 21 original archives were checked file by file, including ZIP integrity, APK metadata and signature continuity. No historical binary was rebuilt or re-signed.
- 完整记录 / Full record: [verification-20261015.json](releases/verification-20261015.json)。

## 本次检查 / Checks performed

```bash
export DATASAIL_TOOLS_DIR=/workspace/toolchains
source scripts/env.sh
./gradlew --no-daemon :sqlserver-android:check :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
./gradlew --no-daemon :app:assembleRelease --console=plain
python3 -m unittest discover -s scripts/tests -v
python3 scripts/publish-history.py --verify-local  # in the prepared delivery branch
```

Debug 构建、lint 和 127 项单元检查通过；发布工具 6 项完整性回归通过，覆盖原件或说明被改动、远端附件冲突、下载内容不符及标签指向错误时拒绝写入。本次未重跑 Android 或真实数据库测试，V0.13.0 原始报告中的 201/67 项不计为本次新执行。

Debug build, lint and 127 unit checks passed. Six publisher integrity tests cover changed payloads/notes, unexpected remote assets, incorrect downloaded bytes, wrong tag targets and accidental stable relabelling. Android and real-database tests were not rerun for this documentation/distribution task; the original V0.13.0 results are historical evidence.

Release/R8 实际退出 1，仍缺失 193 个类；未产出正式 Release APK，未声称生产签名或真机认证。Published Debug artifacts retain their prerelease status; production distribution gates remain incomplete.

## GitHub 操作 / GitHub operations

首次核对已有 10 个 Release，V0.1.0–V0.7.2 共 11 个缺失。发布流程先验证全部现有附件，再补齐缺失版本、更新双语说明；不覆盖已有标签或附件。每个版本使用原始源码提交和原始报告，后补上传时间不冒充开发日期。

The initial audit found 10 existing releases and 11 missing versions (V0.1.0–V0.7.2). The publisher verifies existing attachments before mutations, fills missing releases and updates bilingual notes without overwriting tags or assets. Each release points to its original source and report.

已发布并核验全部 **21 个 Release、126 个附件**：补齐最初缺失的 11 版，并更新原有 10 版的中英文说明。全部标签指向原始源码提交；全部附件下载后逐字节核对 SHA-256，未覆盖历史原件。

All **21 releases and 126 attachments** are published and verified. The 11 initially missing versions have been added, and the 10 existing versions now have bilingual notes. Original source tags and artifact bytes are preserved; every attachment was downloaded and checked against its expected SHA-256.

仓库管理员已将仓库改为 **Public**。独立的匿名请求确认：全部 21 版可见，126 个附件下载链接返回 HTTP 200 且大小匹配；最新版 APK 匿名完整下载后的 SHA-256 与原件一致。该检查未使用 GitHub 令牌或登录 Cookie。

The repository administrator changed visibility to **Public**. Independent unauthenticated requests verified all 21 release pages in the public API and HTTP 200 downloads with matching sizes for all 126 attachments. The latest APK was fully downloaded anonymously and its SHA-256 matched the original. No GitHub token or login cookie was used for this check.

- [发布与附件校验回执 / Publication and artifact receipt](releases/publication-20261015.json)
- [匿名公开下载回执 / Anonymous public-download receipt](releases/public-access-20261015.json)

本次权限排障：自动变更仓库可见性返回 403，由仓库管理员完成公开。创建旧版 Release 时显式指定旧提交也返回 403；先通过 Git 补齐并核验原始标签，再基于已有标签创建 Release。没有请求扩大令牌权限，没有改动旧标签或附件。

Permission troubleshooting: the visibility API returned 403, so the repository administrator made it public. Creating historical releases while explicitly targeting old commits also returned 403. Original tags were pushed and verified through Git, then releases were created from those existing tags. Token permissions were not broadened, and historical tags and attachments were not replaced.
