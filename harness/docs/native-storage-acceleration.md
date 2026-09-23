# Native item-storage acceleration (`ae2store`)

This document describes the optional Rust acceleration for AE2's network-wide item-storage reads: what
it does, what it deliberately does **not** do, how to build and test it, and what the measured effect
is.

## TL;DR

* AE2 repeatedly merges the contents of *every* mounted storage into one `KeyCounter`. That is the hot
  path behind `StorageService#updateCachedStacks`, crafting simulations, pattern providers and
  terminals, and it is what AE2 issues [#8539](https://github.com/AppliedEnergistics/Applied-Energistics-2/issues/8539)
  and PRs [#8965](https://github.com/AppliedEnergistics/Applied-Energistics-2/pull/8965)/[#8966](https://github.com/AppliedEnergistics/Applied-Energistics-2/pull/8966)
  are about.
* `rust/ae2store` keeps that aggregate in a **dense columnar form** (one `u32` id per key, sorted id and
  amount columns per cell, a per-cell presence bitset, one flat network total array) and caches it until
  something actually changes.
* Measured on a 400-cell / 1500-type / 124502-entry network: **~13× faster** for the realistic
  "something changed, then query" tick shape and **~370-390× faster** when the aggregate is
  re-requested without a change, versus AE2's current implementation. See
  [benchmark-results.txt](benchmark-results.txt).
* The mirror is **read-only and best-effort**: if the native library is missing, a mount cannot report
  changes, or anything at all goes wrong, AE2's original Java code path runs unchanged.
* This is, as far as the upstream research went, the first native/FFI attempt in AE2's history; there is
  no precedent in AE2, Refined Storage or comparable storage mods, so treat it as an experiment.

## Where it plugs in

```
StorageService.updateCachedStacks()            (every server tick, when watchers are registered)
  └─ NetworkStorage.getAvailableStacks(out)    ← the Java hot path
       └─ MEInventoryHandler.getAvailableStacks → BasicCellInventory.getAvailableStacks (per cell)
```

`NetworkStorage` keeps its mounts exactly as before and maintains a `RustStorageIndex` mirror
alongside them:

* `mount`/`unmount` keep the mirror's cell set in sync (including the queued-mount flush path).
* `getAvailableStacks(KeyCounter)` asks the mirror first. The mirror returns `null` when it cannot be
  used, and the original loop then runs unchanged.
* Insert/extract are **not** accelerated. All filtering, prioritisation and side effects stay in Java,
  and the mirror is invalidated/refreshed by the resulting mutations.

### How the mirror learns about changes

A cell can be mutated behind `NetworkStorage`'s back (an IO port fills it, a storage bus observes an
external inventory). Re-reading every cell every tick would cost more than the native index saves, so
`BasicCellInventory` implements the new `appeng.api.storage.VersionedStorage` interface and bumps a
counter on every mutation. The mirror only re-reads cells whose counter changed.

A mount that cannot report a version (for example a third-party `MEStorage`) makes the mirror
unusable for that network, and the Java path is used. `MEInventoryHandler` wrappers are transparent as
long as they neither filter the reported contents nor block extraction; otherwise they are rejected
too. This is a deliberate correctness-over-speed trade: the mirror never has to replicate Java-side
filtering.

## Measured results

`harness/run.sh` builds the library, compiles the tools and runs both the benchmark and the
integration harness. Machine: 7-core x86_64, JDK 26, Rust 1.98.1, 400 cells, 1500 distinct keys,
124502 (cell, key) pairs, 2000 iterations.

| Scenario | Java (AE2 path) | Rust mirror (incl. JNI) | Speedup |
| --- | ---: | ---: | ---: |
| `getAvailableStacks` full rebuild, repeated query | 3055.1 µs | 8.3 µs | **370×** |
| `getAvailableStacks` filtered to 32 keys | 238.1 µs | 122.5 µs | **1.9×** |
| tick churn (one cell changes) + `getAvailableStacks` | 2925.3 µs | 223.3 µs | **13.1×** |
| `extract` SIMULATE, single key | 0.11 µs | 0.06 µs | 1.8× |
| `insert` + `extract` MODULATE, single key | 0.26 µs | 0.10 µs | 2.5× |

Run-to-run spread on this machine is roughly ±10 % for the slow scenarios and larger for the
sub-microsecond single-key rows; the full capture is in
[benchmark-results.txt](benchmark-results.txt).

Reading these numbers honestly:

* The **370×** figure is a *cached* query. It is legitimate for the way AE2 consumes the aggregate (the
  storage-service cache, several crafting simulations, pattern providers and terminals all ask for the
  same thing in one tick), but it is not a pure algorithm speedup.
* The **13.1×** figure is the honest end-to-end one for a network that actually changes every tick: one
  cell is dirtied, the cache is invalidated, and the full aggregate has to be rebuilt from 124502
  entries across the JNI boundary.
* The **filtered** query only benefits from the per-cell presence bitset, not from the cache; ~2× is
  what a bitset pre-test buys over 12800 hash probes.
* Single-key `extract`/`insert` are **not** the target and show ~1×: they are already one hash lookup
  per cell in Java, and the JNI call is a fixed overhead. Do not expect improvements there.

## Why a native index at all?

The upstream research turned up two useful facts:

1. AE2 has **never** used native code; no JNI/Panama/FFM/SIMD PR exists, merged or rejected.
2. Past AE2 performance PRs were repeatedly turned down for lacking measurement ([#4220](https://github.com/AppliedEnergistics/Applied-Energistics-2/pull/4220),
   [#8764](https://github.com/AppliedEnergistics/Applied-Energistics-2/pull/8764)), and PR
   [#8904](https://github.com/AppliedEnergistics/Applied-Energistics-2/pull/8904) had to introduce JMH
   infrastructure because "there is no reproducible way to measure" these paths.

So this work comes with its own benchmark and correctness harness (`harness/`), and the benchmark
cross-checks every native result against a faithful re-implementation of AE2's Java data structures
before timing anything.

## Building

The Rust library is built automatically as part of the normal Gradle build:

```bash
cd ae2-src
JAVA_HOME=/path/to/jdk-21 ./gradlew build      # cargoBuild → copyNativeLibrary → processResources
```

* Without a Rust toolchain the tasks are skipped with a log line and the mod builds and runs with the
  Java implementation. Nothing breaks.
* `-PskipRust=true` (or `AE2_SKIP_RUST=true`) skips it explicitly.
* `-PrequireRust=true` (or `AE2_REQUIRE_RUST=true`) turns a missing toolchain into a build failure, for
  CI that wants to guarantee the native build.
* The resulting library is packaged into the mod jar as `/native/libae2store_jni.so` (or `.dll`/`.dylib`).

## Running and configuring

`appeng.storage.nativebridge.NativeLibrary` resolves the library in this order:

1. the `ae2.native.library` system property (explicit path),
2. the copy bundled in the mod jar (extracted to a temp file),
3. `rust/target/release` relative to the working directory (development convenience),
4. `java.library.path`.

The accelerator itself is controlled by the `ae2.native.index` system property. When unset it follows
the native library's availability. To force the Java path:

```
-Dae2.native.index=false
```

## Testing

```bash
# Rust unit tests, including the invariant and priority-order regression tests
cd ae2-src/rust && cargo test --release

# Benchmark + end-to-end integration harness (plain JVM, no Minecraft bootstrap)
harness/run.sh
```

`harness/run.sh` runs `harness.NativeIntegrationHarness`, which drives a real `NetworkStorage` with
`VersionedStorage` test doubles and asserts that the native aggregate is byte-for-byte equal to the
merged Java result across mounts, unmounts, mutations, reprioritisation, 500 random mutations, and the
unversioned-storage fallback. It exits non-zero on any mismatch.

### A note on the Gradle `test` task

`ae2-src/src/test/java/appeng/me/storage/RustStorageIndexTest.java` contains the same integration
checks as a JUnit test. It compiles, but running it through `./gradlew :test` currently fails with
`build/classes/java/main is not a valid mod file` — NeoForge's FML test harness refuses to start with a
classes directory as the mod source. That is a pre-existing tooling limitation, not a failure of this
feature; `harness/run.sh` covers the same ground outside the FML harness.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `native library status: not available` | no `.so`/`.dll`/`.dylib` found | run `cargo build --release` in `ae2-src/rust`, or pass `-Dae2.native.library=<path>` |
| Native build skipped | cargo not found by the Gradle daemon | ensure `cargo` is on the daemon's `PATH` (the plugin also probes `~/.cargo/bin`, `/usr/local/bin`, `/usr/bin`, `/sbin`) |
| No speedup at all | a mount cannot report a version | check for third-party `MEStorage` implementations that do not implement `VersionedStorage` |
| `UnsatisfiedLinkError` for a specific method | stale native library | rebuild with `cargo build --release`; the loader prefers the copy bundled in the jar |

## Incremental refresh (implemented, disabled by default)

`NetworkIndex` keeps a revisioned change log, so a consumer that reports the revision it last saw can
receive only the keys that changed instead of a full aggregate. The log, its JNI surface
(`deltasSince`) and the consumer side (`NetworkStorage.deltasSince`, `StorageService.applyDeltas`) are
implemented and verified; the per-tick path is gated behind `-Dae2.native.incremental=true` and is
**off by default**.

Why it is off: measured with `harness/src/harness/TickRefreshBenchmark.java`, the mirror reports far
more changes than the network actually saw — on a 400-cell network with 20 mutations per tick it
records roughly 15 000 changes per tick. The cause is on the mirror side, not in the change log: a
cell whose version changed is re-pushed and its entire content is re-recorded, so each real mutation
produces hundreds of log entries. Applying those costs more than one full aggregate
(~1.8 ms/tick versus ~1.1 ms/tick), so enabling it would be a regression.

The change log itself is correct and covered by tests: a property test replays it from a recorded
revision over 400 rounds of pushes, deltas, extraction, insertion and reprioritisation and asserts
that the reconstruction equals the real aggregate and that every claimed old value matches the
consumer's baseline. Revisions are deliberately dense — entries are not merged across revisions,
because a consumer that fell behind would otherwise replay an entry whose old value does not match
its baseline.

The remaining work is to narrow what the mirror re-reads: a cell should contribute only the keys
whose amounts changed, not its whole content, and `push_cell` should be able to reuse a cell's
existing ids. Once that is fixed the path can be enabled and the benchmark should be re-run.

## Fuzzy variant index

Independent of the native work, `KeyCounter` used to build an AVL-backed fuzzy variant index for
every damageable key from the ordinary counting path, even though only the storage bus, export bus
and level emitter (with a fuzzy card) ever search by durability. It now converts a primary key to that
index the first time `findFuzzy` is called, so counters that are never fuzzy-searched never build a
tree. See `appeng.api.stacks.KeyCounter`.

## Limitations and next steps

* Only the aggregate read path is accelerated. Extract/insert fan-out is untouched.
* Fuzzy/partition filtering is handled by refusing to mirror filtered `MEInventoryHandler` mounts
  rather than by replicating the filter natively. Replicating it would let `Storage Bus`, `Export Bus`
  and level-emitter networks benefit too.
* ~~A per-key posting list would remove the remaining linear scan in the filtered query.~~
  Implemented: `NetworkIndex` keeps a reverse index of the cells holding each key, `extract` visits
  only those cells, and a filtered query picks the cheaper of walking the posting lists versus
  reading the cached aggregate.
* The mirror's per-cell push should send only the changed keys instead of the whole cell. That is
  what blocks enabling the incremental refresh described above.
* If a future AE2 version lands [#8965/#8966/#8967](https://github.com/AppliedEnergistics/Applied-Energistics-2/pull/8967),
  the Java baseline in the benchmark should be updated to match, because those PRs change the very code
  the baseline models.
