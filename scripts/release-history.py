import json,subprocess
from pathlib import Path
repo='chenwei666/datasail-android'
def api(path): return json.loads(subprocess.check_output(['gh','api',path],text=True))
r=api('repos/'+repo)
pages=json.loads(subprocess.check_output(['gh','api','--paginate','--slurp','repos/'+repo+'/releases?per_page=100'],text=True))
out={'status':'audited','repository':{k:r[k] for k in ['full_name','private','description','homepage','topics']},'releases':[{k:r[k] for k in ['id','tag_name','name','draft','prerelease','target_commitish','published_at','html_url','body','assets']} for page in pages for r in page]}
Path('publication/history-20261015.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')
print('Audited',len(out['releases']),'releases')
