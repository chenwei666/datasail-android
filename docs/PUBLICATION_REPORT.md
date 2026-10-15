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

最终 GitHub 回执在完成后记录于 `docs/releases/publication-20261015.json`，包含实际可见性、发布状态、来源提交和逐附件校验。仓库是否公开以该回执为准，不能从上传成功推断。

The final GitHub receipt is recorded in `docs/releases/publication-20261015.json` after completion. It records actual visibility, release state, source commits and asset checksums. Successful uploads alone do not establish public access.

公开操作已获用户授权；实际 GitHub API 返回 `403 Resource not accessible by integration`。当前 Actions 集成缺少仓库管理权限，需仓库管理员在 Settings → General → Danger Zone → Change repository visibility 中设为 Public。无需向聊天发送令牌。最终可见性仍需远端复核。

The user authorized public visibility, but GitHub returned `403 Resource not accessible by integration`. The Actions integration cannot administer repository visibility. A repository administrator must use Settings → General → Danger Zone → Change repository visibility → Public. No token should be shared in chat. Final visibility requires a remote check.
