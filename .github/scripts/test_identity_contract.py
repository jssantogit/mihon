import importlib.util
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("identity_contract.py")
SPEC = importlib.util.spec_from_file_location("identity_contract", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)

check_contract = MODULE.check_contract


VALID_BUILD = """
android {
    namespace = "eu.kanade.tachiyomi"
    defaultConfig {
        applicationId = "app.tsuzuki"
    }
}
"""

VALID_MANIFEST = """
<manifest>
    <application>
        <activity>
            <intent-filter>
                <data android:scheme="tachiyomi" />
                <data android:host="add-repo" />
            </intent-filter>
            <intent-filter>
                <data android:scheme="mihon" />
                <data android:host="extension-store" />
            </intent-filter>
            <intent-filter>
                <data android:host="auth" android:scheme="tsuzuki" />
            </intent-filter>
        </activity>
        <activity>
            <intent-filter>
                <data android:scheme="mihon" />
                <data android:host="bangumi-auth" />
                <data android:host="myanimelist-auth" />
                <data android:host="shikimori-auth" />
                <data android:host="hikka-auth" />
            </intent-filter>
        </activity>
        <provider android:authorities="${applicationId}.provider" />
        <provider android:authorities="${applicationId}.shizuku" />
        <data android:pathPattern=".*\\.tachibk" />
    </application>
</manifest>
"""

VALID_APP_INFO = """package eu.kanade.tachiyomi

object AppInfo
"""

VALID_BACKUP_CREATOR = '''
private val FILENAME_REGEX = """${BuildConfig.APPLICATION_ID}_\\d{4}.tachibk""".toRegex()
fun getFilename(): String = "${BuildConfig.APPLICATION_ID}.tachibk"
'''

VALID_APK_WORKFLOW = """
env:
  TSUZUKI_KEYSTORE_BASE64: ${{ secrets.TSUZUKI_KEYSTORE_BASE64 }}
  TSUZUKI_KEYSTORE_PASSWORD: ${{ secrets.TSUZUKI_KEYSTORE_PASSWORD }}
  TSUZUKI_KEY_ALIAS: ${{ secrets.TSUZUKI_KEY_ALIAS }}
  TSUZUKI_KEY_PASSWORD: ${{ secrets.TSUZUKI_KEY_PASSWORD }}
flags: -Penable-updater
"""

VALID_CI_WORKFLOW = """
run: ./gradlew assembleRelease -Penable-updater
"""

VALID_ABOUT_SCREEN = """
val projectSource = "https://github.com/jssantogit/tsuzuki"
val upstream = "https://github.com/mihonapp/mihon"
"""

VALID_BASE_STRINGS = """
<resources>
    <string name="app_name" translatable="false">Tsuzuki</string>
</resources>
"""

VALID_SETTINGS = """
rootProject.name = "Tsuzuki"
"""

VALID_README = """
<div align="center">
<picture>
docs/brand/tsuzuki-lockup-paper.svg
docs/brand/tsuzuki-lockup.svg
<img alt="Tsuzuki" />
</picture>
</div>

## Get Tsuzuki
"""


VALID_BRAND_COLORS = """
<resources>
    <color name="splash">@color/tsuzuki_ink</color>
    <color name="tsuzuki_ink">#0B0C0D</color>
    <color name="tsuzuki_paper">#F5F3EC</color>
    <color name="tsuzuki_graphite">#242628</color>
    <color name="tsuzuki_ash">#7D8185</color>
    <color name="tsuzuki_mist">#C9CCCE</color>
</resources>
"""

VALID_LAUNCHER_BACKGROUND = """
<path android:fillColor="@color/tsuzuki_ink" />
"""

VALID_LAUNCHER_FOREGROUND = """
<group android:scaleX="0.661765" android:scaleY="0.661765">
    <path android:fillColor="@color/tsuzuki_paper" />
</group>
"""

VALID_LAUNCHER_MONOCHROME = """
<group android:scaleX="0.661765" android:scaleY="0.661765">
    <path android:fillColor="#FFFFFFFF" />
</group>
"""

