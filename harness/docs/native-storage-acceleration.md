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

### Real AE2 cells (the numbers that matter)

`RealCellPerformanceTest` builds genuine `BasicCellInventory` cells behind `DriveWatcher` mounts and
measures both paths against the same cells. Launch with
`./gradlew :test --tests appeng.me.storage.RealCellPerformanceTest --rerun-tasks --info`.

| Network | Java (AE2 path) | Rust mirror (incl. JNI) | Speedup |
| --- | ---: | ---: | ---: |
| 29 cells / 1827 types, no changes between queries | 70.5-111.1 µs | 0.20-0.22 µs | **344-533×** |
| 14 cells / 882 types, no changes | 28.6-43.2 µs | 0.11-0.19 µs | 194-396× |
| 4 cells / 252 types, no changes | 10.5-16.4 µs | 0.07-0.54 µs | 30-166× |
| 1 cell / 63 types, no changes | 3.9-5.9 µs | 0.46-0.68 µs | 6-10× |
| 29 cells, one cell mutates per tick, delta stream on (default) | 84.2-108.1 µs | 13.9-14.5 µs | **6.1-7.4×** |
| 29 cells, one cell mutates per tick, delta stream forced off | 88.8-113.8 µs | 18.3-23.7 µs | 4.1-5.2× |
| 29 cells, one cell mutates per tick, consumer owns its counter | 88.8-113.8 µs | 167-197 µs | 0.5× |

The per-tick phase is attributed with `RustStorageIndex#profilingSummary`. On a mutating tick the
remaining ~11.6 µs is `sync` (a version check per mount plus the one dirty cell), of which ~4.7 µs is
re-reading that cell and ~2.2 µs is the JNI push for its 63 entries; applying the resulting change is
~0.06 µs. **The remaining cost is AE2's own cell read, not the mirror.**

`measureStorageServiceTickAccounting` isolates the part that depends on the network rather than on the
changed cell: the walk `StorageService` does to find out what changed.

The last row is the honest caveat: a consumer that owns its counter copies all 1827 types out of the
mirror, and that copy is proportional to the network and dominates everything. The mirror cannot
remove it for the caller. `StorageService` - the consumer that matters in a tick - hands the mirror's
counter back, which is the row that describes the game.

### Synthetic benchmark (indicative only)



`harness/run.sh` builds the library, compiles the tools and runs both the benchmark and the
integration harness. Machine: 7-core x86_64, JDK 26, Rust 1.98.1, 400 cells, 1500 distinct keys,
124502 (cell, key) pairs, 2000 iterations. These numbers model a **311-type cell**, while AE2 caps a
cell at **63** types, and the Rust column in the tick-churn row is a native rebuild rather than
`NetworkStorage#getAvailableStacks`; treat the table as a lower bound on the win, not as the headline.

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
* The **13.1×** figure is for a network that changes every tick, with the mirror rebuilding its
  aggregate from 124502 entries. It is a lower bound: `push_cell` used to re-record a dirty cell's
  whole content, and the synthetic benchmark's Rust column predates the diff-based push. The real-cell
  row above (4-5x against AE2's own `getAvailableStacks`, where most of the remaining time is AE2's own
  cell read) is the one to use.
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

### Tests against real AE2 classes

`ae2-src/src/test/java/appeng/me/storage/RustRealCellTest.java` runs the mirror against **real** AE2
storage cells rather than test doubles. It uses `@BootstrapMinecraft` and
`EphemeralTestServerProvider`, so it needs no GPU and no server boot:

```bash
cd ae2-src
./gradlew :test --tests "appeng.me.storage.*"
```

It builds a 64k item cell through `BasicCellInventory`, wraps it in a `DriveWatcher` the way a drive
does, mounts it next to a second cell and asserts that the mirror's aggregate equals the merged Java
result. It then mutates the cell behind the network's back (two inserts and an extract, which is what
an IO port or a crafting CPU does) and checks both the aggregate and a simulated extraction.

