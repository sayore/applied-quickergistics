# Handoff

Written for whoever continues this work (human or agent) with no memory of the sessions that produced
it. Everything below is either verifiable from the repository or was measured and is recorded in a
commit message, a test, or [harness/docs/native-storage-acceleration.md](harness/docs/native-storage-acceleration.md).

## Start here

```bash
# Current commit and whether the tree is clean
git log --oneline -1 && git status --short

# The three suites, in increasing cost
cd ae2-src/rust && cargo test --release            # Rust core, ~seconds
cd ae2-src && JAVA_HOME=<jdk-21> ./gradlew :test   # full Java suite, ~1 min
cd ae2-src && JAVA_HOME=<jdk-21> ./gradlew runGametest   # in-game tests, ~30 s each run
cd harness && ./run.sh                             # native build + benchmark + 50-check harness
```

## Verification state at `12c304a` / `b524eb2`

| Suite | Result | Notes |
| --- | --- | --- |
| Java `:test` (full) | 540/540 | verified before `12c304a` |
| Rust `cargo test` | 33/33 | verified before `12c304a` |
| `harness/run.sh` | 50/50 | verified before `12c304a` |
| `runGametest` | 77/77 | **not verified after `12c304a`** |

### Open item: unverified game-test fix (`12c304a`)

Two game tests failed intermittently, roughly **1 run in 6**:

* `ae2:multi_storage_bus` (`SubnetPlots`) — failed with "Network storage does not contain
  minecraft:red_concrete. Available keys: []". The storage buses poll their target inventories, so the
  contents appear on the buses' schedule, not on a fixed tick. The plot idled one tick and then
  asserted. Fix: the retryable assertions are now the wait condition.
* `ae2:regression_7288` (`AutoCraftingTestPlots`) — failed with "failed to submit job on tick 152".
  Fix: the idle after starting the crafting job was widened from 1 to 5 ticks. **This is the weakest
  kind of fix and may still be flaky.**

`12c304a` was committed **without re-running the suite** (the verification loop was stopped on
request). Re-run `runGametest` repeatedly — a single pass proves nothing at a 1-in-6 rate — before
trusting either.

An earlier attempt at `regression_7288` waited for the item entity to appear and was **wrong**: the
extra diamond only exists *after* the network is broken, so waiting for it deadlocks the test and it
failed every run. Do not reintroduce that.

### Untested surfaces

These have not been exercised and are the honest gaps:

* The mirror with a realistic **mixture** of drive cells and filtered handlers at scale. The
  partial-coverage fuzz uses synthetic storages, and the real-cell tests use a homogeneous drive array.
* A live **client** reading a block entity's ME capability from the client thread. Every path found
  resolves server-side, but no test asserts it.
* **Long-running** behaviour: the delta log under extended play, and the native `Cleaner` under real GC
  pressure.
* **Save/load** round-trips of a live network. Reasoned safe (the mirror holds no persisted state and
  rebuilds on first read) but not tested.

## Environment and process gotchas

These cost real time to discover. They are not in upstream's `AGENTS.md`.

* **JDK 21 is required to *launch* Gradle.** A newer JDK (26) breaks Gradle's Groovy. Example that
  works here: `JAVA_HOME=/home/rainy/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2 ./gradlew ...`.
  The Java toolchain is then auto-provisioned (JDK 25) for compilation and tests.
* **`-Dae2.native.*` on the Gradle command line does not reach the test JVM.** Set properties in a
  **static initializer** in the test class, before `RustStorageIndex` is first loaded. A constructor is
  too late because `@BootstrapMinecraft` can load classes first. See
  `RealCellPerformanceTest`'s static block for the working pattern.
* **Game tests need a full Minecraft bootstrap.** A test that constructs `ItemStack` / `AEItemKey`
  without it fails with "Components not bound yet". Add both `@BootstrapMinecraft` **and**
  `@ExtendWith(EphemeralTestServerProvider.class)` and take a `MinecraftServer` constructor parameter.
  Missing the second one passes only when another test in the same JVM happened to bootstrap first —
  that is what made `NetworkCraftingProvidersTest` fail in the full suite while passing alone.