VALID_BRAND_MARK = """
<path android:fillColor="@color/tsuzuki_paper" android:pathData="top" />
<path android:fillColor="@color/tsuzuki_paper" android:pathData="left" />
<path android:fillColor="@color/tsuzuki_paper" android:pathData="right" />
"""

VALID_BRAND_LOCKUP = """
<path android:fillColor="@color/tsuzuki_paper" android:pathData="1" />
<path android:fillColor="@color/tsuzuki_paper" android:pathData="2" />
<path android:fillColor="@color/tsuzuki_paper" android:pathData="3" />
<path android:fillColor="@color/tsuzuki_paper" android:pathData="4" />
<path android:fillColor="@color/tsuzuki_paper" android:pathData="5" />
<path android:fillColor="@color/tsuzuki_paper" android:pathData="6" />
<path android:fillColor="@color/tsuzuki_paper" android:pathData="7" />
<path android:fillColor="@color/tsuzuki_paper" android:pathData="8" />
<path android:fillColor="@color/tsuzuki_paper" android:pathData="9" />
<path android:fillColor="@color/tsuzuki_paper" android:pathData="10" />
"""

VALID_MASTER_MARK = """
<svg>
  <g fill="#0B0C0D">
    <path id="top" />
    <path id="left" />
    <path id="right" />
  </g>
</svg>
"""

VALID_BRAND_DOC = """
# Brand v3
Canonical asset: tsuzuki-mark.svg
A2 — Organic T
Canonical lockup: tsuzuki-lockup.svg
- Tsuzuki Ink: `#0B0C0D`
- Tsuzuki Paper: `#F5F3EC`
"""

VALID_REPO_LOGO = """
<rect fill="#0B0C0D" />
<g fill="#F5F3EC" />
"""