For a long time no test could run at all: the mod build did not copy `src/main/resources` into the
build output, so `ae2.mixins.json` and `META-INF/neoforge.mods.toml` never reached the runtime
classpath and FML rejected the mod with `build/classes/java/main is not a valid mod file`. That was a
regression from the Rust build plugin registering `rust/` as a resource source directory of the main
source set. It is fixed; `harness/run.sh` remains useful for the microbenchmarks, which do not need
Minecraft at all.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `native library status: not available` | no `.so`/`.dll`/`.dylib` found | run `cargo build --release` in `ae2-src/rust`, or pass `-Dae2.native.library=<path>` |
| Native build skipped | cargo not found by the Gradle daemon | ensure `cargo` is on the daemon's `PATH` (the plugin also probes `~/.cargo/bin`, `/usr/local/bin`, `/usr/bin`, `/sbin`) |
| No speedup at all | a mount cannot report a version | check for third-party `MEStorage` implementations that do not implement `VersionedStorage` |
| `UnsatisfiedLinkError` for a specific method | stale native library | rebuild with `cargo build --release`; the loader prefers the copy bundled in the jar |

## Incremental refresh (on by default)

`NetworkIndex` keeps a revisioned change log, so a consumer that reports the revision it last saw receives only the keys that changed instead of a full aggregate. The log, its JNI surface (`deltasSince`) and the consumer side (`NetworkStorage.deltasSince`, `StorageService.applyDeltas`) are implemented, verified and **on by default**. `-Dae2.native.incremental=false` forces the full-walk path, which is how the two are compared.

What it buys is not in `NetworkStorage#getAvailableStacks` - it is in what `StorageService` does *around* that call. The shared-counter path has no list of changed keys, so to decide which watchers to notify it walks **every stored type** and compares it against the previous amount. The delta path is told what changed. Measured on 29 cells / 1827 types, with the same single mutation per tick:

| Tick accounting | Java |
| --- | ---: |
| shared counter: query + walk every stored type | 102.6-105.4 µs |
| delta list: query + touch the changed key | 13.9-15.1 µs |

That is what moves the end-to-end mutating tick from 4.1-5.2x to **6.1-7.4x**. `StorageService` also skips the walk entirely when the mirror's revision has not moved, so an idle tick is free rather than proportional to the network.

The change log is dense: entries are not merged across revisions, and a cancelling change is recorded rather than elided, because a consumer that fell behind would otherwise replay an entry whose old value does not match its baseline. A property test replays it from a recorded revision over 400 rounds of pushes, deltas, extraction, insertion and reprioritisation and asserts that the reconstruction equals the real aggregate. `RustRealCellTest#deltaStreamReplaysToTheSameAggregateAsAFullRefresh` does the same against real AE2 cells.

### The bug that kept it off, and the one that was hiding behind it

The original reason was on the mirror side and is fixed: `push_cell` used to re-record a changed cell's whole content, so one mutation produced hundreds of log entries and applying them cost more than one full aggregate. It now records one entry per key whose total actually moved.

Enabling the path then exposed a second, worse bug, which is why the switch was not simply flipped. `DenseKeyInterner` interned keys by **instance identity**, but AE2 keys are value objects: `AEItemKey.of(stack)` returns a fresh instance on every call, and `AEKey#getPrimaryKey()` is the shared `Item`. The network routinely produces two instances for one resource - a cell read builds one, the native aggregate is resolved back through the interner to another - so one logical item got **two ids**. The native index summed the item correctly across both ids while the Java counter kept them apart, so applying a delta to one variant overwrote the other's amount. Concretely, with a cell holding 100 stone and another holding 7: `+17` reported 117 instead of 124, and the next tick set the total to 8.

Nothing caught it earlier because a wrong counter is not a crash and the stream was off. `RustRealCellTest#deltaStreamReplaysToTheSameAggregateAsAFullRefresh` is the regression test: it drives 400 random mutations into two real cells and asserts that the replayed counter equals a fresh full refresh after **every** round. Interning now keys on the primary key, exactly as `KeyCounter` does.

