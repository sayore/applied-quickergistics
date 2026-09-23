# applied-quickergistics

A fork of [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) that adds an
optional **Rust-accelerated network item-storage index** (`ae2store`) in front of AE2's aggregate
storage queries.

* Upstream source: `ae2-src/` (AE2 `main`, commit `b7cf5822d`), LGPL-3.0, license unchanged
* Native library: `ae2-src/rust/ae2store` (core) and `ae2-src/rust/ae2store-jni` (JNI bindings)
* Java bridge: `ae2-src/src/main/java/appeng/storage/nativebridge/`
* AE2 integration: `ae2-src/src/main/java/appeng/me/storage/RustStorageIndex.java`
* Benchmark and correctness harness: `harness/`
* Feature documentation: [harness/docs/native-storage-acceleration.md](harness/docs/native-storage-acceleration.md)
* **Continuing this work: [HANDOFF.md](HANDOFF.md)** — verification state, open items, untested
  surfaces, and the environment gotchas that are not in upstream's `AGENTS.md`.

## What it does

AE2 rebuilds the whole-network item tally by walking every mounted storage on each query. This fork
mirrors that tally into a dense columnar Rust index that is updated incrementally (only storages whose
version changed are re-read) and cached until something changes.

The mirror is read-only and best-effort: without the native library it is not created at all, and AE2's
original Java path runs unchanged. `-Dae2.native.index=false` forces the Java path.

## Measured results

Headless, on real `BasicCellInventory` cells behind `DriveWatcher` mounts — the same cells both paths
are timed against (`./gradlew :test --tests appeng.me.storage.RealCellPerformanceTest --rerun-tasks --info`):

| Network | Java (AE2 path) | Rust mirror | Speedup |
| --- | ---: | ---: | ---: |
| 29 cells / 1827 types, nothing changes between queries | 70–111 µs | 0.20–0.22 µs | **344–533×** |
| 14 cells / 882 types, nothing changes | 29–43 µs | 0.11–0.19 µs | 194–396× |
| 4 cells / 252 types, nothing changes | 10–16 µs | 0.07–0.54 µs | 30–166× |
| 1 cell / 63 types, nothing changes | 3.9–5.9 µs | 0.46–0.68 µs | 6–10× |
| 29 cells, one cell mutates per tick | 84–108 µs | 14–15 µs | **6.1–7.4×** |

Ranges are across repeated runs on one machine; run-to-run spread is real and the single-cell row is
dominated by fixed JNI cost.

What the per-tick number is made of matters, so it is measured rather than assumed. On a mutating tick
`RustStorageIndex#profilingSummary` attributes ~11.6 µs to `sync` (a version check per mount plus the
one dirty cell), of which ~4.7 µs is re-reading that cell and ~2.2 µs is the JNI push for its 63
entries; applying the resulting change is ~0.06 µs. **Most of the remaining cost is AE2's own cell
read, not the mirror.**

Two honest caveats:

* A caller that owns its own counter instead of accepting the mirror's shared one pays a copy of the
  whole aggregate (~197 µs for 1827 types). The mirror cannot remove that for the caller.
  `StorageService` — the consumer that matters in a tick — accepts the shared counter.
* The `harness/` microbenchmark models 311 types per cell while AE2 caps a cell at 63 types, and its
  tick-churn row predates the diff-based `push_cell`. Treat its numbers as indicative only; the
  real-cell table above is the one to use.

## Quick start

```bash
# Build the mod (the native library is built by cargo as part of the Gradle build)
cd ae2-src
JAVA_HOME=/path/to/jdk-21 ./gradlew build

# Run the Rust unit tests
cd rust && cargo test --release

# Run the benchmark and the end-to-end integration harness
cd ../.. && harness/run.sh
```

Building without a Rust toolchain works and simply produces a mod that uses the Java implementation.

## Correctness work

The accelerated path is a second implementation of an aggregate that the whole mod reads, so it is
tested as one rather than trusted:

* **Differential tests against a naive model.** The native write paths (extract/insert priority order,
  per-key caps) are fuzzed against a model of the documented rules, and the Java mirror is fuzzed
  against a reference merge over a changing mix of mirrored and unmirrored mounts.
* **Delta replay.** A property test replays the change log from a recorded revision over 400 rounds of
  pushes, deltas, extraction, insertion and reprioritisation; a real-cell test does the same against
  two genuine drive cells and compares the replayed counter with a fresh full refresh after *every*
  round.
* **In-game tests.** Two game-test plots run the production path — a ticked server where
  `StorageService` rebuilds its cached inventory at end of tick — and compare that cache against a
  live query, including a chest behind a storage bus whose contents change while the rest of the
  network is idle.
* **Regression tests for every bug found.** Each of the following has a test that fails without its
  fix: keys interned by identity rather than by primary key (one resource got two ids, so a delta to
  one variant overwrote the other's amount); a removed cell's id still resolving to its recycled slot,
  and again after `reorder` permuted slots; native `insert` never updating the posting list, making an
  inserted key unextractable; coverage never being recomputed, so one unmirrorable mount degraded the
  network for the rest of the session; a filtered query sizing an accumulator to a caller-supplied key
  id; an unbounded change log behind an unmirrorable mount; and a leaked native index per network.

Current status of the suites: Java 540/540 (full suite), Rust 33/33, integration harness 50/50, game
tests 77/77.

**Known open item:** two game tests (`multi_storage_bus`, `regression_7288`) were intermittently
failing roughly 1 run in 6 and have a fix in `12c304a` that is **not yet verified by a full run** — it
was committed without re-running the suite. Re-run the game tests repeatedly before trusting them.

## Status

This is an experiment. The upstream research found no prior native/FFI attempt in AE2, so there is no
precedent for reviewer expectations or for how such a change would be received. The integration is
deliberately conservative (read path only, verifiable fallback, no changes to insert/extract semantics)
and comes with a measurement harness because upstream performance PRs have historically been rejected
for lacking measurements.

Upstream AE2 is LGPL-3.0 and its license is untouched; this fork is a derivative work and is
distributed under the same terms.
