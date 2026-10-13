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
TAG = "V0.8.0"
SOURCE = "86877a47dedd709446550077f2e862bce813e7f8"
ASSETS = Path("release-upload/V0.8.0")
RECEIPT = Path("publication/V0.8.0.json")
TITLE = "数舟 DataSail V0.8.0 · 密码保存、表格编辑与 SQL 补全（测试版）"
NOTES = """数舟 DataSail V0.8.0 / versionCode 12，实验性 Debug 预发布版。

### 本次更新
- 连接页使用紧凑数据库列表，支持 Android Keystore 加密保存密码、自动填入及忘记密码。
- SQL 结果表格支持行列标识、单元格复制与原位编辑、NULL、预览确认和提交后刷新。
- SQL 编辑器支持键盘 Tab、屏幕补全按钮和已加载的表名、字段候选。
- 设置支持主题、字号、行高、列类型、补全开关和密码管理，并保存到本机。
- 修复重复断开清理占满队列导致手动重连被拒绝的问题。

### 下载与升级
安装 `DataSail-V0.8.0-debug.apk`。这是已在云端验证的原始 APK，保持原包名及 Debug 签名，没有在 GitHub 重新签名。已验证从 V0.7.2 覆盖安装并保留连接、收藏和证书信任。
`DataSail-V0.8.0.zip` 含完整源码、安装包、文档和逐文件校验。使用 `SHA256SUMS.txt` 核对附件。归档内 manifest/报告的“未发布”字段记录打包时状态，保留原始快照。

### 验证与限制
构建和 lint 通过；95 项 JVM、39 项 Android 检查通过，设备为 Android 8/API26 和 Android 11/API30 x86_64 模拟器。真实 SQL Server 验证了密码复用登录、Tab 补全、文本/NULL 提交、刷新及证书流程。
图形写入限 SQL Server 测试连接中的安全可定位表；其他引擎保持只读。尚无多格粘贴、批量编辑和完整 DBeaver 功能。未验证真机、arm64 或用户服务器。
Release/R8 仍有与上一版相同的 193 个缺失类，未交付混淆 Release APK。`com.example.datasail` 为开发占位包名，本次不是稳定正式发行。

源码提交：86877a47dedd709446550077f2e862bce813e7f8
APK SHA-256：b90e3dba9afdfc882abe6542ec711f585baaa4a2d447953720f19e10802267ec
"""

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
    if manifest["gitSha"] != SOURCE or manifest["versionCode"] != 12:
        raise RuntimeError("Unexpected source version")
    if expected["DataSail-V0.8.0-debug.apk"] != "b90e3dba9afdfc882abe6542ec711f585baaa4a2d447953720f19e10802267ec":
        raise RuntimeError("APK differs from device-tested binary")
    if expected["DataSail-V0.8.0.zip"] != "a52b57e70ae802998ad7f09aea97ed940cc28fc7f2b08859c69f825228ea5a10":
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