## Fuzzy variant index

Independent of the native work, `KeyCounter` used to build an AVL-backed fuzzy variant index for
every damageable key from the ordinary counting path, even though only the storage bus, export bus
and level emitter (with a fuzzy card) ever search by durability. It now converts a primary key to that
index the first time `findFuzzy` is called, so counters that are never fuzzy-searched never build a
tree. See `appeng.api.stacks.KeyCounter`.

## Coverage: what the mirror actually sees

The mirror can only reproduce a mount whose changes it can observe, so it requires
`VersionedStorage`. Today only `BasicCellInventory` implements it, which means drive cells are
mirrored and everything else is read in Java alongside a mirror that keeps working. The per-mount
table is under "Storage buses" below.

`RustStorageIndex#describeCoverage()` reports which mounts are mirrored and why the others are not.
It matters more than it looks: before partial coverage, an excluded mount silently degraded the
**whole network** to the Java path while still looking healthy, and there was nothing to inspect.
`RustRealCellTest#coverageReportsExcludedMounts` asserts the report.

### Partial coverage

**Status: implemented.** `RustStorageIndex#sync` records the mounts it cannot reproduce in an
uncovered set and leaves its own aggregate exactly the sum of the cells it did reproduce.
`NetworkStorage#getAvailableStacks` then adds the uncovered mounts on top, and `StorageService` does
the same for the cache it exposes (see below).

The first attempt at this was reverted because a correctness test failed. The cause turned out to be
in the test rather than the feature: it keyed a counter's contents by key identity, and the test
environment can hold more than one `Item` instance for the same item (`AEItemKey#getPrimaryKey`
returns the item), so one logical item legitimately landed in two buckets while the amounts were
identical. The failure that followed *that* was real and is described under "Incremental refresh".

The shape is:

```
getAvailableStacks(out):
    shared = mirror.getSharedAvailableStacks()      // its own cells, maintained
    if shared != null:
        if out == shared and nothing is uncovered:
            return                                  // the consumer already holds it
        copy shared into out absolutely
        for each mount the mirror does not cover:   // typically none
            mount.getAvailableStacks(out)           // Java, only for those
```

Three details are load-bearing, and all three cost a bug to find:

* The uncovered mounts are read into a counter of their own, never into the mirror's counter. That
  counter is handed out as a shared read-only snapshot across ticks, so adding to it would count the
  uncovered mounts again on every query.
* The mirrored amounts are written absolutely (`set`), while the uncovered ones are added (`add`),
  because `out` may still hold the previous query's contents.
* Keys that the previous query's uncovered mounts contributed, but which are neither mirrored nor
  uncovered any more, have to be removed explicitly. Nothing writes them again, so they would stay at
  their old amount forever.

`StorageService` needs the merged view too, but it must not end up writing into the mirror's counter
either, and it cannot go incremental on a merged cache. It keeps the uncovered mounts in a counter of
its own (`uncoveredStacks`), and a non-null value there means "the cache is a merge, rebuild it every
tick".

`RustStorageIndexTest` covers this: a versioned mount next to an unversioned one stays mirrored, a
filtered `MEInventoryHandler` mount (which is exactly what a filtered storage bus mounts) is added
exactly once, repeated queries do not accumulate it, and `deltasSince` refuses to serve while any
mount is uncovered - a delta stream that only describes the mirrored mounts would silently omit the
rest.

### Storage buses

| Mount | Mirrored | Why |
| --- | --- | --- |
| Drive cell (`BasicCellInventory` behind `DriveWatcher`) | yes | reports a version |
| Storage bus (`StorageBusInventory`) | partially | no version of its own, but it no longer disables the mirror |
| Filtering handler | partially | the mirror cannot reproduce the filter, so the mount is read in Java |
| Third-party `MEStorage` | partially | no version, so the mount is read in Java |

