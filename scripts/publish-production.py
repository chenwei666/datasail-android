#!/usr/bin/env python3
"""Publish one verified production archive without replacing existing release assets."""
import hashlib
import urllib.request
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

spec = importlib.util.spec_from_file_location('history', Path(__file__).with_name('publish-history.py'))
history = importlib.util.module_from_spec(spec)
spec.loader.exec_module(history)
REPO = history.REPO
PLAN = Path('release-production/plan.json')
RECEIPT = Path('publication/production.json')


def validate(plan):
    tag = plan['tag']
    if not re.fullmatch(r'V[0-9]+\.[0-9]+\.[0-9]+', tag):
        raise ValueError('Invalid release tag')
    if not re.fullmatch(r'[a-f0-9]{40}', plan['sourceCommit']):
        raise ValueError('Invalid source commit')
    if plan.get('prerelease') is not False:
        raise ValueError('Production plan must identify its release channel')
    notes = Path(plan['notes'])
    if history.digest(notes) != plan['notesSha256']:
        raise ValueError('Release notes changed')
    assets = plan['assets']
    required = {f'DataSail-{tag}-release.apk', f'DataSail-{tag}.zip', 'manifest.json', 'TEST_REPORT.md', 'RELEASE_NOTES.md', 'THIRD_PARTY_NOTICES.txt', 'SHA256SUMS.txt'}
    names = {a['name'] for a in assets}
    if len(names) != len(assets) or names != required:
        raise ValueError('Incomplete or duplicate assets')
    base = Path('release-production/payload')
    for asset in assets:
        name = asset['name']
        if Path(name).name != name or '\\' in name:
            raise ValueError('Unsafe asset name')
        path = base / name
        if path.stat().st_size != asset['size'] or history.digest(path) != asset['sha256']:
            raise ValueError('Artifact bytes differ: ' + name)
    manifest = json.loads((base / 'manifest.json').read_text())
    if (manifest['version'] != tag or manifest['gitSha'] != plan['sourceCommit'] or
            manifest['variant'] != 'release' or manifest['signing']['type'] != 'production'):
        raise ValueError('Manifest identity differs')
    identity = json.loads(Path('docs/releases/signing-certificate.json').read_text())
    if manifest['signing']['certificateSha256'] != identity['certificateSha256']:
        raise ValueError('Production signing identity differs')
    apk_name = f'DataSail-{tag}-release.apk'
    apk = base / apk_name
    apk_hash = history.digest(apk)
    if manifest['artifacts'][apk_name]['sha256'] != apk_hash or manifest['artifacts'][apk_name]['bytes'] != apk.stat().st_size:
        raise ValueError('Manifest APK digest or size differs')
    if manifest['verification']['variant'] != 'release' or manifest['verification']['finalApkSha256'] != apk_hash:
        raise ValueError('Release verification does not match APK')
    if not manifest['devices'] or any(d['installedApkSha256'] != apk_hash for d in manifest['devices'].values()):
        raise ValueError('Device evidence does not match APK')
    sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0'
    certificate = subprocess.check_output([str(sdk / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
    if 'certificate SHA-256 digest: ' + identity['certificateSha256'] not in certificate:
        raise ValueError('Actual APK certificate differs')
    badging = subprocess.check_output([str(sdk / 'aapt'), 'dump', 'badging', str(apk)], text=True)
    if ('application-debuggable' in badging or "name='com.example.datasail'" not in badging or
            f"versionCode='{manifest['versionCode']}'" not in badging or f"versionName='{tag[1:]}'" not in badging):
        raise ValueError('Actual APK identity/debuggability differs')
    subprocess.run(['python3', 'scripts/verify-release-dex.py', str(apk)], check=True)
    return plan


def publish(plan, receipt):
    validate(plan)
    if os.environ.get('GITHUB_REPOSITORY') != REPO:
        raise RuntimeError('Unexpected repository context')
    if history.api('repos/' + REPO)['private']:
        raise RuntimeError('Repository is not public')
    tag = plan['tag']
    if history.api(f'repos/{REPO}/commits/{tag}')['sha'] != plan['sourceCommit']:
        raise RuntimeError('Immutable tag source differs')
    pages = json.loads(history.gh('api', '--paginate', '--slurp', f'repos/{REPO}/releases?per_page=100'))
    matches = [r for page in pages for r in page if r['tag_name'] == tag]
    if len(matches) > 1:
        raise RuntimeError('Duplicate release')
    body = Path(plan['notes']).read_text()
    release = matches[0] if matches else None
    with tempfile.TemporaryDirectory(prefix='datasail-production-') as temp:
        directory = Path(temp)
        if release:
            if release['prerelease'] or release['body'] != body:
                raise RuntimeError('Existing release channel or notes differ')
            uploaded = history.verify_release_assets(release, plan, directory)
        else:
            release = history.api(f'repos/{REPO}/releases', 'POST', {
                'tag_name': tag, 'name': plan['title'], 'body': body,
                'draft': True, 'prerelease': False, 'make_latest': 'false'})
            uploaded = {}
        for asset in plan['assets']:
            if asset['name'] not in uploaded:
                history.gh('release', 'upload', tag, str(Path('release-production/payload') / asset['name']), '--repo', REPO)
        release = history.api(f"repos/{REPO}/releases/{release['id']}")
        history.verify_release_assets(release, plan, directory, complete=True)
        release = history.api(f"repos/{REPO}/releases/{release['id']}", 'PATCH', {
            'draft': False, 'prerelease': False, 'make_latest': 'true'})
        if release['draft'] or release['prerelease'] or not release['published_at']:
            raise RuntimeError('Publication state differs')
        public_assets = []
        for asset in release['assets']:
            expected = next(a for a in plan['assets'] if a['name'] == asset['name'])
            url = asset['browser_download_url']
            if not url.startswith(f'https://github.com/{REPO}/releases/download/{tag}/'):
                raise RuntimeError('Unexpected public asset URL')
            digest, size = hashlib.sha256(), 0
            # Deliberately no Authorization header or gh environment credentials.
            with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'DataSail-public-verification'}), timeout=180) as response:
                while block := response.read(1024 * 1024):
                    digest.update(block); size += len(block)
            if digest.hexdigest() != expected['sha256'] or size != expected['size']:
                raise RuntimeError('Anonymous downloaded bytes differ: ' + asset['name'])
            public_assets.append({'name': asset['name'], 'url': url, 'sha256': digest.hexdigest(), 'bytes': size})
        receipt.update(status='published', publicDownloads=public_assets, url=release['html_url'], sourceCommit=plan['sourceCommit'],
                       publishedAt=release['published_at'], assets=plan['assets'])

if __name__ == '__main__':
    import sys
    plan = json.loads(PLAN.read_text())
    if '--verify-local' in sys.argv:
        validate(plan)
        print('Production artifacts verified locally; not published')
    else:
        receipt = {'status': 'running', 'runId': os.environ.get('GITHUB_RUN_ID')}
        try:
            publish(plan, receipt)
        except Exception as error:
            receipt.update(status='failed', error=str(error))
            raise
        finally:
            RECEIPT.parent.mkdir(parents=True, exist_ok=True)
            RECEIPT.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n')
