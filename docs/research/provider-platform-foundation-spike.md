# Provider Platform foundation spike

Status: experimental, not a production Provider API.

## Goal

Prove the minimum technical foundation required before freezing the Provider Platform spec:

1. one maintained QuickJS engine in the APK;
2. temporary binary compatibility for current Mihon/Keiyoushi extensions;
3. bounded JavaScript execution;
4. JavaScript execution in an Android isolated UID/process;
5. no normal Tsuzuki application graph bootstrap inside the provider process;
6. an IPC boundary that can later be replaced by typed Provider protocol DTOs.

## Key finding: QuickJS native-library collision

The inherited Mihon dependency and `quickjs-kt` both package a native library named
`libquickjs.so`, but expose different JNI ABIs. They must not coexist in one APK.

This spike therefore uses one engine, `io.github.dokar3:quickjs-kt`, and temporarily
preserves the old `app.cash.quickjs.QuickJs` surface through a compatibility shim. The
shim exists only to keep today's APK-backed reading sources operational while the new
Provider runtime is proven; it is not part of the intended final Provider API.

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
- exact Mihon-facing `app.cash.quickjs` ABI;
- compatibility evaluation and object bindings.

Android instrumentation proves:

- provider runtime UID differs from the app UID;
- provider runtime PID differs from the app PID;
- normal JavaScript evaluates in the isolated process;
- an infinite loop is interrupted;
- a real immutable MangaFire APK fixture still loads and registers sources.

The branch-scoped workflow `provider-platform-foundation-spike.yml` runs the unit/compile
gate and the API 35 emulator proof.

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
