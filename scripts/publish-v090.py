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
TAG = "V0.9.0"
SOURCE = "164da605829ce8c659a68fb165fee5154a724ec3"
ASSETS = Path("release-upload/V0.9.0")
RECEIPT = Path("publication/V0.9.0.json")
TITLE = "数舟 DataSail V0.9.0 · AI 助手与完整字段浏览（测试版）"
NOTES = '数舟 DataSail V0.9.0 / versionCode 14，Debug 预发布更新。\n\n### AI 助手\n- OpenAI 兼容 Chat Completions、Anthropic Messages、Gemini generateContent 三种文本接口，19 个国内外服务预设及自定义 HTTPS 地址。\n- 从上游获取 / 筛选模型；不提供列表的服务可手填模型 ID。可保存多个服务及独立加密密钥，AI 可以关闭。\n- 每个数据库连接默认禁止外部 AI；显式选择结构或 SQL、预览脱敏、确认发送。生成的 SQL 仅作为草稿，人工载入后仍需手动执行。\n- 预设包括 OpenAI、Claude、Gemini、DeepSeek、通义千问、豆包、智谱、Kimi、MiniMax、硅基流动、千帆、混元、Grok、OpenRouter、Groq、Mistral、Together、NVIDIA、Perplexity。预设是配置便利项，不代表逐家或全部模型实测。\n\n### 表格与设置\n- SQL 宽表按 64 列分组浏览后续字段，保留 100 行分页；查询结果超过字段预算会明确提示。\n- 新增全屏表格、换行、列宽、列显隐和整行详情；设置可保存默认显示偏好。打开表后收起对象列表。\n- 保留原包名、签名、密码、连接、证书信任、查询收藏和表收藏。\n\n### 安装与实际验证\n下载 `DataSail-V0.9.0-debug.apk` 覆盖安装。Android 8/API26 与 Android 11/API30 模拟器已验证 V0.8.1 → V0.9.0 升级。\n131 项 JVM、53 项最终 APK 的 Android 检查通过（另有两项旧版升级准备检查）；包括三协议 HTTPS 合成上游、实际 AI 页面、SQLite 宽表、SQL Server 实连与原有安全编辑 / 证书流程。四个 SQL 网络引擎分别验证宽字段 JVM 浏览。\n附件为云端验收原件，GitHub 不重新编译或签名。ZIP 包含完整源码、安装包、文档和校验清单。manifest 中 published=false 是打包时状态；发布回执独立保存。\n\n### 当前限制\n未进行真实 AI 供应商账号及计费调用，也未验收真机 / arm64 和用户数据库。仅支持上述文本协议；AWS/Vertex IAM、Azure 专有接口、Responses-only、多模态和流式暂未实现。模型列表可见不代表有调用权限。\nRelease/R8 仍有 193 个缺失类，本次不是正式稳定版。生产连接只读；图形编辑仍限 SQL Server 测试会话安全单行操作。表格仍有字段 / 行 / 大字段预算，不宣称完整 DBeaver 或全部数据库功能。\n\n源码提交：164da605829ce8c659a68fb165fee5154a724ec3\nAPK SHA-256：de7ebcb649dd4ca784070e60d12f4626a7787467933e559ba0e6637e6072795f\n'

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
    if manifest["gitSha"] != SOURCE or manifest["versionCode"] != 14:
        raise RuntimeError("Unexpected source version")
    if expected["DataSail-V0.9.0-debug.apk"] != "de7ebcb649dd4ca784070e60d12f4626a7787467933e559ba0e6637e6072795f":
        raise RuntimeError("APK differs from device-tested binary")
    if expected["DataSail-V0.9.0.zip"] != "e77a03003df6e65b8c40846f4e39ab405275ae435f27b12c0c6bad84eb7344ee":
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
