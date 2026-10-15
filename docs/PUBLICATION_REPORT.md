# GitHub 发布校验 / Publication verification

以下为 2026-10-15 (Asia/Bangkok) 完成的 V0.1.0–V0.13.0 历史发布记录。V0.14.0 正式签名与混淆构建另见 [本版说明](releases/V0.14.0.md) 和 [测试报告](TEST_REPORT.md)。

The record below covers historical releases V0.1.0–V0.13.0. V0.14.0 production signing and minification have separate release notes and verification evidence; the earlier R8 failure below is a historical result.

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

## V0.14.0 正式发布 / Production release

- 发布地址 / Release: https://github.com/chenwei666/datasail-android/releases/tag/V0.14.0
- 实际发布时间 / Published: `2026-10-15T07:27:39Z`
- 版本与构建号 / Version and code: `0.14.0 / 22`
- 源码提交 / Source commit: `ebb4f466a4cb2f1190252a4ac8d681a1a025f947`
- 自动发布运行 / Workflow: https://github.com/chenwei666/datasail-android/actions/runs/38698829841
- 正式签名、不可调试、R8 混淆和资源收缩；非预发布，已设置为最新版本。七个附件均在发布后不携带认证重新下载，并核对完整 SHA-256 与大小。
- Production-signed, non-debuggable, R8-minified release with resource shrinking, published as stable/latest. All seven assets were downloaded again without authentication and verified by full SHA-256 and size.
- 公开下载核对原始记录 / Public-download receipt: [production-20261015.json](releases/production-20261015.json)。
- 原有 21 个历史版本及其附件保持原样；正式签名私钥和密码未上传。旧 Debug 版无法直接覆盖，安装迁移和实测边界见 [TEST_REPORT.md](TEST_REPORT.md)。

首次发布尝试因 Git 自动转换许可证附件换行符而被完整性校验拦截，尚未创建正式 Release。附件分支已设置保留原始字节，重试通过后才公开发布。原失败记录见 [运行 38698673667](https://github.com/chenwei666/datasail-android/actions/runs/38698673667)。

V0.14.0 不可变源码快照中的 SLF4J 许可证采用 Git 规范化的 LF 换行，许可文字未改变；许可证来源清单的 SHA-256 指原始上游字节。ZIP 内部文件以其 `SHA256SUMS` 核对，公开 `THIRD_PARTY_NOTICES.txt` 附件保留原始换行。后续主线已固定许可证原始字节，不修改既有 ZIP。
