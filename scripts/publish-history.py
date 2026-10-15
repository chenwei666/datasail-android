#!/usr/bin/env python3
"""Publish original historical artifacts and bilingual notes; never rebuild or replace bytes."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

REPO = 'chenwei666/datasail-android'
PLAN = Path('release-history/plan.json')
RECEIPT = Path('publication/history-20261015.json')

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''): h.update(block)
    return h.hexdigest()

def gh(*args, output=None):
    result = subprocess.run(['gh', *args], text=output is None, stdout=output or subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=180)
    if result.returncode:
        error = result.stderr if isinstance(result.stderr, str) else result.stderr.decode(errors='replace')
        raise RuntimeError('GitHub command failed (' + ' '.join(args[:5]) + '): ' + error.strip()[:1500])
    return result.stdout

def api(path, method='GET', data=None):
    args = ['api', '--method', method, path]
    if data is None: return json.loads(gh(*args))
    with tempfile.TemporaryDirectory(prefix='datasail-api-') as tmp:
        payload = Path(tmp) / 'request.json'
        payload.write_text(json.dumps(data, ensure_ascii=False))
        return json.loads(gh(*args, '--input', str(payload)))

def validate_plan(plan, base=Path('.')):
    versions = plan['versions']
    if len(versions) != 21 or len({v['tag'] for v in versions}) != 21:
        raise RuntimeError('Expected all 21 unique versions')
    for version in versions:
        tag = version['tag']
        if not __import__('re').fullmatch(r'V[0-9]+\.[0-9]+\.[0-9]+', tag): raise RuntimeError('Invalid tag')
        if not __import__('re').fullmatch(r'[a-f0-9]{40}', version['sourceCommit']): raise RuntimeError('Invalid source commit')
        if not version['prerelease']: raise RuntimeError('Historical Debug builds must retain their prerelease status')
        notes = base / version['notes']
        if digest(notes) != version['notesSha256']: raise RuntimeError('Release notes changed: ' + tag)
        assets = version['assets']
        if len(assets) != 6 or len({a['name'] for a in assets}) != 6: raise RuntimeError('Expected six distinct assets: ' + tag)
        required = {f'DataSail-{tag}-debug.apk', f'DataSail-{tag}.zip', 'manifest.json', 'TEST_REPORT.md', 'SHA256SUMS.txt'}
        if not required.issubset({a['name'] for a in assets}): raise RuntimeError('Incomplete artifact set: ' + tag)
        for asset in assets:
            name = asset['name']
            if Path(name).name != name or '/' in name or '\\' in name: raise RuntimeError('Unsafe asset name')
            if not __import__('re').fullmatch(r'[a-f0-9]{64}', asset['sha256']) or asset['size'] <= 0: raise RuntimeError('Invalid asset identity')
            if version['payload']:
                path = base / 'release-history/payload' / tag / name
                if path.stat().st_size != asset['size'] or digest(path) != asset['sha256']:
                    raise RuntimeError('Original artifact mismatch: ' + tag + '/' + name)
    return versions

def remote_bytes(asset, expected, directory):
    if asset['name'] != expected['name'] or asset['state'] != 'uploaded' or asset['size'] != expected['size']:
        raise RuntimeError('Remote asset metadata mismatch: ' + expected['name'])
    server_digest = asset.get('digest')
    if server_digest and server_digest != 'sha256:' + expected['sha256']:
        raise RuntimeError('Remote digest mismatch: ' + expected['name'])
    target = directory / expected['name']
    with target.open('wb') as output:
        gh('api', '-H', 'Accept: application/octet-stream', f"repos/{REPO}/releases/assets/{asset['id']}", output=output)
    if digest(target) != expected['sha256']: raise RuntimeError('Downloaded bytes mismatch: ' + expected['name'])
    target.unlink()

def verify_release_assets(release, version, directory, complete=False):
    expected = {a['name']: a for a in version['assets']}
    uploaded = {a['name']: a for a in release['assets']}
    if len(uploaded) != len(release['assets']) or not set(uploaded).issubset(expected):
        raise RuntimeError('Unexpected existing assets: ' + version['tag'])
    if complete and set(uploaded) != set(expected): raise RuntimeError('Incomplete release: ' + version['tag'])
    for name, asset in uploaded.items(): remote_bytes(asset, expected[name], directory)
    return uploaded

def publish(plan, receipt):
    if os.environ.get('GITHUB_REPOSITORY') != REPO: raise RuntimeError('Unexpected repository context')
    versions = validate_plan(plan)
    pages = json.loads(gh('api', '--paginate', '--slurp', f'repos/{REPO}/releases?per_page=100'))
    releases = [r for page in pages for r in page]
    repo = api('repos/' + REPO)
    receipt['repositoryPrivate'] = repo['private']
    receipt['repositoryUrl'] = repo['html_url']
    # Global preflight: validate every existing source and every existing remote asset before mutations.
    with tempfile.TemporaryDirectory(prefix='datasail-history-') as tmp:
        directory = Path(tmp)
        existing = {}
        for version in versions:
            tag = version['tag']
            matches = [r for r in releases if r['tag_name'] == tag]
            if len(matches) > 1: raise RuntimeError('Duplicate release: ' + tag)
            refs = api(f'repos/{REPO}/git/matching-refs/tags/{tag}')
            exact = [r for r in refs if r['ref'] == 'refs/tags/' + tag]
            if not exact: raise RuntimeError('Original source tag must exist before publication: ' + tag)
            if api(f'repos/{REPO}/commits/{tag}')['sha'] != version['sourceCommit']:
                raise RuntimeError('Immutable tag points to different source: ' + tag)
            if api(f"repos/{REPO}/commits/{version['sourceCommit']}")['sha'] != version['sourceCommit']:
                raise RuntimeError('Source commit unavailable: ' + tag)
            if matches:
                release = matches[0]
                if not exact or not release['prerelease']: raise RuntimeError('Unexpected existing release channel or tag: ' + tag)
                uploaded = verify_release_assets(release, version, directory)
                if len(uploaded) != 6 and not version['payload']: raise RuntimeError('Missing original payload: ' + tag)
                existing[tag] = release
            elif not version['payload']: raise RuntimeError('Missing existing release and payload: ' + tag)
        for version in versions:
            tag = version['tag']; body = Path(version['notes']).read_text(); created = tag not in existing
            release = existing.get(tag)
            if release is None:
                release = api(f'repos/{REPO}/releases', 'POST', {'tag_name': tag,
                    'name': version['title'], 'body': body, 'draft': True, 'prerelease': True, 'make_latest': 'false'})
            uploaded = {a['name']: a for a in release['assets']}
            for asset in version['assets']:
                if asset['name'] not in uploaded:
                    # Never --clobber, delete or replace an existing historical attachment.
                    path = Path('release-history/payload') / tag / asset['name']
                    gh('release', 'upload', tag, str(path), '--repo', REPO)
            release = api(f"repos/{REPO}/releases/{release['id']}")
            verify_release_assets(release, version, directory, complete=True)
            release = api(f"repos/{REPO}/releases/{release['id']}", 'PATCH', {'name': version['title'], 'body': body,
                'draft': False, 'prerelease': True, 'make_latest': 'false'})
            if release['draft'] or not release['published_at'] or release['body'] != body:
                raise RuntimeError('Publication state did not match: ' + tag)
            if api(f'repos/{REPO}/commits/{tag}')['sha'] != version['sourceCommit']:
                raise RuntimeError('Published source mismatch: ' + tag)
            receipt['versions'].append({'tag': tag, 'url': release['html_url'], 'sourceCommit': version['sourceCommit'],
                'created': created, 'prerelease': release['prerelease'], 'publishedAt': release['published_at'],
                'notesSha256': version['notesSha256'], 'assets': version['assets']})
            print('Verified and published', tag, flush=True)
    receipt['repositoryPrivate'] = api('repos/' + REPO)['private']
    receipt['status'] = 'published'

if __name__ == '__main__':
    plan = json.loads(PLAN.read_text())
    if '--refresh-visibility' in sys.argv:
        if os.environ.get('GITHUB_REPOSITORY') != REPO: raise RuntimeError('Unexpected repository context')
        receipt = json.loads(RECEIPT.read_text())
        if receipt['status'] != 'published' or len(receipt['versions']) != 21: raise RuntimeError('Release publication incomplete')
        receipt['repositoryPrivate'] = api('repos/' + REPO)['private']
        receipt['visibilityRunId'] = os.environ.get('GITHUB_RUN_ID')
        RECEIPT.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n')
    elif '--verify-local' in sys.argv:
        print('Validated', len(validate_plan(plan)), 'versions; publication was not executed')
    else:
        receipt = {'status': 'running', 'runId': os.environ.get('GITHUB_RUN_ID'), 'versions': []}
        try: publish(plan, receipt)
        except Exception as error:
            receipt.update(status='failed', error=str(error)); raise
        finally:
            RECEIPT.parent.mkdir(parents=True, exist_ok=True)
            RECEIPT.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n')