* **`thenWaitUntil(...)` retries only while the body throws a game-test assertion.** Throwing a plain
  `AssertionError` **crashes the whole test server**; returning normally passes on the first tick. So
  the wait body must fail *and* fail with the right type: `helper.check(condition, message)` throws
  `GameTestAssertException`, and it is unchecked (`GameTestAssertException` -> `GameTestException` ->
  `RuntimeException`), so it can be called straight from the lambda even though the method signature
  says `throws`. The retryable assertion helpers (`assertNetworkContains`, ...) work the same way.
* **A plot's geometry matters in a way that fails silently.** A drive two blocks from the cable is not
  on the grid at all: the plot sees no storage rather than an error. Verify by asserting on something
  positive (e.g. a simulated insert returns the expected amount) rather than assuming.
* **Assert that a new test can fail.** A game test that silently does nothing is worse than none.
  Temporarily break the expectation and confirm the test fails before trusting it.
* **`KeyCounter` is backed by identity maps.** `Map#equals` reports equal contents as different, and
  comparing keys by identity is unreliable in tests that can hold two `Item` instances for one item.
  Compare entry-wise, or aggregate by key equality. This has caused three separate false alarms here.

## Where the knowledge lives

* [README.md](README.md) — what the project is, measured results, caveats, correctness summary.
* [harness/docs/native-storage-acceleration.md](harness/docs/native-storage-acceleration.md) — the
  design, the measurement methodology, and the history of what was fixed and why.
* `harness/docs/benchmark-results.txt` — raw capture of the synthetic benchmark.
* Commit messages — each fix explains the bug, how it was found, and how it was verified. `git log` is
  the detailed record; start with `git log --oneline` and read the ones that matter.
* Test names encode the bugs: `cell_ids_stay_stable_across_reuse`,
  `removed_ids_stay_retired_across_reorders`, `coverageRecoversWhenAnUnmirrorableMountGoesAway`,
  `deltaStreamReplaysToTheSameAggregateAsAFullRefresh`, `one_mutation_records_proportionally_few_changes`,
  `a_lagging_consumer_survives_a_cancelled_change`, `filtered_query_ignores_ids_outside_the_key_space`,
  and the fuzz tests.

## Bugs already found and fixed

Recorded so they are not re-investigated, and because several were subtler than they look. Each has a
test that fails without its fix.

1. **Keys interned by identity, not primary key.** `AEItemKey.of(stack)` returns a fresh instance per
   call, so one logical item got two ids; the native side summed it while the Java counter kept the
   variants apart, so a delta to one overwrote the other's amount (100 + 7 reported 117, then 8).
2. **A removed cell's id still resolved to its recycled slot**, so the mirror read a drive that no
   longer existed as if it were the newly mounted one.
3. **The same bug again via `reorder`**, which renumbers slots but only rewrote the mapping for live
   cells.
4. **Native `insert` never updated the posting list**, making an inserted key permanently
   unextractable (`extract` returned 0 while the total read 256).
5. **Coverage was never recomputed** — the uncovered set was only added to, so one unmirrorable mount
   degraded the network (and disabled its delta stream) for the rest of the session.
6. **A filtered query sized an accumulator to a caller-supplied key id**: one junk id asked for tens of
   gigabytes.
7. **The change log could only fill**, because a consumer that can never be served kept it growing.
8. **A leaked native index per network** — nothing in the grid lifecycle closed it.
9. **An unsound change-log elision**: a consumer one revision behind a cancelled entry replayed the
   next entry against a baseline it never reached.

## Working agreement that produced this

The measurements here have been wrong several times in ways that flattered the result, and the test
expectations have been wrong more often than the code. Both are recorded in the history rather than
quietly corrected. Keep doing that: measure before claiming, state the uncertainty range, and when a
test fails, check the test before assuming the product is broken.
