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
TAG = "V0.10.0"
SOURCE = "3074f6f71eceba5af66a344f63d9eae2363783af"
ASSETS = Path("release-upload/V0.10.0")
RECEIPT = Path("publication/V0.10.0.json")
TITLE = "数舟 DataSail V0.10.0 · 移动工作台与安全编辑"
NOTES = '数舟 DataSail V0.10.0 / versionCode 16，移动工作台更新。\n\n### 工作台与连接\n- 固定「数据 / SQL 编辑器 / 表结构」页签，分页和编辑操作集中在底部；数据库 / Schema 搜索选择，扫描大量数据库不再撑长整页。\n- 表格双指 65%–200% 缩放、一键复位，单指滚动；选中单元格后直接查看或编辑。\n- 修复跨库收藏回到表格时被对象弹窗遮挡，切表 / 翻页恢复顶部。\n\n### 编辑与 SQL\n- 「开启编辑」确认当前连接后，双击可编辑字段，预览修改再提交；生产标记连接默认只读，可显式启用本次编辑。\n- SQL Server 六种日期时间类型支持精确文本写入、NULL 与并发原值校验；不自动舍入，无主键 / 视图 / 触发器等保护保留。其它引擎仍只读。\n- WHERE / FROM / JOIN 后提示已加载字段和表，Tab 接受候选；选中整段 SQL 时不再被 Tab 意外替换。\n- 实时 AI 使用设置中的服务，默认关闭。预览并授权后停顿 1.2 秒请求，建议手动接受，绝不自动执行；离开编辑器或换目标后关闭。不发送结果行。\n- 页面业务环境「测试」改为「常规」，保留生产 / 只读 / 可写状态。\n\n### 安装与验证\n下载 `DataSail-V0.10.0-debug.apk` 覆盖安装，无需卸载；保留连接、密码、证书、收藏和 AI 配置。\n120 项 JVM、36 项最终 APK 的 Android 检查通过；Android 8/API26 与 Android 11/API30 模拟器验证 V0.9.1 覆盖升级、500 库搜索、真实双指手势、SQL Tab、三协议合成 HTTPS AI 及 SQL Server 实连编辑。\n附件为实际验收原件，GitHub 不重新编译或签名。ZIP 包含完整源码、APK、报告与校验；manifest 的 published=false 是打包状态，发布回执单独保存。\n\n### 正式发行门槛与限制\nRelease/R8 仍有 193 个缺失类，本附件是 Debug 预发布包，尚不能作为正式稳定版。界面去掉测试标签不代表正式验收通过。\n未访问用户业务服务器；未验证真机 / arm64、真实 AI 服务商账号和计费、其它引擎本轮完整矩阵或全引擎编辑。不是完整 DBeaver，也不承诺零 bug。下一步为 Release 驱动兼容和真机验收。\n\n源码提交：3074f6f71eceba5af66a344f63d9eae2363783af\nAPK SHA-256：003bcb2d7682a2fe69324fb46040534550b983f9fe83d6c57b4b652b384f66fc\n'

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
    if manifest["gitSha"] != SOURCE or manifest["versionCode"] != 16:
        raise RuntimeError("Unexpected source version")
    if expected["DataSail-V0.10.0-debug.apk"] != "003bcb2d7682a2fe69324fb46040534550b983f9fe83d6c57b4b652b384f66fc":
        raise RuntimeError("APK differs from device-tested binary")
    if expected["DataSail-V0.10.0.zip"] != "7a372e112649b9f27faef84f088597f85d92cc983621273721790ec8430e9963":
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
