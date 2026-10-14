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
TAG = "V0.12.2"
SOURCE = "93656cadfa7edf7250e85778613e9302f896b25f"
ASSETS = Path("release-upload/V0.12.2")
RECEIPT = Path("publication/V0.12.2.json")
TITLE = "数舟 DataSail V0.12.2 · 大文本表编辑修复"
NOTES = '数舟 DataSail V0.12.2 / versionCode 20，SQL Server 大文本声明导致整表只读的修复。\n\n### 修改内容\n- 修复含 nvarchar(max)、varchar(max)、varchar(8000) 且无 rowversion 的表无法编辑：声明容量不再锁住整表，NULL 和完整加载的短值允许安全比较，无需改表结构。\n- MAX 原值比较使用正确的 SQL 类型转换、长度与字节比较，保留 NULL/空串、大小写、尾空格、尾零及并发冲突检查。\n- SQL Server 单字段文本读取和输入上限统一为 4000 个 UTF-16 单元；真正超限或超页预算的行仍禁止修改/删除，界面列出未完整加载的字段名，其它完整行仍能编辑。\n\n### 安装与实际验证\n下载 `DataSail-V0.12.2-debug.apk` 覆盖安装，保留原包名和签名。重新连接目标表，点击「编辑模式」并确认，再双击非主键字段修改、预览和确认提交。\n148 项 JVM、10 项最终 APK Android 检查通过。Android 8/API26 模拟器验证 V0.12.1 覆盖升级、实际 SQL Server MAX 文本及并发检查、普通/全屏触摸修改。先加入回归测试，在旧代码上复现同一只读原因，再验证修复。未访问用户业务数据库。\n附件为已验收原件，GitHub 不重新编译或签名。ZIP 含源码、APK、报告和校验；manifest 的 published=false 为归档时状态，发布回执独立记录。\n\n### 限制\nRelease/R8 仍有 193 个缺失类，此为 Debug 预发布。Android 11/API30 当前包未重跑（上一轮模拟器系统服务故障），未验证真机/arm64、用户业务库，未重跑其它引擎或 AI 网络完整矩阵；不能承诺零缺陷或 DBeaver 全功能。无主键、触发器、不支持类型和实际不完整行的保护继续生效。\n\n源码提交：93656cadfa7edf7250e85778613e9302f896b25f\nAPK SHA-256：4ae5cdd40b72366fc44005ba522ceebb5b735d0770d6513e33b81c2faed56a96\n'

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
    if manifest["gitSha"] != SOURCE or manifest["versionCode"] != 20:
        raise RuntimeError("Unexpected source version")
    if expected["DataSail-V0.12.2-debug.apk"] != "4ae5cdd40b72366fc44005ba522ceebb5b735d0770d6513e33b81c2faed56a96":
        raise RuntimeError("APK differs from device-tested binary")
    if expected["DataSail-V0.12.2.zip"] != "219fe368ec94ad14a470654edcec77543a722079fae2bc5e4772efe295e9df74":
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