With partial coverage, "no" became "read in Java, next to a mirror that keeps working for everything
else". One storage bus in a base therefore no longer costs all of the acceleration; it costs itself.
That is the difference the `uncoveredStacks` path above exists for.

Giving a bus a version of its own is a separate, smaller problem and remains open:
`ExternalStorageFacade` already has a `setChangeListener` hook that is never wired up, and
`ExternalInventoryCache.update()` (which returns the changed keys) is never called. The bus could
report a version that moves when that cache reports a change. Two caveats before doing it:

* `StorageBusInventory` is an `MEInventoryHandler`; if the mirror is to reproduce its filter, the bus's
  `IPartitionList` has to be pushed down as a whitelist (the per-cell whitelist bitset already exists
  in the core; blacklists need a second bitset and a mode).
* `PrecisePriorityList.getItems()` must return the canonical key instances the identity-based interner
  needs. If it returns copies, their ids will not match the aggregate.

## Changes behind the current numbers

* `push_cell` now diffs the cell against its previous content and records only the totals that
  actually moved, instead of subtracting and re-adding the whole cell. One item inserted into a
  63-key cell records **one** change instead of ~126. `one_mutation_records_proportionally_few_changes`
  pins that down.
* The change log no longer elides a change that exactly undoes its predecessor. The elision looked
  safe because nothing observable changed, but it left a gap: a consumer that was one revision behind
  the cancelled entry replayed the next entry against a baseline it had never reached, and the mirror
  reported a stale total. `a_lagging_consumer_survives_a_cancelled_change` is the regression test.
* The per-tick benchmark used to insert one item per iteration without ever removing it, so the
  network grew to 3000 items while the phase ran. Because the consumer copies the aggregate it is
  handed, at a cost proportional to the stored types, that measured the consumer's growing copy and
  reported the mirror as *slower* than plain Java (0.4x). The phase now swaps one item in and one out,
  so the network is stable and the number describes the refresh itself.
* `StorageService.updateCachedStacks` used to walk every stored type on every tick even when nothing
  had changed, to compare each amount against the previous one and notify watchers. Nothing can have
  changed while the mirror's revision is unchanged, so an idle tick now returns before that walk. The
  per-key watcher bookkeeping itself is unchanged for ticks that do change something.
* `RustStorageIndex#resetProfiling`/`profilingSummary` attribute a phase to `sync`, the cell re-read
  and the delta application. They exist because the first honest-looking number here (0.4x) was an
  artifact twice over, and guessing at the cause wasted more time than measuring it.

## Limitations and next steps

* Only the aggregate read path is accelerated. Extract/insert fan-out is untouched.
* Fuzzy/partition filtering is handled by refusing to mirror filtered `MEInventoryHandler` mounts
  rather than by replicating the filter natively. Replicating it would let `Storage Bus`, `Export Bus`
  and level-emitter networks benefit too.
* ~~A per-key posting list would remove the remaining linear scan in the filtered query.~~
  Implemented: `NetworkIndex` keeps a reverse index of the cells holding each key, `extract` visits
  only those cells, and a filtered query picks the cheaper of walking the posting lists versus
  reading the cached aggregate.
* ~~The mirror's per-cell push should send only the changed keys instead of the whole cell.~~
  Implemented: `push_cell` diffs the cell and records only the totals that moved, so one mutation
  produces one change-log entry. The incremental refresh it was blocking is described above; it stays
  opt-in because the aggregate path it replaces is already cheap, not because it is broken.
* The cell re-read on a version change is the largest remaining cost of a mutating tick
  (`cellRead=1.0 us` of `~20 us` in the benchmark, plus `intern=3.2 us` and JNI `3.0 us` for the same
  63 entries). Pushing only changed keys does not avoid it, because the mirror has to read the cell to
  find out what changed. A bus or cell that reported its deltas instead of only a version would.
* If a future AE2 version lands [#8965/#8966/#8967](https://github.com/AppliedEnergistics/Applied-Energistics-2/pull/8967),
  the Java baseline in the benchmark should be updated to match, because those PRs change the very code
  the baseline models.
