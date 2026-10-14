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
TAG = "V0.13.0"
SOURCE = "1bff4d8f1895a23ef970079aa78dacb8cd1b2c01"
ASSETS = Path("release-upload/V0.13.0")
RECEIPT = Path("publication/V0.13.0.json")
TITLE = "数舟 DataSail V0.13.0 · AI 记忆与多数据库编辑"
NOTES = '数舟 DataSail V0.13.0 / versionCode 21。\n\n### 本次更新\n- AI 设置输入 Key 后即可获取上游模型，无需先保存或手填模型；选择模型自动保存。\n- 助手精简为「问答 / 写 SQL」「操作当前表」，两页都支持连续问答。新增本机加密上下文记忆，按服务、模型、数据库与表隔离，重新打开可以继续追问，也可清空；无跨设备云同步。\n- SQL 表双击可编辑字段直接打开草稿；不用先点编辑模式。新增、行修改和删除统一工具栏，预览确认后才写入。\n- MySQL、MariaDB、PostgreSQL、SQLite 私有副本增加受限单行 CRUD；Redis/Valkey 支持完整字符串；MongoDB 支持有界文档；Elasticsearch/OpenSearch 支持版本校验的单文档操作。\n- AI 解释表用途时不会调用修改；SQL Server 整表删除另行预览、输入完整表名确认。旧对话不会自动重放写入。\n- 修复 MySQL/MariaDB 浮点原值比较及搜索引擎复用关闭连接的问题；冲突拒绝覆盖，响应丢失显示结果未知，不自动重试。\n\n### 安装与验证\n下载 `DataSail-V0.13.0-debug.apk` 覆盖安装，包名与签名连续；原连接、密码、收藏和设置保留。\n实际通过 201 项 JVM 和 67 项 Android 检查；详细引擎、设备、用例和故障记录见附件 TEST_REPORT.md。只使用隔离合成数据库及 HTTPS 上游，未访问用户业务数据库和真实收费 AI 账号。\n附件为已验证原件，GitHub 不重新编译或签名；ZIP 包含源码、APK、报告及校验清单。manifest 的 published=false 是打包时状态，发布回执独立记录。\n\n### 限制\nRelease/R8 仍缺失 193 个类，因此本次为 Debug 预发布。Android 11/API30、真机/arm64 与用户业务库没有本轮通过证据，不能承诺零缺陷。无主键、视图、触发器、不支持类型、超限或未完整加载记录仍有限制；SQLite 不写回源文件。AI 当前表操作限关系型表，整表删除限 SQL Server。尚未覆盖 Oracle、Db2、ClickHouse 等规划引擎，也不宣称完整 DBeaver 功能。\n\n源码提交：1bff4d8f1895a23ef970079aa78dacb8cd1b2c01\nAPK SHA-256：0f03dbd7408d2c015f0e12fa71a14e0f61403462624723e7e1a309242cdb72f5\n'

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
    if manifest["gitSha"] != SOURCE or manifest["versionCode"] != 21:
        raise RuntimeError("Unexpected source version")
    if expected["DataSail-V0.13.0-debug.apk"] != "0f03dbd7408d2c015f0e12fa71a14e0f61403462624723e7e1a309242cdb72f5":
        raise RuntimeError("APK differs from device-tested binary")
    if expected["DataSail-V0.13.0.zip"] != "b51990cf01baa49c2f24a202314045418400296024a655225b20b16f8d1ca3c5":
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
