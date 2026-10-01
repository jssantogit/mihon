from pathlib import Path
import re
import sys

TRACKER_OAUTH_HOSTS = (
    "bangumi-auth",
    "myanimelist-auth",
    "shikimori-auth",
    "hikka-auth",
)

RETIRED_TRACKER_OAUTH_HOSTS = (
    "anilist-auth",
    "mangabaka-auth",
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
    ci_workflow = _read(root, ".github/workflows/ci-v2.yml", errors)
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
    brand_colors = _read(root, "app/src/main/res/values/colors.xml", errors)
    launcher_background = _read(
        root,
        "app/src/main/res/drawable/ic_launcher_background.xml",
        errors,
    )
    launcher_foreground = _read(
        root,
        "app/src/main/res/drawable/ic_launcher_foreground.xml",
        errors,
    )
    launcher_monochrome = _read(
        root,
        "app/src/main/res/drawable/ic_launcher_monochrome.xml",
        errors,
    )
    brand_mark = _read(root, "app/src/main/res/drawable/ic_mihon.xml", errors)
    brand_lockup = _read(
        root,
        "app/src/main/res/drawable/ic_tsuzuki_lockup.xml",
        errors,
    )
    brand_doc = _read(root, "docs/brand/README.md", errors)
    master_mark = _read(root, "docs/brand/tsuzuki-mark.svg", errors)
    repo_logo = _read(root, "docs/brand/tsuzuki-repo-logo.svg", errors)

    if build:
        application_id = re.search(r'\bapplicationId\s*=\s*"([^"]+)"', build)
        if application_id is None or application_id.group(1) != "app.tsuzuki":
            errors.append("applicationId must be app.tsuzuki")

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

        for host in RETIRED_TRACKER_OAUTH_HOSTS:
            if _intent_filter_has(manifest, scheme="mihon", host=host):
                errors.append(f"retired tracker OAuth host {host} must remain absent")

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
        if "-Pinclude-telemetry" in apk_workflow:
            errors.append("official APK workflow must not enable inherited Mihon telemetry")

    if ci_workflow and "-Pinclude-telemetry" in ci_workflow:
        errors.append("official CI release compile must not enable inherited Mihon telemetry")

    if (root / "app/google-services.json").is_file():
        errors.append("inherited Mihon Firebase configuration must remain absent")

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

    if readme:
        if not readme.lstrip().startswith("# Tsuzuki"):
            errors.append("README must identify Tsuzuki as the project")
        if "docs/brand/tsuzuki-lockup.svg" not in readme or "docs/brand/tsuzuki-lockup-paper.svg" not in readme:
            errors.append("README must use the Brand v3 A2 repository lockup")

    if brand_colors:
        required_colors = {
            "tsuzuki_ink": "#0B0C0D",
            "tsuzuki_paper": "#F5F3EC",
            "tsuzuki_graphite": "#242628",
            "tsuzuki_ash": "#7D8185",
            "tsuzuki_mist": "#C9CCCE",
        }
        for name, value in required_colors.items():
            marker = f'<color name="{name}">{value}</color>'
            if marker not in brand_colors:
                errors.append(f"brand color {name} must be {value}")
        if '<color name="splash">@color/tsuzuki_ink</color>' not in brand_colors:
            errors.append("splash brand background must use Tsuzuki Ink")

    if launcher_background and "@color/tsuzuki_ink" not in launcher_background:
        errors.append("launcher background must use Tsuzuki Ink")

    if launcher_foreground:
        if "@color/tsuzuki_paper" not in launcher_foreground:
            errors.append("launcher foreground must use Tsuzuki Paper")
        if "@color/tsuzuki_jade" in launcher_foreground:
            errors.append("launcher foreground still uses legacy Tsuzuki Jade")
        if 'android:scaleX="0.661765"' not in launcher_foreground or 'android:scaleY="0.661765"' not in launcher_foreground:
            errors.append("launcher foreground must use the Brand v3 45% calibration")

    if launcher_monochrome:
        if "#FFFFFFFF" not in launcher_monochrome:
            errors.append("launcher monochrome mark must be white")
        if 'android:scaleX="0.661765"' not in launcher_monochrome or 'android:scaleY="0.661765"' not in launcher_monochrome:
            errors.append("launcher monochrome must use the Brand v3 45% calibration")

    if brand_mark:
        if "@color/tsuzuki_paper" not in brand_mark:
            errors.append("primary in-app brand mark must use Tsuzuki Paper")
        if "@color/tsuzuki_jade" in brand_mark:
            errors.append("primary in-app brand mark still uses legacy Tsuzuki Jade")
        if brand_mark.count("android:pathData=") != 3:
            errors.append("primary in-app Brand v3 mark must contain exactly three masses")

    if brand_lockup:
        if brand_lockup.count("android:pathData=") != 10:
            errors.append("Brand v3 A2 About lockup must contain three mark masses plus seven wordmark glyphs")
        if "@color/tsuzuki_paper" not in brand_lockup:
            errors.append("Brand v3 A2 About lockup must use Tsuzuki Paper before Compose tinting")

    if master_mark:
        for part in ("top", "left", "right"):
            if f'<path id="{part}"' not in master_mark:
                errors.append(f"Brand v3 master mark is missing {part} mass")
        if 'fill="#0B0C0D"' not in master_mark:
            errors.append("Brand v3 master mark must use Tsuzuki Ink")
        if "<image" in master_mark:
            errors.append("Brand v3 master mark must remain true vector geometry")

    if brand_doc:
        if "Brand v3" not in brand_doc or "tsuzuki-mark.svg" not in brand_doc:
            errors.append("brand documentation must identify the current Brand v3 master")
        if "A2 — Organic T" not in brand_doc or "tsuzuki-lockup.svg" not in brand_doc:
            errors.append("brand documentation must identify the approved A2 wordmark lockup")
        if "Tsuzuki Ink: `#0B0C0D`" not in brand_doc or "Tsuzuki Paper: `#F5F3EC`" not in brand_doc:
            errors.append("brand documentation must define the monochrome signature")

    if repo_logo:
        if '#0B0C0D' not in repo_logo or '#F5F3EC' not in repo_logo:
            errors.append("repository logo must use Paper on Ink")
        if '#17C7A3' in repo_logo or '#132235' in repo_logo:
            errors.append("repository logo still uses the legacy Jade/Midnight signature")

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
