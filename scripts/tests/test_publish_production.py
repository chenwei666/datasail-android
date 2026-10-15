import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('production', Path(__file__).resolve().parents[1] / 'publish-production.py')
production = importlib.util.module_from_spec(spec)
spec.loader.exec_module(production)

class ProductionPublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.previous = Path.cwd()
        os.chdir(self.temp.name)
        self.addCleanup(lambda: os.chdir(self.previous))
        self.addCleanup(self.temp.cleanup)
        self.base = Path('release-production/payload')
        self.base.mkdir(parents=True)
        notes = Path('notes.md'); notes.write_text('Chinese / English release notes')
        identity = Path('docs/releases/signing-certificate.json')
        identity.parent.mkdir(parents=True)
        self.cert = 'a' * 64
        identity.write_text(json.dumps({'certificateSha256': self.cert}))
        apk = self.base / 'DataSail-V0.14.0-release.apk'; apk.write_bytes(b'test-fixture-apk')
        self.manifest = {'version': 'V0.14.0', 'versionCode': 22, 'gitSha': 'b' * 40,
            'variant': 'release', 'signing': {'type': 'production', 'certificateSha256': self.cert},
            'artifacts': {apk.name: {'sha256': production.history.digest(apk), 'bytes': apk.stat().st_size}},
            'verification': {'variant': 'release', 'finalApkSha256': production.history.digest(apk)},
            'devices': {'emulator': {'installedApkSha256': production.history.digest(apk)}}}
        (self.base / 'manifest.json').write_text(json.dumps(self.manifest))
        for name in ['DataSail-V0.14.0.zip', 'TEST_REPORT.md', 'RELEASE_NOTES.md', 'THIRD_PARTY_NOTICES.txt', 'SHA256SUMS.txt']:
            (self.base / name).write_bytes(b'test-fixture')
        self.plan = {'tag': 'V0.14.0', 'sourceCommit': 'b' * 40, 'prerelease': False,
            'notes': str(notes), 'notesSha256': production.history.digest(notes)}
        self.refresh()

    def refresh(self):
        self.plan['assets'] = [{'name': p.name, 'size': p.stat().st_size,
            'sha256': production.history.digest(p)} for p in self.base.iterdir()]

    def test_rejects_extra_asset_before_any_publication(self):
        (self.base / 'private-key.p12').write_bytes(b'fixture')
        self.refresh()
        with self.assertRaisesRegex(ValueError, 'Incomplete or duplicate'):
            production.validate(self.plan)

    def test_rejects_changed_payload(self):
        (self.base / 'DataSail-V0.14.0-release.apk').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'Artifact bytes differ'):
            production.validate(self.plan)

    def test_rejects_stale_device_evidence(self):
        self.manifest['devices']['emulator']['installedApkSha256'] = 'c' * 64
        (self.base / 'manifest.json').write_text(json.dumps(self.manifest)); self.refresh()
        with self.assertRaisesRegex(ValueError, 'Device evidence'):
            production.validate(self.plan)

    def test_rejects_actual_debug_certificate_even_if_manifest_claims_production(self):
        with patch.dict(os.environ, {'ANDROID_HOME': '/fixture-sdk'}), patch.object(
                production.subprocess, 'check_output', return_value='certificate SHA-256 digest: ' + 'd' * 64):
            with self.assertRaisesRegex(ValueError, 'Actual APK certificate'):
                production.validate(self.plan)

if __name__ == '__main__':
    unittest.main()
