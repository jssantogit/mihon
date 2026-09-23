# MangaFire 1.6.34 — real-extension Android verification lane

Branch: \`tsuzuki/runtime-e2e-tests\`. No merge into bootstrap and no user-installable
Tsuzuki APK is produced by this lane.

## Immutable fixture provenance

The APK at \`test-fixtures/extensions/mangafire-v1.6.34.apk\` is the copy
extracted from the user's device, not an independently authenticated official
Keiyoushi release. Its known SHA-256 is
\`f5a2bedca694bcf1ef7b133c82d0c0d99a8e1c59e9237b0d93f9153d9c3c7378\`,
and its length is 83,403 bytes. \`.gitattributes\` forces APK binary storage.

\`python3 .github/scripts/test_extension_fixture.py\` verifies the exact
bytes, archive CRC, required members, and intentional Git binary rule.
The Android emulator lane independently requires \`apksigner verify\` before
installation. These checks establish reproducibility of the user-supplied
artifact, **not upstream authenticity or safety**.

## Evidence levels and execution

1. On branch pushes affecting the fixture or instrumentation, the separate
   workflow performs fixture validation and compiles \`:app:compileDebugAndroidTestKotlin\`.
   Neither step runs the extension, creates a release APK, or contacts MangaFire.
2. An explicit \`workflow_dispatch\` with \`live_probe=false\` installs the exact
   fixture on a disposable API 35 emulator, checks Android signature validity,
   loads it through the actual \`ExtensionManager\` trust path, and asserts
   English plus six other internal languages are registered with the
   \`AndroidSourceManager\`. No MangaFire requests are issued.
3. Only an explicit \`workflow_dispatch\` with \`live_probe=true\` invokes the
   real extension's English search for One Punch Man. This is an observational,
   non-deterministic external provider check; do not interpret temporary
   connectivity failures as Tsuzuki regressions. It is **never** a Fast CI gate.

The test prints only closed failure categories, numeric HTTP status if known,
safe counts, and elapsed milliseconds. It does not print raw URLs, responses,
exception messages, cookies, or credentials. A CAPTCHA explicitly signaled by
the extension is reported, not bypassed.

## Operational constraints

The emulator job is manual because it is substantially heavier than JVM tests.
The first run must prove that the fixture loads and all seven sources register
before live probing. GitHub Actions compilation success is not evidence of
successful emulator loading. Emulator success is not evidence of working live
MangaFire search. Live search success is not, on its own, evidence that all
chapter inventories or reader alternatives work.

The user already provided a single-source diagnostic in which English lookup
failed after 19,897 ms with \`EXTENSION_FAILURE\`. Compare a separately
authorized live probe against that observation; if Android behaves differently,
investigate environment and network differences rather than manufacturing a
fallback or an automatic canonical merge.
