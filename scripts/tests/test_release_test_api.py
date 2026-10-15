import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("release_api", Path(__file__).resolve().parents[1] / "generate-release-test-api.py")
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)

def definition(parents=(), members=(), references=(), member_refs=()):
    return {"access": 1, "parents": list(parents), "members": set(members),
            "references": set(references), "member_refs": set(member_refs)}

class ReleaseTestApiTest(unittest.TestCase):
    def test_inherited_static_facade_preserves_declaring_method(self):
        member = ("method", "lazy", "(I)Ljava/lang/String;")
        application = {"fixture/Facade": definition(["fixture/Implementation"]),
                       "fixture/Implementation": definition(members=[member, ("method", "unused", "()V")]),
                       "fixture/Unrelated": definition()}
        tests = {"fixture/Test": definition(references=["fixture/Facade"], member_refs=[("fixture/Facade", *member)])}
        text, classes, members = api.generate(application, tests)
        self.assertEqual((2, 1), (classes, members))
        self.assertIn("class fixture.Facade {\n}", text)
        self.assertIn("java.lang.String lazy(int);", text)
        self.assertNotIn("unused", text)
        self.assertNotIn("Unrelated", text)
        self.assertNotIn("dontwarn", text)
        self.assertIn("-keep,allowobfuscation", text)
        self.assertNotIn("allowoptimization", text)

    def test_array_and_primitive_descriptors_keep_exact_signatures(self):
        self.assertEqual(["byte[][]", "java.lang.String[]", "long", "void"], api.types("([[B[Ljava/lang/String;J)V"))

    def test_interface_member_is_resolved(self):
        method = ("method", "value", "()I")
        application = {"fixture/Impl": definition(["fixture/Contract"]),
                       "fixture/Contract": definition(members=[method])}
        application["fixture/Contract"]["access"] = 0x201
        text, _, _ = api.generate(application, {"Test": definition(member_refs=[("fixture/Impl", *method)])})
        self.assertIn("interface fixture.Contract {\n    int value();", text)

    def test_external_override_preserves_parameters_without_test_call_site(self):
        method = ("method", "createDispatcher", "(Ljava/util/List;)Ljava/lang/Object;")
        application = {"fixture/Factory": definition(members=[method])}
        application["fixture/Factory"]["access"] = 0x201
        tests = {"fixture/BaseTestFactory": definition(["fixture/Factory"]),
                 "fixture/TestFactory": definition(["fixture/BaseTestFactory"], members=[method])}
        text, classes, members = api.generate(application, tests)
        self.assertEqual((1, 1), (classes, members))
        self.assertIn("java.lang.Object createDispatcher(java.util.List);", text)

    def test_kotlin_compatibility_adapter_keeps_static_abi_names(self):
        member = ("method", "release", "(Ljava/lang/Object;)V")
        application = {"fixture/Contract$DefaultImpls": definition(members=[member])}
        text, _, _ = api.generate(application, {"Test": definition(member_refs=[("fixture/Contract$DefaultImpls", *member)])})
        self.assertIn("-keep class fixture.Contract$DefaultImpls {\n    void release(java.lang.Object);", text)

    def test_coroutine_probe_synthetic_keeps_static_abi_name(self):
        member = ("method", "probeCoroutineSuspended", "(Lkotlin/coroutines/Continuation;)V")
        owner = "kotlin/coroutines/jvm/internal/DebugProbesKt"
        text, _, _ = api.generate({owner: definition(members=[member])}, {"Test": definition(member_refs=[(owner, *member)])})
        self.assertIn("-keep class kotlin.coroutines.jvm.internal.DebugProbesKt", text)
        self.assertIn("void probeCoroutineSuspended(kotlin.coroutines.Continuation);", text)

if __name__ == "__main__":
    unittest.main()
