#!/usr/bin/env python3
"""Publish immutable, already-tested assets. Never rebuild, re-sign or replace assets."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

REPO = "chenwei666/datasail-android"
TAG = "V0.12.0"
SOURCE = "e59d8f482c456c56e856f691371d9ef2caef52da"
ASSETS = Path("release-upload/V0.12.0")
RECEIPT = Path("publication/V0.12.0.json")
TITLE = "数舟 DataSail V0.12.0 · 工作状态恢复与表格维护"
NOTES = '数舟 DataSail V0.12.0 / versionCode 18，工作状态恢复与表格维护。\n\n### 更方便的表格操作\n- 筛选条件可回填修改，支持追加多列排序、按单元格值筛选/排除；限制不适用的类型操作。\n- 全屏保留分页、刷新、编辑模式、新增/删除及提交预览；与普通视图共享选中单元格和布局。\n- 修复连续双指缩放累计不足，以及 MySQL/MariaDB BIGINT UNSIGNED 超过 Long 上限无法筛选。\n\n### 工作状态与错误恢复\n- 查询失败保留上次结果并标记旧数据，刷新成功前禁止写入及 AI 表操作；取消或失效的确认不能提交。\n- 旋转保留会话，真实后台仍断开。本次运行内按连接记忆草稿、表视图、列宽/隐藏列/缩放；显式重连恢复只读，不重放写入。\n- 表结构变化导致旧筛选失效时清理该表记忆，允许重新打开。SQL 结果与结构分离，查询后保留字段补全。\n- 新增每连接最近 30 条成功查询历史（含行数和耗时），可载入草稿或清空；仅进程内保存。\n\n### 安装与验证\n下载 `DataSail-V0.12.0-debug.apk` 覆盖安装，保留原包名和签名。\n164 项 JVM、34 项最终 APK Android 检查通过。API26 完整移动工作台和 SQL Server UI；API30 恢复/选中/缩放及 SQL Server 驱动；两者均验证 V0.11.0 覆盖升级和三协议合成 HTTPS AI。\n附件为已验收原件，GitHub 不重新编译或签名。ZIP 含源码、APK、报告和校验。manifest 的 published=false 是打包时状态，发布回执独立保存。\n\n### 限制\nRelease/R8 仍有 193 个缺失类，此为 Debug 预发布，尚未达到正式稳定发行门槛。未验证真机/arm64、用户业务数据库、真实 AI 供应商账号及计费；未重跑非 SQL 引擎全矩阵。写入仍限 SQL Server 单条预览确认；多标签、批量暂存、其它引擎写入、SSH/DDL 等未实现。进程退出后不恢复临时草稿和查询历史，不承诺零缺陷。\n详细命令、修复前失败和最终证据见 TEST_REPORT.md。\n\n源码提交：e59d8f482c456c56e856f691371d9ef2caef52da\nAPK SHA-256：0e14bc6882235af1e116069f8887e7a6f4d49e42671bfc218ba381ddd023c0ae\n'

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def gh(*args):
    result = subprocess.run(["gh", *args], text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError("GitHub command failed: " + result.stderr.strip()[:1500])
    return result.stdout

def api(path):
    return json.loads(gh("api", path))

def verify_local():
    expected = {}
    for line in (ASSETS / "SHA256SUMS.txt").read_text().splitlines():
        sha, name = line.split("  ", 1)
        if Path(name).name != name or name in expected:
            raise RuntimeError("Invalid checksum filename")
        if digest(ASSETS / name) != sha:
            raise RuntimeError("Local checksum mismatch: " + name)
        expected[name] = sha
    expected["SHA256SUMS.txt"] = digest(ASSETS / "SHA256SUMS.txt")
    if set(p.name for p in ASSETS.iterdir()) != set(expected):
        raise RuntimeError("Unexpected release payload")
    manifest = json.loads((ASSETS / "manifest.json").read_text())
    if manifest["gitSha"] != SOURCE or manifest["versionCode"] != 18:
        raise RuntimeError("Unexpected source version")
    if expected["DataSail-V0.12.0-debug.apk"] != "0e14bc6882235af1e116069f8887e7a6f4d49e42671bfc218ba381ddd023c0ae":
        raise RuntimeError("APK differs from device-tested binary")
    if expected["DataSail-V0.12.0.zip"] != "6c8c38db141ee7180d4e77fc2256994d72e8618c989539dc5062b6cefb9fdb54":
        raise RuntimeError("Immutable archive differs")
    return expected

def verify_remote(asset, expected, folder):
    name = asset["name"]
    if name not in expected or asset["state"] != "uploaded" or asset["size"] != (ASSETS / name).stat().st_size:
        raise RuntimeError("Unexpected remote asset: " + name)
    # Verify server bytes even when GitHub omits its digest field.
    dest = folder / name
    with dest.open("wb") as output:
        subprocess.run(["gh", "api", "-H", "Accept: application/octet-stream",
                        f"repos/{REPO}/releases/assets/{asset['id']}"], stdout=output, check=True)
    if digest(dest) != expected[name]:
        raise RuntimeError("Remote checksum mismatch: " + name)

def publish(expected):
    if os.environ.get("GITHUB_REPOSITORY") != REPO:
        raise RuntimeError("Publication must run in the expected GitHub repository")
    refs = api(f"repos/{REPO}/git/matching-refs/tags/{TAG}")
    if any(ref["ref"] == "refs/tags/" + TAG for ref in refs):
        if api(f"repos/{REPO}/commits/{TAG}")["sha"] != SOURCE:
            raise RuntimeError("Existing tag points to different source; refusing to overwrite it")
    # Use the REST list so a missing tag is distinct from authentication failure.
    releases = json.loads(gh("api", "--paginate", "--slurp", f"repos/{REPO}/releases?per_page=100"))
    matching = [r for page in releases for r in page if r["tag_name"] == TAG]
    if len(matching) > 1:
        raise RuntimeError("Duplicate release tag")
    with tempfile.TemporaryDirectory(prefix="datasail-publish-") as tmp:
        folder = Path(tmp)
        if not matching:
            payload = folder / "release.json"
            payload.write_text(json.dumps({"tag_name": TAG, "target_commitish": SOURCE,
                                           "name": TITLE, "body": NOTES, "draft": True, "prerelease": True}))
            # Use the creation response directly; a fresh draft need not appear
            # immediately in the paginated list.
            release = json.loads(gh("api", "--method", "POST", f"repos/{REPO}/releases", "--input", str(payload)))
        else:
            release = matching[0]
        if release["target_commitish"] != SOURCE or not release["prerelease"]:
            raise RuntimeError("Existing release has a different source or channel")
        uploaded = {a["name"]: a for a in release["assets"]}
        if not set(uploaded).issubset(expected):
            raise RuntimeError("Unexpected assets in existing release; nothing will be overwritten")
        for asset in uploaded.values():
            verify_remote(asset, expected, folder)
        for name in sorted(set(expected) - set(uploaded)):
            if not release["draft"]:
                raise RuntimeError("Published release is incomplete; refusing to modify it")
            gh("release", "upload", TAG, str(ASSETS / name), "--repo", REPO)
        release = api(f"repos/{REPO}/releases/{release['id']}")
        if {a["name"] for a in release["assets"]} != set(expected):
            raise RuntimeError("Release asset set does not match")
        for asset in release["assets"]:
            verify_remote(asset, expected, folder)
        if release["draft"]:
            gh("release", "edit", TAG, "--repo", REPO, "--draft=false", "--prerelease", "--latest=false")
        release = api(f"repos/{REPO}/releases/{release['id']}")
        if release["draft"] or not release["published_at"]:
            raise RuntimeError("Release publication was not confirmed")
        if api(f"repos/{REPO}/commits/{TAG}")["sha"] != SOURCE:
            raise RuntimeError("Published tag does not match the tested source")
        return {"status": "published", "tag": TAG, "sourceCommit": SOURCE,
                "url": release["html_url"], "publishedAt": release["published_at"],
                "prerelease": release["prerelease"], "runId": os.environ.get("GITHUB_RUN_ID"),
                "assets": [{"name": a["name"], "url": a["browser_download_url"],
                            "size": a["size"], "sha256": expected[a["name"]]} for a in release["assets"]]}

if __name__ == "__main__":
    try:
        checksums = verify_local()
        if "--verify-only" in sys.argv:
            print(f"Verified {len(checksums)} immutable release assets")
        else:
            result = publish(checksums)
            RECEIPT.parent.mkdir(exist_ok=True)
            RECEIPT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
            print(result["url"])
    except Exception as error:
        if "--verify-only" not in sys.argv:
            RECEIPT.parent.mkdir(exist_ok=True)
            RECEIPT.write_text(json.dumps({"status": "failed", "tag": TAG, "sourceCommit": SOURCE,
                                         "runId": os.environ.get("GITHUB_RUN_ID"), "error": str(error)}, ensure_ascii=False, indent=2) + "\n")
        raise
