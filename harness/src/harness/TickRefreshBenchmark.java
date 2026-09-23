package harness;

import java.util.IdentityHashMap;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.me.storage.NetworkStorage;

/**
 * Compares the two ways {@code StorageService} can refresh its per-tick inventory:
 *
 * <ul>
 * <li>a full aggregate over every mounted cell, which is what AE2 does today, and</li>
 * <li>applying only the keys that changed since the previous tick, which is what the native delta
 * stream makes possible.</li>
 * </ul>
 *
 * The mutation pattern matters more than the network size here: the win is proportional to how few
 * keys change per tick compared to how many exist.
 */
public final class TickRefreshBenchmark {
    public static void main(String[] args) throws Exception {
        var cellCount = Integer.getInteger("bench.cells", 400);
        var keyCount = Integer.getInteger("bench.keys", 1500);
        var ticks = Integer.getInteger("bench.ticks", 4000);
        var changesPerTick = Integer.getInteger("bench.changesPerTick", 20);
        var holderPercent = Integer.getInteger("bench.holders", 4);

        var network = new NetworkStorage();
        var cells = new java.util.ArrayList<NativeIntegrationHarness.FakeStorage>();
        var keys = new NativeIntegrationHarness.FakeKey[keyCount];
        for (var i = 0; i < keyCount; i++) {
            keys[i] = new NativeIntegrationHarness.FakeKey(i);
        }
        for (var c = 0; c < cellCount; c++) {
            var cell = new NativeIntegrationHarness.FakeStorage();
            for (var k = 0; k < keyCount; k++) {
                if ((k * 31 + c * 17) % 100 < holderPercent) {
                    cell.set(keys[k], 1 + (k * 7 + c) % 1000);
                }
            }
            cells.add(cell);
            network.mount(c % 5, cell);
        }

        var totalKeys = 0;
        for (var cell : cells) {
            totalKeys += cell.contents.size();
        }
        System.out.printf("tick refresh benchmark: %d cells, %d stored entries, %d changes/tick, "
                        + "%d ticks%n%n", cellCount, totalKeys, changesPerTick, ticks);

        // --- full aggregate every tick (AE2 today)
        var counter = new KeyCounter();
        var start = System.nanoTime();
        for (var tick = 0; tick < ticks; tick++) {
            mutate(cells, keys, changesPerTick, tick);
            counter.clear();
            network.getAvailableStacks(counter);
            if (counter.isEmpty()) {
                throw new IllegalStateException();
            }
        }
        var fullMicros = (System.nanoTime() - start) / 1000.0 / ticks;

        // --- delta stream (native mirror)
        var revision = network.revision();
        var incremental = new IdentityHashMap<AEKey, Long>();
        var authoritative = new KeyCounter();
        network.getAvailableStacks(authoritative);
        for (var entry : authoritative) {
            incremental.put(entry.getKey(), entry.getLongValue());
        }
        revision = network.revision();

        var notifications = 0;
        start = System.nanoTime();
        for (var tick = 0; tick < ticks; tick++) {
            mutate(cells, keys, changesPerTick, tick);
            var deltas = network.deltasSince(revision);
            if (deltas == null) {
                // Fall back, exactly like StorageService does.
                var fresh = new KeyCounter();
                network.getAvailableStacks(fresh);
                incremental.clear();
                for (var entry : fresh) {
                    incremental.put(entry.getKey(), entry.getLongValue());
                }
                revision = network.revision();
                continue;
            }
            for (var change : deltas.changes()) {
                if (change.newTotal() == 0) {
                    incremental.remove(change.key());
                } else {
                    incremental.put(change.key(), change.newTotal());
                }
                if (change.oldTotal() != change.newTotal()) {
                    notifications++;
                }
            }
            revision = deltas.revision();
        }
        var deltaMicros = (System.nanoTime() - start) / 1000.0 / ticks;

        System.out.printf("  full aggregate per tick : %10.3f us/tick%n", fullMicros);
        System.out.printf("  delta stream per tick   : %10.3f us/tick   speedup %.1fx%n",
                deltaMicros, fullMicros / deltaMicros);
        System.out.printf("  (%d watcher notifications over %d ticks)%n", notifications, ticks);

        System.out.printf("  net effect: %+.0f%% per tick%n",
                (deltaMicros / fullMicros - 1.0) * 100.0);
        System.out.println();
        System.out.println("  NOTE: the incremental path is disabled by default (see");
        System.out.println("  RustStorageIndex.isIncrementalRefreshEnabled). The numbers above show why: the");
        System.out.println("  mirror currently records far more changes than the network actually saw, because a");
        System.out.println("  cell whose version changed is re-pushed and its whole content is re-recorded.");
        System.out.println("  Rerun with -D" + appeng.me.storage.RustStorageIndex.INCREMENTAL_PROPERTY
                + "=true to see the enabled behaviour.");
    }

    private static void mutate(java.util.List<NativeIntegrationHarness.FakeStorage> cells,
            NativeIntegrationHarness.FakeKey[] keys, int changes, int tick) {
        var rnd = new java.util.Random(tick * 31L + 7);
        for (var i = 0; i < changes; i++) {
            var cell = cells.get(rnd.nextInt(cells.size()));
            var key = keys[rnd.nextInt(keys.length)];
            cell.set(key, 1 + rnd.nextInt(100000));
        }
    }
}
