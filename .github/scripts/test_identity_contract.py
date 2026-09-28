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
        applicationId = "app.mihon"
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
                <data android:host="anilist-auth" />
                <data android:host="bangumi-auth" />
                <data android:host="mangabaka-auth" />
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
"""

VALID_ABOUT_SCREEN = """
val projectSource = "https://github.com/jssantogit/mihon"
val upstream = "https://github.com/mihonapp/mihon"
"""

VALID_MORE_SCREEN = """
fun MoreScreen() {
    // Tsuzuki-owned navigation only.
}
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
        about_screen=VALID_ABOUT_SCREEN,
        more_screen=VALID_MORE_SCREEN,
    ):
        temp_dir = tempfile.TemporaryDirectory()
        root = Path(temp_dir.name)
        files = {
            "app/build.gradle.kts": build,
            "app/src/main/AndroidManifest.xml": manifest,
            "app/src/main/java/eu/kanade/tachiyomi/AppInfo.kt": app_info,
            "app/src/main/java/eu/kanade/tachiyomi/data/backup/create/BackupCreator.kt": backup_creator,
            ".github/workflows/apk.yml": apk_workflow,
            "app/src/main/java/eu/kanade/presentation/more/settings/screen/about/AboutScreen.kt": about_screen,
            "app/src/main/java/eu/kanade/presentation/more/MoreScreen.kt": more_screen,
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
        root = self.make_repo(build=VALID_BUILD.replace('"app.mihon"', '"app.tsuzuki"'))
        self.assertIn("applicationId must remain app.mihon", check_contract(root))

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

    def test_persistent_tsuzuki_signing_secret_names_are_required(self):
        root = self.make_repo(apk_workflow=VALID_APK_WORKFLOW.replace("TSUZUKI_KEY_ALIAS", "NEW_KEY_ALIAS"))
        self.assertIn("APK workflow no longer references TSUZUKI_KEY_ALIAS", check_contract(root))

    def test_about_screen_rejects_mihon_public_identity_links(self):
        inherited = VALID_ABOUT_SCREEN + '\nval site = "https://mihon.app"\n'
        root = self.make_repo(about_screen=inherited)
        self.assertIn("About screen exposes Mihon-owned public identity links", check_contract(root))

    def test_about_screen_rejects_mihon_release_channel(self):
        root = self.make_repo(about_screen=VALID_ABOUT_SCREEN + "\nval release = RELEASE_URL\n")
        self.assertIn("About screen still exposes the inherited Mihon release channel", check_contract(root))

    def test_more_screen_rejects_inherited_support_and_help_entries(self):
        inherited = VALID_MORE_SCREEN + "\nval support = MR.strings.label_support_us\nval help = Constants.URL_HELP\n"
        root = self.make_repo(more_screen=inherited)
        errors = check_contract(root)
        self.assertIn("More screen still exposes inherited Mihon support/help entry points", errors)


if __name__ == "__main__":
    unittest.main()
