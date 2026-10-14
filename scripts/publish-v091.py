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
TAG = "V0.9.1"
SOURCE = "f0a674b6ac4963f08c97be1acf7cea9e92174671"
ASSETS = Path("release-upload/V0.9.1")
RECEIPT = Path("publication/V0.9.1.json")
TITLE = "数舟 DataSail V0.9.1 · 日期表修复与独立 AI 设置（测试版）"
NOTES = '数舟 DataSail V0.9.1 / versionCode 15，Debug 预发布修复更新。\n\n### 修复查看表后断连\n- SQL Server 连接和列出数据库正常，但查看含日期字段的表可能报“不支持此能力”并断开连接。\n- 在 Android 上复现驱动调用不存在的 Timestamp.valueOf(LocalDateTime)，源码适配改用 Android 支持的方法。验证 date、time、datetime、smalldatetime、datetime2、datetimeoffset 和 NULL，保留时间精度与时区。\n- 真实 SQL Server 合成库验证连接、切库、查看日期表，以及原有收藏、单元格预览提交与取消；不降低证书验证，也不自动执行或重试写入。\n\n### AI 设置与字段浏览\n- AI 地址、协议、密钥、模型获取与选择、启用开关统一放在「设置 → AI 服务与模型」。助手页专注提问、预览和 SQL 草稿。\n- 表结构支持字段名 / 类型搜索、仅看主键、复制字段名，并展示字段属性。\n- 保留原连接、数据库密码、证书、收藏、显示设置及 AI 服务 / 加密密钥。\n\n### 下载与验证\n下载 `DataSail-V0.9.1-debug.apk` 直接覆盖安装，签名与 V0.9.0 相同，无需卸载。\n116 项 JVM、28 项最终 APK 的 Android 检查通过；Android 8/API26 和 Android 11/API30 模拟器分别验证 V0.9.0 覆盖升级、设置与字段浏览、三协议合成 HTTPS AI 和真实 SQL Server。\n附件为实际验收原件，GitHub 不重新编译或签名。ZIP 包含完整源码、APK、文档与校验清单；manifest 的 published=false 是打包时状态，发布回执单独保存。\n\n### 限制\n没有访问用户业务服务器，未进行真机 / arm64 或真实 AI 供应商账号计费验证；其它数据库引擎本补丁未重跑。AI 仍是三种文本协议，预设不代表全部模型实测。日期读取修复不开放原先禁止的日期写入。\nRelease/R8 仍有 193 个缺失类；这是 Debug 测试版，不能作为正式生产版或零缺陷声明。\n\n源码提交：f0a674b6ac4963f08c97be1acf7cea9e92174671\nAPK SHA-256：36280880c9e0f4d76079c856c3c9d3568b1b3cfd20fdf7d091f526255cd43d8b\n'

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
    if manifest["gitSha"] != SOURCE or manifest["versionCode"] != 15:
        raise RuntimeError("Unexpected source version")
    if expected["DataSail-V0.9.1-debug.apk"] != "36280880c9e0f4d76079c856c3c9d3568b1b3cfd20fdf7d091f526255cd43d8b":
        raise RuntimeError("APK differs from device-tested binary")
    if expected["DataSail-V0.9.1.zip"] != "20fdc23cf808fed02d1060a776e289210373bd03c90772b232e05dbdd090ee51":
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
