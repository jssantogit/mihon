from pathlib import Path
import re
import sys

TRACKER_OAUTH_HOSTS = (
    "anilist-auth",
    "bangumi-auth",
    "mangabaka-auth",
    "myanimelist-auth",
    "shikimori-auth",
    "hikka-auth",
)

SIGNING_SECRET_NAMES = (
    "TSUZUKI_KEYSTORE_BASE64",
    "TSUZUKI_KEYSTORE_PASSWORD",
    "TSUZUKI_KEY_ALIAS",
    "TSUZUKI_KEY_PASSWORD",
)


def _read(root: Path, relative: str, errors: list[str]) -> str:
    path = root / relative
    if not path.is_file():
        errors.append(f"required contract file is missing: {relative}")
        return ""
    return path.read_text(encoding="utf-8")


def _intent_filter_has(manifest: str, *, scheme: str, host: str) -> bool:
    blocks = re.findall(r"<intent-filter\b[\s\S]*?</intent-filter>", manifest)
    scheme_marker = f'android:scheme="{scheme}"'
    host_marker = f'android:host="{host}"'
    return any(scheme_marker in block and host_marker in block for block in blocks)


def check_contract(root: Path) -> list[str]:
    root = Path(root)
    errors: list[str] = []

    build = _read(root, "app/build.gradle.kts", errors)
    manifest = _read(root, "app/src/main/AndroidManifest.xml", errors)
    app_info = _read(root, "app/src/main/java/eu/kanade/tachiyomi/AppInfo.kt", errors)
    backup_creator = _read(
        root,
        "app/src/main/java/eu/kanade/tachiyomi/data/backup/create/BackupCreator.kt",
        errors,
    )
    apk_workflow = _read(root, ".github/workflows/apk.yml", errors)
    about_screen = _read(
        root,
        "app/src/main/java/eu/kanade/presentation/more/settings/screen/about/AboutScreen.kt",
        errors,
    )
    more_screen = _read(
        root,
        "app/src/main/java/eu/kanade/presentation/more/MoreScreen.kt",
        errors,
    )
    base_strings = _read(
        root,
        "i18n/src/commonMain/moko-resources/base/strings.xml",
        errors,
    )
    settings = _read(root, "settings.gradle.kts", errors)
    readme = _read(root, "README.md", errors)

    if build:
        application_id = re.search(r'\bapplicationId\s*=\s*"([^"]+)"', build)
        if application_id is None or application_id.group(1) != "app.mihon":
            errors.append("applicationId must remain app.mihon")

        namespace = re.search(r'\bnamespace\s*=\s*"([^"]+)"', build)
        if namespace is None or namespace.group(1) != "eu.kanade.tachiyomi":
            errors.append("Android namespace must remain eu.kanade.tachiyomi")

    if manifest:
        if not _intent_filter_has(manifest, scheme="tachiyomi", host="add-repo"):
            errors.append("legacy tachiyomi://add-repo contract is missing")

        if not _intent_filter_has(manifest, scheme="mihon", host="extension-store"):
            errors.append("legacy mihon://extension-store contract is missing")

        if not _intent_filter_has(manifest, scheme="tsuzuki", host="auth"):
            errors.append("tsuzuki://auth callback contract is missing")

        for host in TRACKER_OAUTH_HOSTS:
            if not _intent_filter_has(manifest, scheme="mihon", host=host):
                errors.append(f"tracker OAuth host {host} is missing")

        if 'android:authorities="${applicationId}.provider"' not in manifest:
            errors.append("FileProvider authority must remain ${applicationId}.provider")

        if 'android:authorities="${applicationId}.shizuku"' not in manifest:
            errors.append("Shizuku authority must remain ${applicationId}.shizuku")

        if ".tachibk" not in manifest:
            errors.append("legacy .tachibk restore contract is missing")

    if app_info:
        if "package eu.kanade.tachiyomi" not in app_info or "object AppInfo" not in app_info:
            errors.append("extension-facing AppInfo namespace changed")

    if backup_creator:
        if "BuildConfig.APPLICATION_ID" not in backup_creator:
            errors.append("backup filename must continue to derive from BuildConfig.APPLICATION_ID")
        if ".tachibk" not in backup_creator:
            errors.append("backup filename must preserve .tachibk compatibility")

    if apk_workflow:
        for name in SIGNING_SECRET_NAMES:
            if f"secrets.{name}" not in apk_workflow:
                errors.append(f"APK workflow no longer references {name}")

    if about_screen:
        project_source = "https://github.com/jssantogit/tsuzuki"
        if project_source not in about_screen:
            errors.append("About screen must link to the Tsuzuki repository")
        if "https://github.com/jssantogit/mihon" in about_screen:
            errors.append("About screen still links to the pre-rename repository")

        inherited_public_markers = (
            "https://mihon.app",
            "Constants.URL_DISCORD",
            "https://x.com/mihonapp",
            "https://facebook.com/mihonapp",
            "https://www.reddit.com/r/mihonapp",
        )
        if any(marker in about_screen for marker in inherited_public_markers):
            errors.append("About screen exposes Mihon-owned public identity links")

        inherited_release_markers = (
            "RELEASE_URL",
            "updaterEnabled",
            "check_for_updates",
            "NewUpdateScreen",
        )
        if any(marker in about_screen for marker in inherited_release_markers):
            errors.append("About screen still exposes the inherited Mihon release channel")

    if more_screen:
        inherited_more_markers = (
            "label_support_us",
            "Constants.URL_HELP",
            "onClickSupport",
            "VolunteerActivism",
        )
        if any(marker in more_screen for marker in inherited_more_markers):
            errors.append("More screen still exposes inherited Mihon support/help entry points")

    if base_strings:
        app_name = re.search(
            r'<string\s+name="app_name"[^>]*>([^<]+)</string>',
            base_strings,
        )
        if app_name is None or app_name.group(1).strip() != "Tsuzuki":
            errors.append("base app_name must be Tsuzuki")

    if settings:
        project_name = re.search(r'rootProject\.name\s*=\s*"([^"]+)"', settings)
        if project_name is None or project_name.group(1) != "Tsuzuki":
            errors.append("rootProject.name must be Tsuzuki")

    if readme and not readme.lstrip().startswith("# Tsuzuki"):
        errors.append("README must identify Tsuzuki as the project")

    legacy_release = root / ".github/workflows/release.yml"
    if legacy_release.is_file():
        release_text = legacy_release.read_text(encoding="utf-8")
        if "mihonapp/mihon" in release_text or "name: Mihon " in release_text:
            errors.append("inherited Mihon release workflow is active")

    legacy_website = root / ".github/workflows/update_website.yml"
    if legacy_website.is_file():
        website_text = legacy_website.read_text(encoding="utf-8")
        if "mihonapp/website" in website_text:
            errors.append("inherited Mihon website workflow is active")

    return errors


def main() -> int:
    repo_root = Path(__file__).resolve().parents[2]
    errors = check_contract(repo_root)

    if errors:
        print("Tsuzuki identity compatibility contract FAILED:")
        for error in errors:
            print(f"- {error}")
        return 1

    print("Tsuzuki identity compatibility contract OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
