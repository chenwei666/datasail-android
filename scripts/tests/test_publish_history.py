import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import json

spec = importlib.util.spec_from_file_location('publisher', Path(__file__).parents[1] / 'publish-history.py')
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)

class PublicationIntegrityTest(unittest.TestCase):
    def fixture(self, folder):
        notes = folder / 'notes.md'; notes.write_text('Original bilingual notes / 原始说明')
        versions = []
        for index in range(21):
            tag = f'V0.0.{index}'
            names = [f'DataSail-{tag}-debug.apk', f'DataSail-{tag}.zip', 'manifest.json', 'TEST_REPORT.md', 'SHA256SUMS.txt', 'verification.json']
            versions.append({'tag': tag, 'sourceCommit': 'a' * 40, 'notes': str(notes),
                             'notesSha256': publisher.digest(notes), 'prerelease': True, 'payload': False,
                             'assets': [{'name': name, 'size': 4, 'sha256': 'b' * 64} for name in names]})
        return {'versions': versions}

    def test_changed_notes_reject_the_entire_plan(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp); plan = self.fixture(folder)
            self.assertEqual(21, len(publisher.validate_plan(plan)))
            (folder / 'notes.md').write_text('Changed after review')
            with self.assertRaisesRegex(RuntimeError, 'notes changed'): publisher.validate_plan(plan)

    def test_original_payload_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp); plan = self.fixture(folder); first = plan['versions'][0]; first['payload'] = True
            target = folder / 'release-history/payload' / first['tag']; target.mkdir(parents=True)
            (target / first['assets'][0]['name']).write_bytes(b'evil')
            with self.assertRaisesRegex(RuntimeError, 'artifact mismatch'): publisher.validate_plan(plan, folder)

    def test_existing_unexpected_attachment_is_not_deleted(self):
        with tempfile.TemporaryDirectory() as tmp:
            version = self.fixture(Path(tmp))['versions'][0]
            with patch.object(publisher, 'gh') as gh:
                with self.assertRaisesRegex(RuntimeError, 'Unexpected existing assets'):
                    publisher.verify_release_assets({'assets': [{'name': 'unexpected.apk'}]}, version, Path(tmp))
                gh.assert_not_called()

    def test_matching_size_with_different_downloaded_bytes_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            expected = {'name': 'test.apk', 'size': 4, 'sha256': 'b' * 64}
            remote = {'name': 'test.apk', 'size': 4, 'state': 'uploaded', 'id': 1}
            def download(*args, output=None): output.write(b'evil')
            with patch.object(publisher, 'gh', side_effect=download):
                with self.assertRaisesRegex(RuntimeError, 'Downloaded bytes mismatch'):
                    publisher.remote_bytes(remote, expected, Path(tmp))

    def test_changed_source_tag_aborts_before_any_mutation(self):
        with tempfile.TemporaryDirectory() as tmp:
            plan = self.fixture(Path(tmp)); calls = []
            def api(path, method='GET', data=None):
                calls.append(method)
                if path == 'repos/' + publisher.REPO: return {'private': True, 'html_url': 'https://github.com/' + publisher.REPO}
                if '/git/matching-refs/' in path: return [{'ref': 'refs/tags/V0.0.0'}]
                return {'sha': 'c' * 40}
            with patch.dict('os.environ', {'GITHUB_REPOSITORY': publisher.REPO}), patch.object(publisher, 'gh', return_value=json.dumps([[]])), patch.object(publisher, 'api', side_effect=api):
                with self.assertRaisesRegex(RuntimeError, 'different source'): publisher.publish(plan, {'versions': []})
            self.assertTrue(calls); self.assertEqual({'GET'}, set(calls))

    def test_debug_build_cannot_be_relabelled_as_stable(self):
        with tempfile.TemporaryDirectory() as tmp:
            plan = self.fixture(Path(tmp)); plan['versions'][0]['prerelease'] = False
            with self.assertRaisesRegex(RuntimeError, 'retain their prerelease status'): publisher.validate_plan(plan)

if __name__ == '__main__': unittest.main()
