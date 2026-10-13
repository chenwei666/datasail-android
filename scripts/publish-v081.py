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
TAG = "V0.8.1"
SOURCE = "a7348aeac80e17bc893dccf32a0b9cb07df15cba"
ASSETS = Path("release-upload/V0.8.1")
RECEIPT = Path("publication/V0.8.1.json")
TITLE = "数舟 DataSail V0.8.1 · 导航修复、表收藏与快捷连接（测试版）"
NOTES = '数舟 DataSail V0.8.1 / versionCode 13，Debug 预发布更新。\n\n### 本次修复与新增\n- 修复连接成功后不自动加载 Schema 和表的问题；对象直接显示，可筛选、切库、切 Schema、刷新，空列表与失败分别提示。\n- 首页历史连接支持使用已存加密密码一键进入工作台；未保存密码时进入配置填写。\n- 新增表收藏，绑定原连接、数据库、Schema 和对象名称；重新打开时校验元数据，支持返回原库，不会自动启用写入。\n- 修复查询诊断取消与清理并发关闭同一连接的竞态；保留未知结果提示，不重放查询。\n\n### 安装与验证\n下载 `DataSail-V0.8.1-debug.apk` 覆盖安装。保持原包名和签名；Android 8/API26 与 Android 11/API30 模拟器已验证从 V0.8.0 升级，保留连接、查询收藏、证书信任、加密密码和设置。\n102 项 JVM、43 项当前 APK 的 Android 检查通过（另有2项旧版升级状态准备检查）；真实 SQL Server 实际点击验证历史登录、库/Schema/表导航、跨库表收藏、单元格预览确认、取消与证书流程。APK和ZIP是云端已验证原件，GitHub不重新编译或签名。\nZIP含完整源码/安装包/文档/校验清单；manifest中published=false记录打包时状态，发布回执另存。\n\n### 正式发行限制\n这不是正式稳定版，不能承诺没有bug。Release/R8仍有193个缺失类，未通过混淆发行构建；真机、arm64、用户业务服务器以及其它引擎新界面的完整实连矩阵未验证。生产连接保持只读，图形写入仍限SQL Server测试会话中的安全可定位表。表收藏限SQL表/视图；PostgreSQL一连接一库。\n\n源码提交：a7348aeac80e17bc893dccf32a0b9cb07df15cba\nAPK SHA-256：41b2060a94f861d095cc2f6b7bdb8ce66c356a49f51a81269cd524160f01b471\n'

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
    if manifest["gitSha"] != SOURCE or manifest["versionCode"] != 13:
        raise RuntimeError("Unexpected source version")
    if expected["DataSail-V0.8.1-debug.apk"] != "41b2060a94f861d095cc2f6b7bdb8ce66c356a49f51a81269cd524160f01b471":
        raise RuntimeError("APK differs from device-tested binary")
    if expected["DataSail-V0.8.1.zip"] != "e68da5c36a0e782b6e24ea01e280d272efbc75aafe8885b1325929f5bd0bcf20":
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
