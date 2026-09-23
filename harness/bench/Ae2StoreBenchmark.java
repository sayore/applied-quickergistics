package bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

import appeng.storage.nativebridge.DenseKeyInterner;
import appeng.storage.nativebridge.NativeLibrary;
import appeng.storage.nativebridge.NativeNetworkIndex;

/**
 * Standalone benchmark comparing AE2's current network-storage aggregation path against the Rust
 * {@code ae2store} dense index.
 * <p>
 * This harness deliberately avoids Minecraft so it can run in a plain JVM. The baseline below is a
 * faithful re-implementation of the data structures that AE2 actually uses:
 * <ul>
 * <li>{@code NetworkStorage#getAvailableStacks} fanning out over every mounted storage,</li>
 * <li>{@code BasicCellInventory} storing contents in an {@code Object2LongOpenHashMap<AEKey>},</li>
 * <li>{@code KeyCounter}/{@code VariantCounter} merging those entries into a
 * {@code Reference2ObjectMap<Object, VariantCounter>} with per-primary-key
 * {@code Object2LongOpenHashMap} records using equals/hashCode semantics.</li>
 * </ul>
 * The non-fuzzy/unordered variant is used because it is the hot path for plain items.
 */
public final class Ae2StoreBenchmark {

    /** Mirrors AEItemKey: cached hash, equals compares hash then the underlying stack. */
    static final class FakeKey {
        private final int id;
        private final int hashCode;

        /**
         * Mirrors {@code AEKey#getPrimaryKey}: the identity the interner groups variants by. Here the
         * fake key is its own primary key, so the benchmark stays one-id-per-key.
         */
        Object getPrimaryKey() {
            return this;
        }

        FakeKey(int id) {
            this.id = id;
            // Model ItemStack#hashItemAndComponents: not free, but cheap.
            this.hashCode = mix(id * 0x9E3779B1);
        }

        private static int mix(int x) {
            x ^= x >>> 16;
            x *= 0x7feb352d;
            x ^= x >>> 15;
            x *= 0x846ca68b;
            x ^= x >>> 16;
            return x;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            // Mirrors AEItemKey#equals: hash fast-fail then component comparison.
            return this.hashCode == ((FakeKey) o).hashCode && this.id == ((FakeKey) o).id;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public String toString() {
            return "key" + id;
        }
    }

    /** Mirrors BasicCellInventory's storage map. */
    static final class JavaCell {
        final Object2LongMap<FakeKey> contents = new Object2LongOpenHashMap<>();
        final int[] priorityAndId; // [priority, id]

        JavaCell(int priority) {
            this.priorityAndId = new int[] { priority, 0 };
        }
    }

    /** Mirrors KeyCounter + VariantCounter.UnorderedVariantMap. */
    static final class JavaKeyCounter {
        final Reference2ObjectMap<Object, Object2LongMap<FakeKey>> primary =
                new Reference2ObjectOpenHashMap<>();

        void add(FakeKey key, long amount) {
            var records = primary.get(key);
            if (records == null) {
                records = new Object2LongOpenHashMap<>();
                primary.put(key, records);
            }
            records.mergeLong(key, amount, Long::sum);
        }

        void clear() {
            primary.clear();
        }

        long get(FakeKey key) {
            var records = primary.get(key);
            return records == null ? 0 : records.getLong(key);
        }

        int size() {
            int total = 0;
            for (var records : primary.values()) {
                total += records.size();
            }
            return total;
        }
    }

    /** Mirrors NetworkStorage#getAvailableStacks over a flat list of mounted storages. */
    static void javaGetAvailableStacks(List<JavaCell> cells, JavaKeyCounter out) {
        for (var cell : cells) {
            for (var entry : cell.contents.object2LongEntrySet()) {
                out.add(entry.getKey(), entry.getLongValue());
            }
        }
    }

    /** Mirrors NetworkStorage#extract fanning out over every mounted storage. */
    static long javaExtract(List<JavaCell> cells, FakeKey key, long amount, boolean simulate) {
        long extracted = 0;
        for (var cell : cells) {
            if (extracted >= amount) {
                break;
            }
            var current = cell.contents.getLong(key);
            if (current <= 0) {
                continue;
            }
            var take = Math.min(current, amount - extracted);
            if (!simulate) {
                if (take == current) {
                    cell.contents.removeLong(key);
                } else {
                    cell.contents.put(key, current - take);
                }
            }
            extracted += take;
        }
        return extracted;
    }

