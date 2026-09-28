# Tsuzuki staged chapter projection benchmark

Measured: 2026-09-27. The production sources and existing tests match draft PR #21 at `300b121daf422e619d0a06edfddf724a314c7755`; the opt-in harness and temporary CI workflow ran at disposable SHA `07d7e185a3bb64464fad71e6c43328ed5cf5a6ff`. Only the benchmark test is suitable for promotion; the temporary workflow enabling both measurements and uploading artifacts must not be merged.

Evidence: [full CI run 36316578951](https://github.com/jssantogit/mihon/actions/runs/36316578951) · [two-report raw artifact 10931156451](https://github.com/jssantogit/mihon/actions/runs/36316578951/artifacts/10931156451). Domain, Data, Core Common, SQLDelight, Supabase and Format have passed; distinguish the separately skipped Compile/Native gates when reading the final CI result.

## Workloads and environment

- `ChapterEvidenceReconciliationBenchmarkTest.kt` opts into both lanes separately with `TSUZUKI_CHAPTER_BENCHMARK=true` and `TSUZUKI_STAGED_PROJECTION_BENCHMARK=true`. Ordinary tests discover these methods but skip the opt-in measurements.
- Host reported Linux amd64, OpenJDK 21.0.12.1, four logical processors, real SQLDelight schema and JDBC SQLite `IN_MEMORY`. There was no network, external manga provider, APK or Android device.
- Evidence-only lane seeds N confirmed canonical chapters and N mapped source evidence rows; warms 25 rows; repeats the full N-observation reconciliation twice.
- Staged lane seeds a materialized Mihon source mapping, then projects N full chapter observations to canonical chapters, evidence and N operational variants outside timing. It repeats the full N-observation refresh twice at later original fetch timestamps; verifies N chapter/evidence/variant rows and exact source-variant ID preservation.
- Each timer covers the complete respective reconciler call. A `SqlDriver` proxy counts `execute` and `executeQuery`; the two samples at each size produced identical SQL counts. Setup, initial inserts, final storage checks and report writing are excluded from the timed interval.

## Raw results

Times below are the two independent recorded samples, in milliseconds. SQL counts are per full refresh and repeated identically.

| Observations | Evidence-only SQL (write / read / total) | Evidence-only elapsed ms | Staged SQL (write / read / total) | Staged elapsed ms | Staged IDs |
| ---: | ---: | ---: | ---: | ---: | :--- |
| 100 | 200 / 3 / 203 | 23.037, 20.140 | 300 / 404 / 704 | 94.769, 81.906 | Stable |
| 500 | 1,000 / 3 / 1,003 | 64.338, 64.391 | 1,500 / 2,004 / 3,504 | 267.439, 295.762 | Stable |
| 1,000 | 2,000 / 3 / 2,003 | 136.424, 140.772 | 3,000 / 4,004 / 7,004 | 434.308, 383.283 | Stable |

Staged SQL driver calls followed `7N + 4` (N=100, 500 and 1,000), compared with `2N + 3` for the evidence-only workload. Staged writes were 3N and reads 4N + 4; this is observed linear growth over the three measured sizes, not a proof of asymptotic limits for all data distributions.

## Interpretation and cutover limits

**Do not treat evidence-only and staged runtimes as a direct performance regression.** The staged lane additionally validates the persisted mapping, reads and persists source-specific operational variants, whereas the evidence-only lane does neither. Both share the same host and underlying driver but have different warm-up volumes and workloads. Two samples per size cannot establish stable throughput; JVM warm-up and CI contention can change elapsed times. Per-observation variant reads are a candidate for profiling and eventual bulk loading, but this benchmark alone does not identify which exact query is responsible.

The staged projection benchmark demonstrates bounded repeated-refresh behavior in a synthetic in-memory database and retention of existing variant IDs. It does **not** measure first-time provider fetch, low-confidence workloads, network latency, disk-backed Android storage, simultaneous Reader/detail refresh, process restart or the post-cutover eight-case Android battery. The independent legacy Reader writer remains active; neither PR #20 nor PR #21 is ready to merge on benchmark evidence alone.
