import json, subprocess
from pathlib import Path
repo = 'chenwei666/datasail-android'
receipt = Path('publication/history-20261015.json')
receipt.parent.mkdir(parents=True, exist_ok=True)
def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', path], text=True))
try:
    r = api('repos/' + repo)
    pages = json.loads(subprocess.check_output(['gh', 'api', '--paginate', '--slurp', 'repos/' + repo + '/releases?per_page=100'], text=True))
    out = {'status': 'audited', 'repository': {k: r[k] for k in ['full_name', 'private', 'description', 'homepage', 'topics']}, 'releases': [{k: r[k] for k in ['id', 'tag_name', 'name', 'draft', 'prerelease', 'target_commitish', 'published_at', 'html_url', 'body', 'assets']} for page in pages for r in page]}
    receipt.write_text(json.dumps(out, ensure_ascii=False, indent=2) + '\n')
    print('Audited', len(out['releases']), 'releases')
except Exception as error:
    receipt.write_text(json.dumps({'status': 'failed', 'error': str(error)}, indent=2) + '\n')
    raise
