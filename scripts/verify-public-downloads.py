#!/usr/bin/env python3
"""Verify release visibility and downloads without a GitHub token or cookies."""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
from urllib.request import Request, urlopen

REPO = 'chenwei666/datasail-android'
RECEIPT = Path('publication/public-access-20261015.json')

def request(url, method='GET'):
    return urlopen(Request(url, method=method, headers={'User-Agent': 'DataSail-public-download-verification'}), timeout=90)

def read_json(url):
    with request(url) as response:
        return json.load(response)

def check_asset(item):
    tag, asset = item
    url = f"https://github.com/{REPO}/releases/download/{tag}/{asset['name']}"
    with request(url, 'HEAD') as response:
        size = int(response.headers.get('Content-Length', '-1'))
        if response.status != 200 or size != asset['size']:
            raise RuntimeError('Anonymous download metadata mismatch: ' + tag + '/' + asset['name'])
    return {'tag': tag, 'name': asset['name'], 'status': 200, 'size': size, 'url': url}

def verify(receipt):
    plan = json.loads(Path('release-history/plan.json').read_text())
    repo = read_json('https://api.github.com/repos/' + REPO)
    receipt['repositoryPrivate'] = repo['private']
    if repo['private']: raise RuntimeError('Repository is not public')
    releases = read_json(f'https://api.github.com/repos/{REPO}/releases?per_page=100')
    items = []
    for version in plan['versions']:
        matches = [r for r in releases if r['tag_name'] == version['tag'] and not r['draft']]
        if len(matches) != 1: raise RuntimeError('Public release missing: ' + version['tag'])
        release = matches[0]
        if release['body'] != Path(version['notes']).read_text():
            raise RuntimeError('Public release notes mismatch: ' + version['tag'])
        assets = {a['name']: a for a in release['assets']}
        if set(assets) != {a['name'] for a in version['assets']}:
            raise RuntimeError('Public release assets mismatch: ' + version['tag'])
        for asset in version['assets']:
            if assets[asset['name']]['size'] != asset['size']: raise RuntimeError('Public asset size mismatch')
            items.append((version['tag'], asset))
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        receipt['downloads'] = list(executor.map(check_asset, items))
    latest = plan['versions'][-1]
    apk = next(a for a in latest['assets'] if a['name'].endswith('.apk'))
    url = f"https://github.com/{REPO}/releases/download/{latest['tag']}/{apk['name']}"
    digest = hashlib.sha256()
    with request(url) as response:
        for block in iter(lambda: response.read(1024 * 1024), b''): digest.update(block)
    if digest.hexdigest() != apk['sha256']: raise RuntimeError('Anonymous APK checksum mismatch')
    receipt.update(status='verified', releaseCount=len(plan['versions']), assetCount=len(items),
                   latestApk={'tag': latest['tag'], 'sha256': digest.hexdigest(), 'url': url})

if __name__ == '__main__':
    receipt = {'status': 'running', 'anonymous': True, 'runId': os.environ.get('GITHUB_RUN_ID')}
    try:
        verify(receipt)
        print('Anonymous public access verified:', receipt['releaseCount'], 'releases,', receipt['assetCount'], 'assets')
    except Exception as error:
        receipt.update(status='failed', error=str(error))
        raise
    finally:
        RECEIPT.parent.mkdir(parents=True, exist_ok=True)
        RECEIPT.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n')
