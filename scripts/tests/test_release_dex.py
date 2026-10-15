import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('dex_verify', Path(__file__).resolve().parents[1] / 'verify-release-dex.py')
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)

class ReleaseReflectionTest(unittest.TestCase):
    def fixture(self):
        builder = 'Lorg/mariadb/jdbc/Configuration$Builder;'
        names = {'user', 'password', 'sslMode', 'tlsSocketType', 'connectTimeout', 'socketTimeout',
                 'allowLocalInfile', 'allowMultiQueries', 'allowPublicKeyRetrieval', 'useServerPrepStmts'}
        return {builder, 'Lcom/microsoft/sqlserver/jdbc/SQLServerResource;'}, {(builder,n) for n in names}, {(builder,n) for n in names}

    def test_security_property_rename_is_rejected(self):
        classes, fields, methods = self.fixture()
        fields.remove(('Lorg/mariadb/jdbc/Configuration$Builder;', 'sslMode'))
        self.assertIn('field sslMode', verify.reflection_gaps(classes, fields, methods))

    def test_missing_message_bundle_is_rejected(self):
        classes, fields, methods = self.fixture()
        classes.remove('Lcom/microsoft/sqlserver/jdbc/SQLServerResource;')
        self.assertTrue(verify.reflection_gaps(classes, fields, methods))

    def test_retained_class_and_named_members_pass(self):
        self.assertEqual([], verify.reflection_gaps(*self.fixture()))

if __name__ == '__main__': unittest.main()
