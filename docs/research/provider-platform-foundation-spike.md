# Provider Platform foundation spike

Status: experimental, not a production Provider API.

## Goal

Prove the minimum technical foundation required before freezing the Provider Platform spec:

1. one maintained QuickJS engine in the APK;
2. temporary compatibility for the QuickJS operations used by current Mihon/Keiyoushi extensions;
3. bounded JavaScript execution;
4. JavaScript execution in an Android isolated UID/process;
5. no normal Tsuzuki application graph bootstrap inside the provider process;
6. an IPC boundary that can later be replaced by typed Provider protocol DTOs.

## Key finding: QuickJS native-library collision

The inherited Mihon dependency and `quickjs-kt` both package a native library named
`libquickjs.so`, but expose different JNI ABIs. They must not coexist in one APK.

This spike therefore uses one engine, `io.github.dokar3:quickjs-kt`, and temporarily
preserves the old `app.cash.quickjs.QuickJs` binary surface through a compatibility shim.
The shim exists only to keep today's APK-backed reading sources operational while the new
Provider runtime is proven; it is not part of the intended final Provider API.

A current Keiyoushi source audit found active extension usages of `evaluate`, `compile`
and `execute`; no current `QuickJs.get(...)` or `QuickJs.set(...)` call sites were found.
The shim keeps both method signatures for linkage compatibility. `set(...)` is supported
by the maintained engine's reflection binding. `get(...)` deliberately fails closed with
`UnsupportedOperationException`: the first proxy-based compatibility attempt could exhaust
the JVM test worker, and preserving an unobserved legacy behavior is not worth introducing a
host-stability hazard. If a real extension later proves that operation is required, it must be
implemented from an explicit fixture/contract rather than a best-effort dynamic proxy.

## Runtime boundary

`ProviderRuntimeService` runs with `android:isolatedProcess="true"`. The application
bootstrap explicitly returns before creating DI, network, persistence, WebView, jobs,
notifications or telemetry in the provider process.

The spike's IPC intentionally exposes only:

- evaluate a source string with explicit wall-clock and JS execution deadlines;
- query the process UID/PID for verification.

The string result protocol is temporary. The real Provider Platform must use versioned,
small typed DTOs/handles and must not send large HTML, images or archives over Binder.

## Execution limits

The effective Tsuzuki minimum SDK is API 26 (`gradle/mihon.versions.toml`), which is above the current minimums required by both QuickJS-kt and the jlibtorrent option evaluated for a later torrent spike.

The runtime applies:

- wall-clock timeout;
- QuickJS execution timeout/interrupt;
- memory limit;
- stack limit;
- runtime disposal after evaluation.

Runtime reuse/pooling remains deliberately undecided until benchmarked.

## Verification

Unit tests cover:

- primitive JavaScript evaluation;
- infinite-loop interruption;
- script-error containment;
- exact Mihon-facing `app.cash.quickjs` binary method surface;
- compatibility evaluation, array return shape and Kotlin-to-JavaScript binding;
- fail-closed behavior for the unsupported legacy JavaScript-object proxy path.

Android instrumentation proves:

- provider runtime UID differs from the app UID;
- provider runtime PID differs from the app PID;
- the main app retains `INTERNET`, while the isolated provider UID is denied that permission;
- normal JavaScript evaluates in the isolated process;
- an infinite loop is interrupted;
- a real immutable MangaFire APK fixture still loads and registers sources.

The branch-scoped workflow `provider-platform-foundation-spike.yml` runs focused runtime
unit tests separately from the app unit/compile gate, then runs the API 35 emulator proof.
Keeping the stages separate makes runtime failures attributable instead of masking them as
general test-worker pressure.

## Non-goals

This spike does not define:

- Provider manifests or repositories;
- HTTP Broker;
- Browser Broker;
- storage/secrets APIs;
- catalog/metadata/reading capability contracts;
- torrent/debrid/P2P contracts;
- runtime pooling policy;
- final serialization/IPC protocol.

Those remain subsequent spikes/spec work.
