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
    def test_full_mode_runs_every_primary_lane(self):
        result = plan([], "full")
        self.assertEqual(set(result["selected_tests"]), {"Core Common", "Domain", "Data", "App"})
        self.assertEqual(result["selected_compiles"], [])
        self.assertTrue(result["run_format"])
        self.assertTrue(result["run_database"])
        self.assertTrue(result["run_supabase"])
        self.assertTrue(result["run_release"])
        self.assertFalse(result["run_native_package"])

    def test_tsuzuki_domain_test_change_stays_filtered_to_domain(self):
        result = plan(["domain/src/test/java/tachiyomi/domain/tsuzuki/FooTest.kt"], "affected")
        self.assertEqual(result["selected_tests"], ["Domain — Tsuzuki"])
        self.assertEqual(result["selected_compiles"], [])
        self.assertFalse(result["run_database"])

    def test_shared_domain_test_change_stays_in_full_domain_shard(self):
        result = plan(["domain/src/test/java/tachiyomi/domain/manga/FooTest.kt"], "affected")
        self.assertEqual(result["selected_tests"], ["Domain"])
        self.assertEqual(result["selected_compiles"], [])

    def test_tsuzuki_domain_production_change_runs_filtered_tests_and_app_compile(self):
        result = plan(["domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/Foo.kt"], "affected")
        self.assertEqual(set(result["selected_tests"]), {"Domain — Tsuzuki", "App — Tsuzuki"})
        self.assertEqual(result["selected_compiles"], [])
        self.assertFalse(result["run_database"])
        self.assertFalse(result["run_supabase"])
        self.assertFalse(result["run_release"])

    def test_shared_domain_production_change_runs_full_domain_tests_and_app_compile(self):
        result = plan(["domain/src/main/java/tachiyomi/domain/manga/Foo.kt"], "affected")
        self.assertEqual(result["selected_tests"], ["Domain"])
        self.assertEqual(result["selected_compiles"], ["App Compile"])

    def test_tsuzuki_data_production_change_runs_filtered_data_tests_and_app_compile(self):
        result = plan(["data/src/main/java/tachiyomi/data/tsuzuki/Foo.kt"], "affected")
        self.assertEqual(result["selected_tests"], ["Data — Tsuzuki"])
        self.assertEqual(result["selected_compiles"], ["App Compile"])

    def test_shared_data_production_change_runs_full_data_tests_and_app_compile(self):
        result = plan(["data/src/main/java/tachiyomi/data/manga/Foo.kt"], "affected")
        self.assertEqual(result["selected_tests"], ["Data"])
        self.assertEqual(result["selected_compiles"], ["App Compile"])

    def test_tsuzuki_app_change_runs_filtered_app_tests_without_redundant_compile(self):
        result = plan(["app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/Foo.kt"], "affected")
        self.assertEqual(result["selected_tests"], ["App — Tsuzuki"])
        self.assertEqual(result["selected_compiles"], [])

    def test_runtime_binding_gateway_change_runs_domain_and_app_tsuzuki_tests(self):
        result = plan(
            ["app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/MihonReadingSourceGateway.kt"],
            "affected",
        )
        self.assertEqual(set(result["selected_tests"]), {"Domain — Tsuzuki", "App — Tsuzuki"})
        self.assertEqual(result["selected_compiles"], [])

    def test_runtime_domain_binding_change_runs_domain_and_app_tests_and_compile(self):
        result = plan(
            ["domain/src/main/java/tachiyomi/domain/tsuzuki/content/interactor/ResolveContentBinding.kt"],
            "affected",
        )
        self.assertEqual(set(result["selected_tests"]), {"Domain — Tsuzuki", "App — Tsuzuki"})
        self.assertEqual(result["selected_compiles"], [])

    def test_runtime_selector_change_runs_both_tsuzuki_test_shards(self):
        result = plan(
            ["app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/content/ContentSelectorScreenModel.kt"],
            "affected",
        )
        self.assertEqual(set(result["selected_tests"]), {"Domain — Tsuzuki", "App — Tsuzuki"})

    def test_unrelated_ui_only_change_does_not_run_domain_runtime_tests(self):
        result = plan(
            ["app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/library/CanonicalLibraryScreenModel.kt"],
            "affected",
        )
        self.assertEqual(result["selected_tests"], ["App — Tsuzuki"])

    def test_tsuzuki_named_settings_screen_uses_filtered_app_shard(self):
        result = plan(["app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTsuzukiIntegrationsScreen.kt"], "affected")
        self.assertEqual(result["selected_tests"], ["App — Tsuzuki"])

    def test_shared_app_change_runs_full_app_tests(self):
        result = plan(["app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt"], "affected")
        self.assertEqual(result["selected_tests"], ["App"])
        self.assertEqual(result["selected_compiles"], [])

    def test_core_common_change_runs_core_tests_and_app_compile(self):
        result = plan(["core/common/src/main/java/mihon/core/common/Foo.kt"], "affected")
        self.assertEqual(result["selected_tests"], ["Core Common"])
        self.assertEqual(result["selected_compiles"], ["App Compile"])

    def test_core_metro_change_fails_safe_to_full(self):
        result = plan(["core/metro/src/main/java/mihon/core/metro/Foo.kt"], "affected")
        self.assertEqual(set(result["selected_tests"]), {"Core Common", "Domain", "Data", "App"})
        self.assertTrue(result["run_release"])

    def test_source_api_change_compiles_app_without_irrelevant_test_suites(self):
        result = plan(["source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/Foo.kt"], "affected")
        self.assertEqual(result["selected_tests"], [])
        self.assertEqual(result["selected_compiles"], ["App Compile"])

    def test_app_only_dependency_module_change_compiles_app(self):
        result = plan(["source-local/src/androidMain/kotlin/tachiyomi/source/local/Foo.kt"], "affected")
        self.assertEqual(result["selected_tests"], [])
        self.assertEqual(result["selected_compiles"], ["App Compile"])

    def test_tsuzuki_sqldelight_change_runs_filtered_data_tests_compile_and_migrations(self):
        result = plan(["data/src/main/sqldelight/tachiyomi/data/tsuzuki_content_bindings.sq"], "affected")
        self.assertEqual(result["selected_tests"], ["Data — Tsuzuki"])
        self.assertEqual(result["selected_compiles"], ["App Compile"])
        self.assertTrue(result["run_database"])

    def test_non_tsuzuki_sqldelight_change_runs_full_data_tests_compile_and_migrations(self):
        result = plan(["data/src/main/sqldelight/tachiyomi/data/mangas.sq"], "affected")
        self.assertEqual(result["selected_tests"], ["Data"])
        self.assertEqual(result["selected_compiles"], ["App Compile"])
        self.assertTrue(result["run_database"])

    def test_supabase_only_change_runs_backend_lane_without_gradle(self):
        result = plan(["supabase/migrations/20260920000100_sync.sql"], "affected")
        self.assertEqual(result["selected_tests"], [])
        self.assertEqual(result["selected_compiles"], [])
        self.assertFalse(result["run_format"])
        self.assertTrue(result["run_supabase"])
        self.assertFalse(result["run_database"])
        self.assertFalse(result["run_release"])

    def test_torrent_app_change_runs_filtered_app_tests_and_native_package_gate(self):
        result = plan(["app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/torrent/TorrentSessionController.kt"], "affected")
        self.assertEqual(result["selected_tests"], ["App — Tsuzuki"])
        self.assertTrue(result["run_native_package"])

    def test_torrent_domain_content_change_runs_domain_and_app_tests_and_native_gate(self):
        result = plan(["domain/src/main/java/tachiyomi/domain/tsuzuki/content/TorrentArtifactEngine.kt"], "affected")
        self.assertEqual(set(result["selected_tests"]), {"Domain — Tsuzuki", "App — Tsuzuki"})
        self.assertEqual(result["selected_compiles"], [])
        self.assertTrue(result["run_native_package"])

    def test_ci_configuration_change_uses_planner_self_test_only(self):
        result = plan([".github/workflows/ci-v2.yml"], "affected")
        self.assertEqual(result["selected_tests"], [])
        self.assertEqual(result["selected_compiles"], [])
        self.assertFalse(result["run_format"])
        self.assertFalse(result["run_database"])
        self.assertFalse(result["run_release"])

    def test_gradle_change_fails_safe_to_full_and_release(self):
        result = plan(["gradle/libs.versions.toml"], "affected")
        self.assertEqual(set(result["selected_tests"]), {"Core Common", "Domain", "Data", "App"})
        self.assertEqual(result["selected_compiles"], [])
        self.assertTrue(result["run_database"])
        self.assertTrue(result["run_supabase"])
        self.assertTrue(result["run_release"])

    def test_app_build_script_change_fails_safe_to_full_and_release(self):
        result = plan(["app/build.gradle.kts"], "affected")
        self.assertEqual(set(result["selected_tests"]), {"Core Common", "Domain", "Data", "App"})
        self.assertTrue(result["run_database"])
        self.assertTrue(result["run_release"])

    def test_data_build_script_change_fails_safe_to_full(self):
        result = plan(["data/build.gradle.kts"], "affected")
        self.assertEqual(set(result["selected_tests"]), {"Core Common", "Domain", "Data", "App"})
        self.assertTrue(result["run_database"])
        self.assertTrue(result["run_release"])

    def test_proguard_change_runs_app_tests_and_release_compile(self):
        result = plan(["app/proguard-rules.pro"], "affected")
        self.assertEqual(result["selected_tests"], ["App"])
        self.assertTrue(result["run_release"])

    def test_docs_only_needs_no_ci_work(self):
        result = plan(["README.md"], "affected")
        self.assertEqual(result["selected_tests"], [])
        self.assertEqual(result["selected_compiles"], [])
        self.assertFalse(result["run_format"])
        self.assertFalse(result["run_database"])
        self.assertFalse(result["run_supabase"])
        self.assertFalse(result["run_release"])

    def test_mixed_changes_union_tests_and_drop_redundant_app_compile(self):
        result = plan([
            "domain/src/main/java/tachiyomi/domain/tsuzuki/Foo.kt",
            "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt",
        ], "affected")
        self.assertEqual(set(result["selected_tests"]), {"Domain — Tsuzuki", "App"})
        self.assertEqual(result["selected_compiles"], [])

    def test_full_shard_dominates_filtered_shard_for_same_module(self):
        result = plan([
            "domain/src/main/java/tachiyomi/domain/tsuzuki/Foo.kt",
            "domain/src/main/java/tachiyomi/domain/manga/Bar.kt",
        ], "affected")
        self.assertEqual(result["selected_tests"], ["Domain"])

    def test_missing_diff_fails_safe_to_full(self):
        result = plan([], "affected")
        self.assertEqual(set(result["selected_tests"]), {"Core Common", "Domain", "Data", "App"})
        self.assertTrue(result["run_database"])
        self.assertTrue(result["run_release"])

    def test_unknown_path_fails_safe_to_full(self):
        result = plan(["scripts/new-build-tool.sh"], "affected")
        self.assertEqual(set(result["selected_tests"]), {"Core Common", "Domain", "Data", "App"})
        self.assertTrue(result["run_database"])
        self.assertTrue(result["run_release"])

    def test_research_documents_and_csv_data_do_not_trigger_release_build(self):
        result = plan(
            [
                "docs/research/overnight-runtime-e2e-report.md",
                "docs/research/overnight-runtime-e2e-results.csv",
            ],
            "affected",
        )
        self.assertEqual(result["selected_tests"], [])
        self.assertEqual(result["selected_compiles"], [])
        self.assertFalse(result["run_database"])
        self.assertFalse(result["run_supabase"])
        self.assertFalse(result["run_release"])


if __name__ == "__main__":
    unittest.main()
