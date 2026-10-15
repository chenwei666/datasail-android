#!/usr/bin/env python3
"""Verify the current release matrix and bind its reports to the actual APKs."""
from pathlib import Path
import hashlib,json,re,subprocess,sys,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
build=(root/'app/build.gradle.kts').read_text()
version=re.search(r'versionName = "([0-9.]+)"',build).group(1)
code=int(re.search(r'versionCode = ([0-9]+)',build).group(1))
ev=root/'docs/evidence'/('v'+version.replace('.',''))
apk=root/'app/build/outputs/apk/release/app-release.apk';sha=hashlib.sha256(apk.read_bytes()).hexdigest()
test_apk=root/'app/build/outputs/apk/androidTest/release/app-release-androidTest.apk'
test_sha=hashlib.sha256(test_apk.read_bytes()).hexdigest()
runners=json.loads((ev/'test-runners.json').read_text())
assert runners['applicationSha256']==sha and runners['currentTestApkSha256']==test_sha
prior_reports={}
for index,runner in enumerate(runners['priorRunners']):
 assert runner['supersededBy']==(runners['priorRunners'][index+1]['sha256'] if index+1<len(runners['priorRunners']) else test_sha)
 assert hashlib.sha256((root/runner['localPath']).read_bytes()).hexdigest()==runner['sha256']
 assert hashlib.sha256((ev/runner['testOnlyPatch']).read_bytes()).hexdigest()==runner['testOnlyPatchSha256']
 for report in runner['reports']:
  assert report not in prior_reports and not report.endswith('local-ui.txt')
  prior_reports[report]=runner['sha256']
expected={'SQL_SERVER':4,'SQL_SERVER_AUTOCERT':2,'MYSQL':4,'MARIADB':4,'POSTGRESQL':4,'REDIS':4,'VALKEY':4,'MONGODB':5,'ELASTICSEARCH':6,'OPENSEARCH':6}
reports=[]
for api,serial in [(26,'emulator-5556'),(30,'emulator-5554')]:
 base=f'android{api}-release'
 pairs=[(f'{base}/{engine}/android-{serial}.txt',f'{base}/{engine}/device-{serial}.json',count) for engine,count in expected.items()]
 pairs += [(f'{base}/ai-{serial}.txt',f'{base}/ai-device-{serial}.json',5),(f'{base}/local-ui.txt',f'{base}/local-ui-device.json',33)]
 for report,device,count in pairs:
  text=(ev/report).read_text();receipt=json.loads((ev/device).read_text())
  assert re.search(r'OK \('+str(count)+r' tests?\)',text) and 'FAILURES!!!' not in text,report
  assert receipt['variant']=='release' and receipt['api']==str(api) and receipt['installedApkSha256']==sha and receipt['installedTestApkSha256']==prior_reports.get(report,test_sha),device
  reports.append({'report':report,'device':device,'tests':count,'api':api,'testApkSha256':receipt['installedTestApkSha256']})
def junit(paths):
 rows=[ET.parse(p).getroot() for p in paths];assert rows
 result={key:sum(int(row.get(key,'0')) for row in rows) for key in ['tests','failures','errors','skipped']}
 assert not any(result[k] for k in ['failures','errors','skipped'])
 return result
units={name:junit((root/path).glob('TEST-*.xml')) for name,path in {
 'core':'core/build/test-results/test','app-debug':'app/build/test-results/testDebugUnitTest',
 'sqlserver-profile':'sqlserver-android/build/test-results/test','postgresql-profile':'postgresql-android/build/test-results/test',
 'mariadb-profile':'mariadb-android/build/test-results/test','mongodb-profile':'mongodb-core-android/build/test-results/test'}.items()}
engine_paths={kind:next(path for path in [ev/'android30-release'/kind,ev/'android26-release'/kind] if list(path.glob('TEST-*.xml'))) for kind in expected}
engines={kind:junit(path.glob('TEST-*.xml')) for kind,path in engine_paths.items()}
probes=junit((ev/'driver-probes').glob('TEST-*.xml'))
assert not ET.parse(root/'app/build/reports/lint-results-debug.xml').getroot().findall('issue')
run=subprocess.run([sys.executable,'-m','unittest','discover','-s','scripts/tests'],cwd=root,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
(ev/'script-tests.txt').write_text(run.stdout)
if run.returncode: raise SystemExit('Script regression tests failed')
script_count=int(re.search(r'Ran (\d+) tests?',run.stdout).group(1))
debug_report=(ev/'android26-debug/smoke.txt').read_text()
debug_device=json.loads((ev/'android26-debug/device.json').read_text())
assert 'OK (3 tests)' in debug_report and 'FAILURES!!!' not in debug_report
assert debug_device['variant']=='debug' and debug_device['installedApkSha256']==hashlib.sha256((root/'app/build/outputs/apk/debug/app-debug.apk').read_bytes()).hexdigest()
result={'debugSmokeTests':3,'debugSmokeDevice':debug_device,'version':version,'versionCode':code,'variant':'release','finalApkSha256':sha,'finalTestApkSha256':test_sha,
 'requiredJvmReports':['test','sqlServerTest','sqlServerAutoCertificateTest','jdbcWorkspaceTest','keyValueTest','mongoTest','searchTest','integrationTest','untrustedTlsTest'],
 'testRunnerHistory':runners,'androidReports':reports,'androidTests':sum(r['tests'] for r in reports),
 'jvmUnits':units,'jvmEngines':engines,'jvmEngineReportDirectories':{k:str(v.relative_to(ev)) for k,v in engine_paths.items()},'driverProbes':probes,
 'jvmTests':sum(r['tests'] for r in units.values())+sum(r['tests'] for r in engines.values())+probes['tests'],
 'pythonScriptTests':script_count,'lintDebugIssues':0,
 'physicalDevicesTested':False,'arm64Tested':False,'liveAiVendorsTested':False,
 'historicalDebugUpgrade':'New production certificate cannot overwrite historical Debug installs; expected rejection evidence retained under attempts, not a successful upgrade.'}
(ev/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print('Verified final evidence:',result['jvmTests'],'JVM checks,',result['androidTests'],'Release Android checks')
