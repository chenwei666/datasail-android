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
TAG = "V0.12.1"
SOURCE = "1181d5a5cdc6f8d32bdf51fee5c170ccff861b95"
ASSETS = Path("release-upload/V0.12.1")
RECEIPT = Path("publication/V0.12.1.json")
TITLE = "数舟 DataSail V0.12.1 · 浮点表编辑与 AI 连续对话"
NOTES = '数舟 DataSail V0.12.1 / versionCode 19，SQL Server 浮点表编辑与 AI 连续对话。\n\n### 修改内容\n- 修复无 rowversion 的 SQL Server float/real 表不能编辑；按字段类型绑定与比较原值，保留并发冲突检查。全屏和普通表顶部显示具体只读原因。\n- AI 助手默认打开聊天，可直接发送、继续追问、取消和清空。设置中统一配置模型；不自动附加数据库内容。最多保留 8 轮、发送 24000 字符的完整对话，服务/模型变化后清空历史。\n- SQL 草稿与操作当前表保持独立，数据库写入仍需预览确认。\n\n### 安装与实际验证\n下载 `DataSail-V0.12.1-debug.apk` 覆盖安装，保留原包名和签名。\n145 项 JVM、19 项最终 APK Android 检查通过。Android 8/API26 模拟器验证 V0.12.0 覆盖升级、实际 SQL Server 浮点编辑与全屏流程、三协议合成 HTTPS 多轮聊天。Android 11/API30 模拟器两次启动不稳定，旧包安装和应用验证被环境阻断，未计作通过。未访问用户业务数据库。\n附件为已验收原件，GitHub 不重新编译或签名。ZIP 含源码、APK、报告和校验；manifest 的 published=false 为归档时状态，发布回执独立记录。\n\n### 限制\nRelease/R8 仍有 193 个缺失类，此为 Debug 预发布。未验证真机/arm64、真实 AI 服务商账号和计费，未重跑其它引擎完整矩阵；不能承诺零缺陷或 DBeaver 全功能。无主键、触发器及不完整大字段等编辑保护保留。聊天暂不支持持久化历史、流式输出和附件。\n\n源码提交：1181d5a5cdc6f8d32bdf51fee5c170ccff861b95\nAPK SHA-256：3c22699e83479f8670f6ec0a7d13d1fe269eecdcb5c43e9a78535b570351dd9c\n'

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
    if manifest["gitSha"] != SOURCE or manifest["versionCode"] != 19:
        raise RuntimeError("Unexpected source version")
    if expected["DataSail-V0.12.1-debug.apk"] != "3c22699e83479f8670f6ec0a7d13d1fe269eecdcb5c43e9a78535b570351dd9c":
        raise RuntimeError("APK differs from device-tested binary")
    if expected["DataSail-V0.12.1.zip"] != "4b2f701f36d36de55306b2c54ae53f321cde1e04dae033eb9a7091884cf61583":
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