    /** Mirrors NetworkStorage#insert fanning out over every mounted storage. */
    static long javaInsert(List<JavaCell> cells, FakeKey key, long amount, boolean simulate) {
        long inserted = 0;
        for (var cell : cells) {
            if (inserted >= amount) {
                break;
            }
            var current = cell.contents.getLong(key);
            var take = amount - inserted;
            if (!simulate) {
                cell.contents.put(key, current + take);
            }
            inserted += take;
        }
        return inserted;
    }

    // ---------------------------------------------------------------------------------------------

    record Params(int cellCount, int keyCount, int seed) {
    }

    static final class Fixture {
        final Params params;
        final FakeKey[] keys;
        final List<JavaCell> cells = new ArrayList<>();
        final DenseKeyInterner<FakeKey> interner = new DenseKeyInterner<>(FakeKey::getPrimaryKey);
        final NativeNetworkIndex nativeIndex;
        final int[] cellIds;
        final int[] keyIds;
        final int[] priorities;

        Fixture(Params params) {
            this.params = params;
            var rnd = new Random(params.seed());
            this.keys = new FakeKey[params.keyCount()];
            for (var i = 0; i < keys.length; i++) {
                keys[i] = new FakeKey(i);
                interner.intern(keys[i]);
            }
            this.keyIds = new int[params.keyCount()];
            for (var i = 0; i < keys.length; i++) {
                keyIds[i] = interner.idOf(keys[i]);
            }

            this.nativeIndex = NativeLibrary.isAvailable() ? NativeNetworkIndex.create() : null;
            this.cellIds = new int[params.cellCount()];
            this.priorities = new int[params.cellCount()];

            // Distribute keys over cells: popular items appear in many cells, rare ones in few.
            for (var c = 0; c < params.cellCount(); c++) {
                var priority = c % 5;
                priorities[c] = priority;
                var cell = new JavaCell(priority);
                cells.add(cell);
                var entries = new ArrayList<long[]>(); // [keyId, amount]
                for (var k = 0; k < params.keyCount(); k++) {
                    // Popularity falls off with key index; 30% of the keys are network-wide staples.
                    boolean present;
                    if (k < params.keyCount() * 0.3) {
                        present = rnd.nextInt(100) < 60;
                    } else {
                        present = rnd.nextInt(100) < 4;
                    }
                    if (!present) {
                        continue;
                    }
                    var amount = 1L + rnd.nextInt(1_000_000);
                    cell.contents.put(keys[k], amount);
                    entries.add(new long[] { keyIds[k], amount });
                }
                if (nativeIndex != null) {
                    cellIds[c] = nativeIndex.addCell(interner.size(), priority);
                    var packed = new long[entries.size() * 2];
                    for (var i = 0; i < entries.size(); i++) {
                        packed[i * 2] = entries.get(i)[0];
                        packed[i * 2 + 1] = entries.get(i)[1];
                    }
                    nativeIndex.pushCell(cellIds[c], interner.size(), packed, entries.size());
                }
            }
        }

        int totalKeysStored() {
            var total = 0;
            for (var cell : cells) {
                total += cell.contents.size();
            }
            return total;
        }
    }

    // ---------------------------------------------------------------------------------------------

    interface Bench {
        void run();
    }

    static final class Timer {
        final String name;
        long nanos;
        int runs;

        Timer(String name) {
            this.name = name;
        }

        void time(Bench bench, int iterations) {
            // Warmup
            for (var i = 0; i < Math.max(1, iterations / 4); i++) {
                bench.run();
            }
            var start = System.nanoTime();
            for (var i = 0; i < iterations; i++) {
                bench.run();
            }
            var elapsed = System.nanoTime() - start;
            nanos += elapsed;
            runs += iterations;
        }

        double avgMicros() {
            return nanos / 1000.0 / runs;
        }
    }

