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
TAG = "V0.11.0"
SOURCE = "88cb0bbb4fd9030acf7a6035d586f4221db760c0"
ASSETS = Path("release-upload/V0.11.0")
RECEIPT = Path("publication/V0.11.0.json")
TITLE = "数舟 DataSail V0.11.0 · 筛选排序与 AI 表操作"
NOTES = '数舟 DataSail V0.11.0 / versionCode 17，列头筛选排序与 AI 表操作。\n\n### 表格操作\n- 点击列头进行升序、降序和筛选，上方显示当前条件并支持清除。筛选覆盖整张表后再分页；刷新、翻页和提交后保留条件。\n- 支持 SQL Server、MySQL、MariaDB、PostgreSQL、SQLite 参数绑定筛选；多个条件为 AND，排序追加主键。修复 65% 缩放时文字裁切。\n- 修复 SQL Server time 筛选被 JDBC 转成 datetime 导致失败，按实际日期时间类型精确绑定。\n\n### AI 操作当前表\n- 助手选择“操作当前表”，用自然语言描述需求，预览并批准发送问题和表结构后查询；实际数据行留在本机。\n- 显式允许写入预览后，可为 SQL Server 新增、修改、删除一条记录；核对目标、主键及原值/新值，确认后才提交。零条或多条匹配拒绝，保留乐观并发和未知结果不重试。\n- 模型只生成受限 JSON，不允许任意 SQL、跨表、批量或 DDL。其它 SQL 引擎当前仅查询。配置仍统一在设置，原 SQL 草稿和实时建议保持手动执行。\n\n### 安装与验证\n下载 `DataSail-V0.11.0-debug.apk` 覆盖安装，无需卸载，保留原包名和签名。\n155 项 JVM、36 项最终 APK Android 检查通过：API26 / API30 模拟器、V0.10.0 覆盖升级、列头筛选/排序、缩放、三协议合成 HTTPS AI、真实 SQL Server 日期筛选和安全编辑。四种网络 SQL 引擎独立验证本轮 JVM 筛选排序。\n附件为已验收原件，不在 GitHub 重新编译或签名；ZIP 包含源码、APK、报告和校验。manifest 的 published=false 为打包状态，发布回执另存。\n\n### 限制\nRelease/R8 仍有 193 个缺失类，此为 Debug 预发布，尚未达到正式稳定发行门槛。未访问用户业务服务器，未验证真机/arm64、真实 AI 供应商账号及计费、其他引擎写入或完整 DBeaver 功能；不承诺零缺陷。详细命令、失败修复和证据见 TEST_REPORT.md。\n\n源码提交：88cb0bbb4fd9030acf7a6035d586f4221db760c0\nAPK SHA-256：7472e7c1540f1747faafedd0716235fdc31512cb73e9a5921b0e4cfdecd72ca7\n'

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
    if manifest["gitSha"] != SOURCE or manifest["versionCode"] != 17:
        raise RuntimeError("Unexpected source version")
    if expected["DataSail-V0.11.0-debug.apk"] != "7472e7c1540f1747faafedd0716235fdc31512cb73e9a5921b0e4cfdecd72ca7":
        raise RuntimeError("APK differs from device-tested binary")
    if expected["DataSail-V0.11.0.zip"] != "98a9ccc4320ddc506732d61eeff154fbe686a3f98a21e6141667924894737544":
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
