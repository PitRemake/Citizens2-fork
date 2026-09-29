import importlib.util
import os
import pathlib
import tempfile
import unittest
import warnings
import zipfile

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "build-pitremake.py"
SPEC = importlib.util.spec_from_file_location("citizens_builder", SCRIPT)
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)


class ArtifactGateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixed = pathlib.Path(os.environ["CITIZENS_TEST_ARTIFACT"])
        cls.old = pathlib.Path(os.environ["CITIZENS_OLD_ARTIFACT"])
        with zipfile.ZipFile(cls.fixed) as jar:
            cls.code = jar.read(BUILDER.QUEUE_HANDLER)

    def jar(self, code, duplicate=False):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        path = pathlib.Path(temporary.name) / "Citizens.jar"
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(path, "w") as jar:
                jar.writestr(BUILDER.QUEUE_HANDLER, code)
                if duplicate:
                    jar.writestr(BUILDER.QUEUE_HANDLER, code)
                jar.writestr("plugin.yml", "version: " + BUILDER.FORK_VERSION)
        return path

    def test_rebuilt_distributable_passes(self):
        BUILDER.verify_artifact(self.fixed)

    def test_published_leaking_artifact_fails(self):
        with self.assertRaisesRegex(ValueError, "exact NPC queuePacket override"):
            BUILDER.verify_artifact(self.old)

    def test_forged_new_version_cannot_hide_unpatched_class(self):
        with zipfile.ZipFile(self.old) as jar:
            code = jar.read(BUILDER.QUEUE_HANDLER)
        with self.assertRaisesRegex(ValueError, "exact NPC queuePacket override"):
            BUILDER.verify_artifact(self.jar(code))

    def test_wrong_method_name_fails(self):
        with self.assertRaisesRegex(ValueError, "exact NPC queuePacket override"):
            BUILDER.verify_artifact(self.jar(self.code.replace(b"queuePacket", b"otherPacket")))

    def test_wrong_packet_descriptor_fails(self):
        code = self.code.replace(BUILDER.QUEUE_DESCRIPTOR.encode(),
                                 BUILDER.QUEUE_DESCRIPTOR.replace("Packet", "Picket").encode())
        with self.assertRaisesRegex(ValueError, "exact NPC queuePacket override"):
            BUILDER.verify_artifact(self.jar(code))

    def test_non_noop_implementation_fails(self):
        marker = b"\x00\x00\x00\x02\x00\x00\x00\x01\xb1\x00\x00"
        self.assertGreater(self.code.count(marker), 0)
        with self.assertRaisesRegex(ValueError, "only RETURN"):
            BUILDER.verify_artifact(self.jar(self.code.replace(marker, marker[:8] + b"\x00" + marker[9:])))

    def test_duplicate_class_entry_fails(self):
        with self.assertRaisesRegex(ValueError, "one NPC connection class"):
            BUILDER.verify_artifact(self.jar(self.code, duplicate=True))

    def test_compiler_output_mismatch_fails(self):
        with self.assertRaisesRegex(ValueError, "differs from compiler output"):
            BUILDER.verify_artifact(self.fixed, {BUILDER.QUEUE_HANDLER: b"stale compiler output"})

    def test_truncated_class_fails(self):
        with self.assertRaisesRegex(ValueError, "Truncated"):
            BUILDER.verify_artifact(self.jar(self.code[:100]))


if __name__ == "__main__":
    unittest.main()