    public static void main(String[] args) {
        System.out.println("=== ae2store benchmark ===");
        System.out.println("java            : " + System.getProperty("java.version"));
        System.out.println("native available: " + NativeLibrary.isAvailable());
        System.out.println("native status   : " + NativeLibrary.getStatus());
        System.out.println();

        var params = new Params(
                Integer.getInteger("bench.cells", 400),
                Integer.getInteger("bench.keys", 1500),
                Integer.getInteger("bench.seed", 42));
        var iterations = Integer.getInteger("bench.iterations", 2000);

        System.out.printf("scenario: %d cells, %d distinct keys, %d iterations%n",
                params.cellCount(), params.keyCount(), iterations);

        var fixture = new Fixture(params);
        System.out.printf("cell/type pairs actually stored: %d%n%n", fixture.totalKeysStored());

        benchmarkAvailable(fixture, iterations);
        benchmarkAvailableFiltered(fixture, iterations);
        benchmarkTickChurn(fixture, iterations);
        benchmarkExtract(fixture, iterations);
        benchmarkInsertMutating(fixture, iterations);
    }

    /**
     * The realistic server-tick shape: something in the network changes, then the aggregate is
     * queried. The change invalidates any cached aggregate, so this measures the actual rebuild path
     * rather than the cache hit.
     */
    private static void benchmarkTickChurn(Fixture fixture, int iterations) {
        var cell = fixture.cells.get(0);
        var key = fixture.keys[1];
        var counter = new JavaKeyCounter();

        var java = new Timer("java");
        java.time(() -> {
            // One item moved in, one item moved out, then the tick's aggregate query.
            cell.contents.mergeLong(key, 1, Long::sum);
            cell.contents.mergeLong(key, -1, Long::sum);
            counter.clear();
            javaGetAvailableStacks(fixture.cells, counter);
        }, iterations);

        if (fixture.nativeIndex == null) {
            System.out.printf("tick churn + getAvailableStacks%n  java : %10.2f us/op%n%n", java.avgMicros());
            return;
        }

        var keyId = fixture.keyIds[1];
        var cellId = fixture.cellIds[0];
        var nativeTimer = new Timer("rust");
        nativeTimer.time(() -> {
            fixture.nativeIndex.applyCellDelta(cellId, keyId, 1);
            fixture.nativeIndex.applyCellDelta(cellId, keyId, -1);
            var flat = fixture.nativeIndex.available();
            if (flat.length == 0) {
                throw new IllegalStateException("empty");
            }
        }, iterations);

        System.out.printf("tick churn + getAvailableStacks (cache invalidated each tick)%n");
        System.out.printf("  java        : %10.2f us/op%n", java.avgMicros());
        System.out.printf("  rust (jni)  : %10.2f us/op   speedup %.1fx%n%n",
                nativeTimer.avgMicros(), java.avgMicros() / nativeTimer.avgMicros());
    }

    private static void benchmarkAvailable(Fixture fixture, int iterations) {
        var counter = new JavaKeyCounter();
        var java = new Timer("java");
        java.time(() -> {
            counter.clear();
            javaGetAvailableStacks(fixture.cells, counter);
        }, iterations);

        if (fixture.nativeIndex == null) {
            System.out.printf("getAvailableStacks (full rebuild)%n  java : %10.2f us/op%n%n", java.avgMicros());
            return;
        }

        // Verify the native result matches the Java baseline before timing it.
        counter.clear();
        javaGetAvailableStacks(fixture.cells, counter);
        verifyAvailable(fixture, counter);

        var nativeTimer = new Timer("rust");
        nativeTimer.time(() -> {
            var flat = fixture.nativeIndex.available();
            if (flat.length == 0) {
                throw new IllegalStateException("empty");
            }
        }, iterations);

        System.out.printf("getAvailableStacks (full rebuild)%n");
        System.out.printf("  java        : %10.2f us/op%n", java.avgMicros());
        System.out.printf("  rust (jni)  : %10.2f us/op   speedup %.1fx%n%n",
                nativeTimer.avgMicros(), java.avgMicros() / nativeTimer.avgMicros());
    }

