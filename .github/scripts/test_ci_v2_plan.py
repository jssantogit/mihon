#!/usr/bin/env python3
import importlib.util
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("ci_v2_plan.py")
SPEC = importlib.util.spec_from_file_location("ci_v2_plan", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)
plan = MODULE.plan


class PlannerTest(unittest.TestCase):
    def selected(self, paths, mode="affected"):
        return set(plan(paths, mode)["selected"])

    def test_full_mode_runs_everything_and_database(self):
        result = plan([], "full")
        self.assertEqual(
            set(result["selected"]),
            {"Core Common", "Domain", "Data", "App"},
        )
        self.assertTrue(result["run_database"])
        self.assertTrue(result["run_format"])

    def test_domain_test_change_stays_in_domain(self):
        result = plan(["domain/src/test/java/example/FooTest.kt"], "affected")
        self.assertEqual(set(result["selected"]), {"Domain"})
        self.assertFalse(result["run_database"])

    def test_domain_production_change_closes_over_dependents(self):
        self.assertEqual(
            self.selected(["domain/src/main/java/example/Foo.kt"]),
            {"Domain", "Data", "App"},
        )

    def test_data_production_change_runs_data_and_app(self):
        self.assertEqual(
            self.selected(["data/src/main/java/example/Foo.kt"]),
            {"Data", "App"},
        )

    def test_app_test_change_only_runs_app(self):
        self.assertEqual(
            self.selected(["app/src/test/java/example/FooTest.kt"]),
            {"App"},
        )

    def test_core_common_change_runs_all_dependents(self):
        self.assertEqual(
            self.selected(["core/common/src/main/java/example/Foo.kt"]),
            {"Core Common", "Domain", "Data", "App"},
        )

    def test_source_api_change_runs_all_consumers(self):
        self.assertEqual(
            self.selected(["source-api/src/main/java/example/Source.kt"]),
            {"Domain", "Data", "App"},
        )

    def test_sqldelight_change_runs_database_and_consumers(self):
        result = plan(["data/src/main/sqldelight/tachiyomi/data/mangas.sq"], "affected")
        self.assertEqual(set(result["selected"]), {"Data", "App"})
        self.assertTrue(result["run_database"])

    def test_gradle_change_fails_safe_to_full(self):
        result = plan(["gradle/libs.versions.toml"], "affected")
        self.assertEqual(
            set(result["selected"]),
            {"Core Common", "Domain", "Data", "App"},
        )
        self.assertTrue(result["run_database"])

    def test_unknown_path_fails_safe_to_full(self):
        result = plan(["scripts/new-build-tool.sh"], "affected")
        self.assertEqual(
            set(result["selected"]),
            {"Core Common", "Domain", "Data", "App"},
        )
        self.assertTrue(result["run_database"])

    def test_docs_only_needs_no_gradle_work(self):
        result = plan(["README.md"], "affected")
        self.assertEqual(result["selected"], [])
        self.assertFalse(result["run_database"])
        self.assertFalse(result["run_format"])

    def test_multiple_changes_union_their_affected_modules(self):
        self.assertEqual(
            self.selected([
                "domain/src/test/java/example/FooTest.kt",
                "app/src/main/java/example/Screen.kt",
            ]),
            {"Domain", "App"},
        )


if __name__ == "__main__":
    unittest.main()