class IdentityContractTest(unittest.TestCase):
    def make_repo(
        self,
        *,
        build=VALID_BUILD,
        manifest=VALID_MANIFEST,
        app_info=VALID_APP_INFO,
        backup_creator=VALID_BACKUP_CREATOR,
        apk_workflow=VALID_APK_WORKFLOW,
        ci_workflow=VALID_CI_WORKFLOW,
        about_screen=VALID_ABOUT_SCREEN,
        base_strings=VALID_BASE_STRINGS,
        settings=VALID_SETTINGS,
        readme=VALID_README,
        brand_colors=VALID_BRAND_COLORS,
        launcher_background=VALID_LAUNCHER_BACKGROUND,
        launcher_foreground=VALID_LAUNCHER_FOREGROUND,
        launcher_monochrome=VALID_LAUNCHER_MONOCHROME,
        brand_mark=VALID_BRAND_MARK,
        brand_lockup=VALID_BRAND_LOCKUP,
        master_mark=VALID_MASTER_MARK,
        brand_doc=VALID_BRAND_DOC,
        repo_logo=VALID_REPO_LOGO,
    ):
        temp_dir = tempfile.TemporaryDirectory()
        root = Path(temp_dir.name)
        files = {
            "app/build.gradle.kts": build,
            "app/src/main/AndroidManifest.xml": manifest,
            "app/src/main/java/eu/kanade/tachiyomi/AppInfo.kt": app_info,
            "app/src/main/java/eu/kanade/tachiyomi/data/backup/create/BackupCreator.kt": backup_creator,
            ".github/workflows/apk.yml": apk_workflow,
            ".github/workflows/ci-v2.yml": ci_workflow,
            "app/src/main/java/eu/kanade/presentation/more/settings/screen/about/AboutScreen.kt": about_screen,
            "i18n/src/commonMain/moko-resources/base/strings.xml": base_strings,
            "settings.gradle.kts": settings,
            "README.md": readme,
            "app/src/main/res/values/colors.xml": brand_colors,
            "app/src/main/res/drawable/ic_launcher_background.xml": launcher_background,
            "app/src/main/res/drawable/ic_launcher_foreground.xml": launcher_foreground,
            "app/src/main/res/drawable/ic_launcher_monochrome.xml": launcher_monochrome,
            "app/src/main/res/drawable/ic_mihon.xml": brand_mark,
            "app/src/main/res/drawable/ic_tsuzuki_lockup.xml": brand_lockup,
            "docs/brand/tsuzuki-mark.svg": master_mark,
            "docs/brand/README.md": brand_doc,
            "docs/brand/tsuzuki-repo-logo.svg": repo_logo,
        }
        for relative, content in files.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        self.addCleanup(temp_dir.cleanup)
        return root

    def test_current_compatibility_contract_is_accepted(self):
        self.assertEqual(check_contract(self.make_repo()), [])

    def test_application_id_change_is_rejected(self):
        root = self.make_repo(build=VALID_BUILD.replace('"app.tsuzuki"', '"app.mihon"'))
        self.assertIn("applicationId must be app.tsuzuki", check_contract(root))

    def test_legacy_or_duplicated_application_ids_are_rejected(self):
        legacy = self.make_repo(build=VALID_BUILD + '\nval old = "app.mihon"\n')
        self.assertIn("build configuration must not retain app.mihon as an application ID", check_contract(legacy))

        duplicated = self.make_repo(build=VALID_BUILD + '\napplicationIdSuffix = ".tsuzuki.deva"\n')
        self.assertIn("Tsuzuki application ID suffixes must not duplicate the tsuzuki segment", check_contract(duplicated))

    def test_android_namespace_change_is_rejected(self):
        root = self.make_repo(build=VALID_BUILD.replace('"eu.kanade.tachiyomi"', '"app.tsuzuki"'))
        self.assertIn("Android namespace must remain eu.kanade.tachiyomi", check_contract(root))

    def test_legacy_extension_store_schemes_are_required(self):
        root = self.make_repo(manifest=VALID_MANIFEST.replace('android:scheme="tachiyomi"', 'android:scheme="other"'))
        self.assertIn("legacy tachiyomi://add-repo contract is missing", check_contract(root))

        root = self.make_repo(manifest=VALID_MANIFEST.replace('android:host="extension-store"', 'android:host="other"'))
        self.assertIn("legacy mihon://extension-store contract is missing", check_contract(root))

    def test_tsuzuki_auth_callback_is_required(self):
        root = self.make_repo(manifest=VALID_MANIFEST.replace('android:host="auth" android:scheme="tsuzuki"', 'android:host="auth" android:scheme="other"'))
        self.assertIn("tsuzuki://auth callback contract is missing", check_contract(root))

    def test_tracker_oauth_hosts_remain_on_mihon_scheme(self):
        root = self.make_repo(manifest=VALID_MANIFEST.replace('android:host="hikka-auth"', 'android:host="other-auth"'))
        self.assertIn("tracker OAuth host hikka-auth is missing", check_contract(root))

    def test_retired_tracker_oauth_hosts_stay_absent(self):
        retired_manifest = VALID_MANIFEST.replace(
            '<data android:host="bangumi-auth" />',
            '<data android:host="bangumi-auth" />\n                <data android:host="anilist-auth" />',
        )
        root = self.make_repo(manifest=retired_manifest)
        self.assertIn("retired tracker OAuth host anilist-auth must remain absent", check_contract(root))

    def test_provider_authorities_continue_to_follow_application_id(self):
        root = self.make_repo(manifest=VALID_MANIFEST.replace('${applicationId}.provider', 'app.tsuzuki.provider'))
        self.assertIn("FileProvider authority must remain ${applicationId}.provider", check_contract(root))

        root = self.make_repo(manifest=VALID_MANIFEST.replace('${applicationId}.shizuku', 'app.tsuzuki.shizuku'))
        self.assertIn("Shizuku authority must remain ${applicationId}.shizuku", check_contract(root))

    def test_extension_facing_app_info_namespace_is_required(self):
        root = self.make_repo(app_info=VALID_APP_INFO.replace("package eu.kanade.tachiyomi", "package app.tsuzuki"))
        self.assertIn("extension-facing AppInfo namespace changed", check_contract(root))

    def test_tachibk_backup_compatibility_is_required(self):
        root = self.make_repo(manifest=VALID_MANIFEST.replace("tachibk", "tsuzukibk"))
        self.assertIn("legacy .tachibk restore contract is missing", check_contract(root))

        root = self.make_repo(backup_creator=VALID_BACKUP_CREATOR.replace("BuildConfig.APPLICATION_ID", '"tsuzuki"'))
        self.assertIn("backup filename must continue to derive from BuildConfig.APPLICATION_ID", check_contract(root))

    def test_inherited_mihon_telemetry_is_rejected(self):
        root = self.make_repo(
            apk_workflow=VALID_APK_WORKFLOW + "\nflags: -Pinclude-telemetry\n",
            ci_workflow=VALID_CI_WORKFLOW + "\nrun: ./gradlew assembleRelease -Pinclude-telemetry\n",
        )
        google_services = root / "app/google-services.json"
        google_services.parent.mkdir(parents=True, exist_ok=True)
        google_services.write_text('{"project_id":"mihonapp"}', encoding="utf-8")
        errors = check_contract(root)
        self.assertIn("official APK workflow must not enable inherited Mihon telemetry", errors)
        self.assertIn("official CI release compile must not enable inherited Mihon telemetry", errors)
        self.assertIn("inherited Mihon Firebase configuration must remain absent", errors)

    def test_persistent_tsuzuki_signing_secret_names_are_required(self):
        root = self.make_repo(apk_workflow=VALID_APK_WORKFLOW.replace("TSUZUKI_KEY_ALIAS", "NEW_KEY_ALIAS"))
        self.assertIn("APK workflow no longer references TSUZUKI_KEY_ALIAS", check_contract(root))

    def test_about_screen_requires_current_tsuzuki_repository(self):
        root = self.make_repo(
            about_screen=VALID_ABOUT_SCREEN.replace("jssantogit/tsuzuki", "jssantogit/mihon"),
        )
        errors = check_contract(root)
        self.assertIn("About screen must link to the Tsuzuki repository", errors)
        self.assertIn("About screen still links to the pre-rename repository", errors)

    def test_about_screen_rejects_mihon_public_identity_links(self):
        inherited = VALID_ABOUT_SCREEN + '\nval site = "https://mihon.app"\n'
        root = self.make_repo(about_screen=inherited)
        self.assertIn("About screen exposes Mihon-owned public identity links", check_contract(root))

    def test_about_screen_rejects_mihon_release_channel(self):
        root = self.make_repo(about_screen=VALID_ABOUT_SCREEN + "\nval release = RELEASE_URL\n")
        self.assertIn("About screen still exposes the inherited Mihon release channel", check_contract(root))

    def test_retired_legacy_ui_surfaces_remain_absent(self):
        root = self.make_repo()
        retired = root / "app/src/main/java/eu/kanade/tachiyomi/ui/history/HistoryTab.kt"
        retired.parent.mkdir(parents=True, exist_ok=True)
        retired.write_text("data object HistoryTab", encoding="utf-8")
        self.assertIn(
            "retired legacy UI surface must remain absent: app/src/main/java/eu/kanade/tachiyomi/ui/history/HistoryTab.kt",
            check_contract(root),
        )

    def test_retired_launcher_shortcuts_remain_absent(self):
        root = self.make_repo()
        shortcuts = root / "app/src/main/shortcuts.xml"
        shortcuts.parent.mkdir(parents=True, exist_ok=True)
        shortcuts.write_text(
            '<shortcut android:shortcutId="show_recently_read" />',
            encoding="utf-8",
        )
        self.assertIn(
            "retired launcher shortcut must remain absent: show_recently_read",
            check_contract(root),
        )

    def test_base_product_name_must_be_tsuzuki(self):
        root = self.make_repo(base_strings=VALID_BASE_STRINGS.replace(">Tsuzuki<", ">Mihon<"))
        self.assertIn("base app_name must be Tsuzuki", check_contract(root))

    def test_gradle_project_name_must_be_tsuzuki(self):
        root = self.make_repo(settings=VALID_SETTINGS.replace('"Tsuzuki"', '"Mihon"'))
        self.assertIn("rootProject.name must be Tsuzuki", check_contract(root))

    def test_readme_must_identify_tsuzuki(self):
        root = self.make_repo(readme=VALID_README.replace('alt="Tsuzuki"', 'alt="Mihon"'))
        self.assertIn("README must identify Tsuzuki in the repository lockup", check_contract(root))

    def test_brand_v2_palette_is_required(self):
        root = self.make_repo(
            brand_colors=VALID_BRAND_COLORS.replace("#0B0C0D", "#132235", 1),
        )
        self.assertIn("brand color tsuzuki_ink must be #0B0C0D", check_contract(root))

    def test_primary_brand_assets_reject_legacy_jade_midnight_signature(self):
        root = self.make_repo(
            launcher_foreground=VALID_LAUNCHER_FOREGROUND.replace("tsuzuki_paper", "tsuzuki_jade"),
            brand_mark=VALID_BRAND_MARK.replace("tsuzuki_paper", "tsuzuki_jade"),
            repo_logo=VALID_REPO_LOGO.replace("#0B0C0D", "#132235").replace("#F5F3EC", "#17C7A3"),
        )
        errors = check_contract(root)
        self.assertIn("launcher foreground must use Tsuzuki Paper", errors)
        self.assertIn("launcher foreground still uses legacy Tsuzuki Jade", errors)
        self.assertIn("primary in-app brand mark must use Tsuzuki Paper", errors)
        self.assertIn("primary in-app brand mark still uses legacy Tsuzuki Jade", errors)
        self.assertIn("repository logo must use Paper on Ink", errors)
        self.assertIn("repository logo still uses the legacy Jade/Midnight signature", errors)

    def test_brand_v3_master_must_be_three_path_true_vector(self):
        root = self.make_repo(master_mark=VALID_MASTER_MARK.replace('<path id="right" />', '<image href="mark.png" />'))
        errors = check_contract(root)
        self.assertIn("Brand v3 master mark is missing right mass", errors)
        self.assertIn("Brand v3 master mark must remain true vector geometry", errors)

    def test_brand_v3_a2_lockup_is_required(self):
        root = self.make_repo(
            readme=VALID_README.replace("docs/brand/tsuzuki-lockup.svg", "docs/brand/old-logo.svg"),
            brand_lockup=VALID_BRAND_LOCKUP.replace(' android:pathData="10"', ""),
        )
        errors = check_contract(root)
        self.assertIn("README must use the Brand v3 A2 repository lockup", errors)
        self.assertIn("Brand v3 A2 About lockup must contain three mark masses plus seven wordmark glyphs", errors)

    def test_brand_v3_launcher_calibration_is_required(self):
        root = self.make_repo(
            launcher_foreground=VALID_LAUNCHER_FOREGROUND.replace("0.661765", "0.625"),
            launcher_monochrome=VALID_LAUNCHER_MONOCHROME.replace("0.661765", "0.625"),
        )
        errors = check_contract(root)
        self.assertIn("launcher foreground must use the Brand v3 45% calibration", errors)
        self.assertIn("launcher monochrome must use the Brand v3 45% calibration", errors)

    def test_inherited_mihon_release_workflows_are_rejected(self):
        root = self.make_repo()
        release = root / ".github/workflows/release.yml"
        release.parent.mkdir(parents=True, exist_ok=True)
        release.write_text("if: github.repository == 'mihonapp/mihon'\n", encoding="utf-8")
        self.assertIn("inherited Mihon release workflow is active", check_contract(root))

        release.unlink()
        website = root / ".github/workflows/update_website.yml"
        website.write_text("https://api.github.com/repos/mihonapp/website/dispatches\n", encoding="utf-8")
        self.assertIn("inherited Mihon website workflow is active", check_contract(root))


if __name__ == "__main__":
    unittest.main()
