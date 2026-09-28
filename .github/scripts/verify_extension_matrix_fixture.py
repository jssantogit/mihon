#!/usr/bin/env python3
"""Validate exact Git bytes for eight user-provided extension APKs."""
import hashlib
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[2]
DIR = ROOT / "test-fixtures/extensions"
EXPECTED = {
    "manga-ball-1.6.1.apk": (81635, "3022e5fe83dd212352e597f20262fa1f337f4deb"),
    "animexnovel-1.6.19.apk": (63163, "b2691c6d6c71c9de6b9c3239712da992808ff5b6"),
    "manga-flix-1.4.4.apk": (63303, "e8340a0c468eb0ff6c41774234a2cd0568a4535c"),
    "manga-livre.to-1.6.57.apk": (67110, "f200a7e2569391c12560bf1a31ad1d35376f5d0b"),
    "mangadex-1.6.0.apk": (98014, "790ecf061fc467261a1e147b27c50bed40d08ed7"),
    "mangadot-1.6.23.apk": (151147, "58dc6e795fcdc44a9390cb3eb5b4afbd475f43a0"),
    "mangafire-v1.6.34.apk": (83403, "683e1a00a5f1cc61c06572975d2f8340b87926f4"),
    "mangas-brasuka-1.6.57.apk": (102863, "f81b88ca34b3f54318e32a32740037d4078827d0"),
}
MANGAFIRE_SHA256 = "f5a2bedca694bcf1ef7b133c82d0c0d99a8e1c59e9237b0d93f9153d9c3c7378"

def verify_one(filename: str, path: Path | None = None) -> str:
    if filename not in EXPECTED:
        raise ValueError("Unpinned APK fixture")
    apk = path if path is not None else DIR / filename
    data = apk.read_bytes()
    length, expected_blob = EXPECTED[filename]
    blob = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
    if len(data) != length or blob != expected_blob:
        raise ValueError("APK fixture bytes do not match pinned Git blob: " + filename)
    digest = hashlib.sha256(data).hexdigest()
    if filename == "mangafire-v1.6.34.apk" and digest != MANGAFIRE_SHA256:
        raise ValueError("MangaFire SHA-256 mismatch")
    with zipfile.ZipFile(apk) as archive:
        members = archive.namelist()
        if len(members) != len(set(members)) or not {"AndroidManifest.xml", "classes.dex"} <= set(members):
            raise ValueError("APK has duplicate or missing essential ZIP members: " + filename)
        if archive.testzip() is not None:
            raise ValueError("APK failed ZIP CRC verification: " + filename)
    return digest

def verify_all() -> dict[str, str]:
    present = {p.name for p in DIR.glob("*.apk")}
    if present != set(EXPECTED):
        raise ValueError("Fixture list differs from pinned manifest")
    if "/test-fixtures/extensions/*.apk binary" not in (ROOT / ".gitattributes").read_text():
        raise ValueError("APK Git binary attribute is missing")
    return {name: verify_one(name) for name in EXPECTED}

if __name__ == "__main__":
    for name, sha in verify_all().items():
        print("FIXTURE_OK|" + name + "|sha256=" + sha)