    private static void benchmarkAvailableFiltered(Fixture fixture, int iterations) {
        if (fixture.nativeIndex == null) {
            return;
        }
        // A terminal search for ~32 keys, which is what a filtered query looks like in practice.
        var filter = new int[32];
        for (var i = 0; i < filter.length; i++) {
            filter[i] = fixture.keyIds[i * 7 % fixture.keyIds.length];
        }

        var java = new Timer("java");
        java.time(() -> {
            long sum = 0;
            for (var id : filter) {
                var key = fixture.interner.keyOf(id);
                for (var cell : fixture.cells) {
                    sum += cell.contents.getLong(key);
                }
            }
            if (sum < 0) {
                throw new IllegalStateException();
            }
        }, iterations);

        var nativeTimer = new Timer("rust");
        nativeTimer.time(() -> {
            var flat = fixture.nativeIndex.available(filter);
            if (flat.length < 0) {
                throw new IllegalStateException();
            }
        }, iterations);

        System.out.printf("getAvailableStacks (filtered to %d keys)%n", filter.length);
        System.out.printf("  java        : %10.2f us/op%n", java.avgMicros());
        System.out.printf("  rust (jni)  : %10.2f us/op   speedup %.1fx%n%n",
                nativeTimer.avgMicros(), java.avgMicros() / nativeTimer.avgMicros());
    }

    private static void benchmarkExtract(Fixture fixture, int iterations) {
        // Simulated extraction is the crafting-calculation hot path: it must not mutate.
        var key = fixture.keys[3];
        var java = new Timer("java");
        java.time(() -> {
            var got = javaExtract(fixture.cells, key, 500, true);
            if (got < 0) {
                throw new IllegalStateException();
            }
        }, iterations);

        if (fixture.nativeIndex == null) {
            System.out.printf("extract SIMULATE%n  java : %10.2f us/op%n%n", java.avgMicros());
            return;
        }

        var keyId = fixture.keyIds[3];
        var nativeTimer = new Timer("rust");
        nativeTimer.time(() -> {
            var got = fixture.nativeIndex.extract(keyId, 500, true);
            if (got < 0) {
                throw new IllegalStateException();
            }
        }, iterations);

        System.out.printf("extract SIMULATE (single key, 500 items)%n");
        System.out.printf("  java        : %10.2f us/op%n", java.avgMicros());
        System.out.printf("  rust (jni)  : %10.2f us/op   speedup %.1fx%n%n",
                nativeTimer.avgMicros(), java.avgMicros() / nativeTimer.avgMicros());
    }

    /**
     * Models real mutation traffic: a burst of small inserts and extracts on a single key. This is
     * the loop that pattern providers, import/export buses and automation drive, and unlike the
     * simulate variants it has to actually touch storage.
     */
    private static void benchmarkInsertMutating(Fixture fixture, int iterations) {
        var key = fixture.keys[9];
        var java = new Timer("java");
        java.time(() -> {
            javaInsert(fixture.cells, key, 64, false);
            javaExtract(fixture.cells, key, 64, false);
        }, iterations);

        if (fixture.nativeIndex == null) {
            System.out.printf("insert+extract MODULATE%n  java : %10.2f us/op%n%n", java.avgMicros());
            return;
        }

        var keyId = fixture.keyIds[9];
        var nativeTimer = new Timer("rust");
        nativeTimer.time(() -> {
            fixture.nativeIndex.insert(keyId, 64, false);
            fixture.nativeIndex.extract(keyId, 64, false);
        }, iterations);

        System.out.printf("insert+extract MODULATE (single key, 64 items each way)%n");
        System.out.printf("  java        : %10.2f us/op%n", java.avgMicros());
        System.out.printf("  rust (jni)  : %10.2f us/op   speedup %.1fx%n%n",
                nativeTimer.avgMicros(), java.avgMicros() / nativeTimer.avgMicros());
    }

    /** Cross-checks the native aggregate against the Java baseline. */
    private static void verifyAvailable(Fixture fixture, JavaKeyCounter reference) {
        var flat = fixture.nativeIndex.available();
        var mismatches = 0;
        for (var i = 0; i < flat.length; i += 2) {
            var id = (int) flat[i];
            var amount = flat[i + 1];
            var expected = reference.get(fixture.interner.keyOf(id));
            if (expected != amount) {
                if (mismatches++ < 5) {
                    System.err.printf("MISMATCH key %d: java=%d rust=%d%n", id, expected, amount);
                }
            }
        }
        if (mismatches > 0) {
            throw new IllegalStateException("native aggregate does not match Java baseline: " + mismatches);
        }
        var referenceSize = reference.size();
        var nativeSize = flat.length / 2;
        if (referenceSize != nativeSize) {
            throw new IllegalStateException(
                    "native has " + nativeSize + " keys, java has " + referenceSize);
        }
        System.out.printf("  [verified: %d keys agree between java and rust]%n", nativeSize);
    }
}
