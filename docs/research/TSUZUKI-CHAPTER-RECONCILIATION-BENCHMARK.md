# Chapter evidence reconciliation benchmark

Date: 2026-09-26. This is a local, synthetic SQLDelight measurement of a repeated refresh for already-known chapter observations. It measures SQL calls and elapsed reconciliation time; it does not query or depend on any external provider.

## Revisions and execution

| Run | Revision measured | Result |
| --- | --- | --- |
| Baseline | `6363bbcb1e7cd2432fbdce32d3ede3e958186aa8` | PASS: 1 test, 0 skipped, 0 failures |
| Current | `db593cd1aa41a8da8dc62d06cefb86f7f9dffe98` | PASS: 1 test, 0 skipped, 0 failures |

The current SHA was captured with `git rev-parse HEAD` immediately before the Gradle command and confirmed unchanged after it. The shared worktree advanced in later integration commits; this is the snapshot measured, not a claim about the later HEAD. The current test artifact's descriptive environment label says `current-0bc4df777`; that label is stale and is not the measured revision. Use the SHA in this report.

Both valid runs used this target and test filter, with the shown environment variables:

```sh
TSUZUKI_CHAPTER_BENCHMARK=true \
TSUZUKI_BENCHMARK_LABEL=baseline-6363bbcb \
./gradlew :data:testDebugUnitTest \
  --tests 'tachiyomi.data.tsuzuki.ChapterEvidenceReconciliationBenchmarkTest' \
  --console=plain
```

The baseline test source was copied unchanged into a temporary detached worktree at the baseline SHA because the opt-in harness is new on the current branch. From the current repository root, the baseline setup was:

```sh
git worktree add --detach /tmp/tsuzuki-baseline-6363 6363bbcb1e7cd2432fbdce32d3ede3e958186aa8
cp data/src/test/java/tachiyomi/data/tsuzuki/ChapterEvidenceReconciliationBenchmarkTest.kt \
  /tmp/tsuzuki-baseline-6363/data/src/test/java/tachiyomi/data/tsuzuki/ChapterEvidenceReconciliationBenchmarkTest.kt
```

The current run was made from the working branch; its revision check was:

```sh
git rev-parse HEAD
# db593cd1aa41a8da8dc62d06cefb86f7f9dffe98
```

```sh
TSUZUKI_CHAPTER_BENCHMARK=true \
TSUZUKI_BENCHMARK_LABEL=current-0bc4df777 \
./gradlew :data:testDebugUnitTest \
  --tests 'tachiyomi.data.tsuzuki.ChapterEvidenceReconciliationBenchmarkTest' \
  --console=plain
```

The harness lives in `data/src/test/java/tachiyomi/data/tsuzuki/ChapterEvidenceReconciliationBenchmarkTest.kt`. It is opt-in through `TSUZUKI_CHAPTER_BENCHMARK=true`; ordinary test runs compile and discover it but skip the measurement unless that environment variable is set.

## Method

- Host: Linux `aarch64`, OpenJDK `21.0.12.1`, 8 reported processors.
- Database: real SQLDelight schema and repositories over `JdbcSqliteDriver(IN_MEMORY)`.
- Each size uses one canonical title, N confirmed canonical chapters, and N pre-existing observations with stable provider keys and mappings. Provider IDs are title-specific to respect the database's unique provider-key constraint.
- For each size, one unmeasured warm-up reconciles the first 25 observations. Then the full N-observation inventory is reconciled twice. The timer covers `ReconcileChapterEvidence.execute`; the query counter is reset immediately before each measured run.
- A dynamic proxy around `SqlDriver` counts calls to `execute` and `executeQuery`. The two samples at each size must produce identical counts. Inventory row counts are checked after measurement.
- The measured work is a refresh of an already persisted inventory. This intentionally exercises the repeated-lookup path; it does not measure network discovery, new-key fallback behavior, Android storage, or provider latency.

## Results

Elapsed values are the two raw samples in milliseconds. Query counts are reported as `execute / executeQuery / total`.

| Observations | Baseline queries | Baseline elapsed ms | Current queries | Current elapsed ms |
| ---: | ---: | ---: | ---: | ---: |
| 100 | 200 / 402 / 602 | 421.566, 285.234 | 200 / 3 / 203 | 89.338, 67.622 |
| 500 | 1,000 / 2,002 / 3,002 | 3,214.980, 2,804.265 | 1,000 / 3 / 1,003 | 249.796, 211.117 |
| 1,000 | 2,000 / 4,002 / 6,002 | 14,147.455, 6,878.731 | 2,000 / 3 / 2,003 | 319.816, 371.949 |

For these persisted-inventory cases, write calls remain linear at two `execute` calls per observation. Read calls were `4N + 2` in the baseline samples and stayed at 3 in the current samples. Total observed driver calls changed from `6N + 2` to `2N + 3` for these measured sizes. These are counts from the harness, not estimates.

## Limits and discarded attempts

The timing samples are not a stable throughput benchmark: there are only two per size, and baseline timing varied substantially, especially at 1,000 observations. JVM/JIT state, CPU scheduling, and proxy overhead can affect elapsed time. The current and baseline runs used the same host and harness, but the measurements do not establish device performance or predict provider refresh latency. The query-count change is the stronger result because both samples at each size agreed exactly.

Only the successful runs above are included. Earlier harness attempts were discarded: one compile attempt invoked an internal constructor and called suspend setup functions outside a suspend context; the next corrected compile exposed fixture reuse of provider keys across titles, violating the SQL unique index. The fixture was corrected to use title-specific provider IDs and the successful baseline/current commands then passed. An earlier malformed `:data:test` invocation did not run the benchmark. No provider data, URLs, images, credentials, or external responses were used or recorded.
