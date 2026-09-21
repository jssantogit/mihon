import importlib.util
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("apk_lane.py")
SPEC = importlib.util.spec_from_file_location("apk_lane", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)
resolve_lane = MODULE.resolve_lane


class ApkLaneTest(unittest.TestCase):
    def test_bootstrap_and_main_use_release(self):
        for branch in ("main", "tsuzuki/bootstrap"):
            self.assertEqual(resolve_lane(branch)["lane"], "release")

    def test_runtime_v2_dev_branches_use_isolated_variants(self):
        self.assertEqual(resolve_lane("tsuzuki/runtime-v2-dev-a")["task"], "assembleDeva")
        self.assertEqual(resolve_lane("tsuzuki/runtime-v2-dev-b-fix")["task"], "assembleDevb")
        self.assertEqual(resolve_lane("tsuzuki/runtime-v2-dev-c")["task"], "assembleDevc")

    def test_runtime_v2_integration_and_torrent_use_release(self):
        self.assertEqual(resolve_lane("tsuzuki/runtime-v2-integration")["task"], "assembleRelease")
        self.assertEqual(resolve_lane("tsuzuki/runtime-v2-torrent-jni")["task"], "assembleRelease")

    def test_legacy_dev_branch_patterns_remain_supported(self):
        self.assertEqual(resolve_lane("tsuzuki/unified-library-dev-a-foo")["lane"], "dev-a")
        self.assertEqual(resolve_lane("tsuzuki/mvp-v2-canonical")["lane"], "dev-b")
        self.assertEqual(resolve_lane("tsuzuki/mvp-v3-sync")["lane"], "dev-c")

    def test_unsupported_branch_is_rejected(self):
        with self.assertRaises(ValueError):
            resolve_lane("tsuzuki/random-experiment")


if __name__ == "__main__":
    unittest.main()
