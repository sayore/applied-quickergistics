/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2025, TeamAppliedEnergistics, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.me.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.core.definitions.AEItems;
import appeng.me.cells.BasicCellInventory;
import appeng.me.helpers.BaseActionSource;
import appeng.util.BootstrapMinecraft;

/**
 * Measures the native storage mirror against a drive array of <em>real</em> AE2 cells.
 * <p>
 * The synthetic benchmark in {@code harness/} measures the same operations but against hand-written storages, so it
 * cannot see the cost of AE2's own wrappers ({@link DriveWatcher} and {@link MEInventoryHandler}) or of
 * {@link BasicCellInventory}'s bookkeeping. This test builds the real thing from the item registry and reports timings
 * on stdout.
 * <p>
 * It runs headlessly through {@code @BootstrapMinecraft}, so it needs no GPU and no server boot.
 * <p>
 * The numbers are informational, not assertions: a virtual machine's timings say little about a real server, and a hard
 * threshold here would only make the suite flaky. What <em>is</em> asserted is that both paths return the same
 * aggregate, so a timing run can never silently measure a broken mirror.
 */
@BootstrapMinecraft
@ExtendWith(EphemeralTestServerProvider.class)
class RealCellPerformanceTest {
    static {
        // The mirror is opt-in, and RustStorageIndex resolves that flag when its class is first
        // loaded - which the Minecraft bootstrap below can trigger. Set it before any of that runs.
        System.setProperty(RustStorageIndex.ENABLED_PROPERTY, "true");
        // `-Dae2.native.incremental=true` on the Gradle command line does not reach the test JVM,
        // so the same switch can be given as `-Dae2.perf.incremental=true`, which is copied over
        // before RustStorageIndex is initialized.
        if (Boolean.getBoolean("ae2.perf.incremental")) {
            System.setProperty(RustStorageIndex.INCREMENTAL_PROPERTY, "true");
        }
    }

    /** Real AE2 caps a cell at 63 types; using the cap keeps the drive realistic. */
    private static final int TYPES_PER_CELL = 63;

    private final BaseActionSource src = new BaseActionSource();
    private final MinecraftServer server;

    RealCellPerformanceTest(MinecraftServer server) {
        this.server = server;
    }

    /** A drive cell holding up to {@code typesPerCell} distinct items. */
    private static DriveWatcher buildCell(List<AEKey> keys, int from, int to) {
        var cell = AEItems.ITEM_CELL_64K.stack();
        var inventory = BasicCellInventory.createInventory(cell, null);
        assertThat(inventory).isNotNull();
        var source = new BaseActionSource();
        for (var i = from; i < to; i++) {
            var inserted = inventory.insert(keys.get(i), 1000 + i, Actionable.MODULATE, source);
            assertThat(inserted).as("inserting key %d", i).isEqualTo(1000 + i);
        }
        return new DriveWatcher(inventory, () -> {
        });
    }

    /** All item keys the server registry offers, in a stable order. */
    private static List<AEKey> collectKeys() {
        var keys = new ArrayList<AEKey>();
        for (var item : BuiltInRegistries.ITEM) {
            // Skip anything without a valid default stack.
            var stack = new ItemStack(item);
            if (stack.isEmpty()) {
                continue;
            }
            var key = AEItemKey.of(stack);
            if (key != null) {
                keys.add(key);
            }
            if (keys.size() >= 4000) {
                break;
            }
        }
        // Vanilla alone offers about 1900 item types, which bounds the drive array this test can
        // build. A modded server would offer far more, which is why the array size is derived from
        // what is actually available rather than hard-coded.
        assertThat(keys.size()).as("the registry must offer enough item types").isGreaterThan(500);
        return keys;
    }

    /**
     * Disables the mirror on a network, so the same class runs AE2's original code path.
     * <p>
     * The enable flag is resolved once per JVM, so this is the only way to compare both paths in a single process
     * without differences in JIT warmup, world state or machine load polluting the comparison.
     */
    private static NetworkStorage withoutMirror(NetworkStorage network) {
        try {
            var field = NetworkStorage.class.getDeclaredField("nativeIndex");
            field.setAccessible(true);
            field.set(network, null);
            return network;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot disable the mirror", e);
        }
    }

    /** The aggregate produced by walking the cells, which is what AE2 does today. */
    private static KeyCounter javaAggregate(List<MEStorage> mounts) {
        var counter = new KeyCounter();
        for (var mount : mounts) {
            mount.getAvailableStacks(counter);
        }
        return counter;
    }

    private static void report(String what, String path, long nanos, int iterations) {
        System.out.printf("  %-34s %-8s %10.3f us/op%n", what, path, nanos / 1000.0 / iterations);
    }

    /**
     * Runs {@code body} {@code iterations} times after a warmup and returns the average microseconds.
     */
    private static double time(Consumer<Integer> body, int iterations) {
        for (var i = 0; i < Math.max(1, iterations / 4); i++) {
            body.accept(i);
        }
        var start = System.nanoTime();
        for (var i = 0; i < iterations; i++) {
            body.accept(i);
        }
        return (System.nanoTime() - start) / 1000.0 / iterations;
    }

    /** Builds an identical pair of networks: one with the mirror, one running plain Java. */
    private record Fixture(List<MEStorage> mirrorMounts, NetworkStorage mirrorNetwork,
            List<MEStorage> javaMounts, NetworkStorage javaNetwork) {
    }

    private Fixture buildFixture(List<AEKey> keys, int cellCount) {
        var fixture = buildPair(keys, cellCount);
        // Both paths must agree before anything is timed.
        var expected = javaAggregate(fixture.mirrorMounts());
        var actual = new KeyCounter();
        fixture.mirrorNetwork().getAvailableStacks(actual);
        assertSameContents(actual, expected);
        return fixture;
    }

    private Fixture buildPair(List<AEKey> keys, int cellCount) {
        var mirrorMounts = new ArrayList<MEStorage>();
        var mirrorNetwork = new NetworkStorage();
        var javaMounts = new ArrayList<MEStorage>();
        var javaNetwork = new NetworkStorage();
        for (var c = 0; c < cellCount; c++) {
            var from = c * TYPES_PER_CELL;
            var to = Math.min(from + TYPES_PER_CELL, keys.size());
            if (from >= to) {
                break;
            }
            var mirrorCell = buildCell(keys, from, to);
            mirrorMounts.add(mirrorCell);
            mirrorNetwork.mount(c % 5, mirrorCell);
            var javaCell = buildCell(keys, from, to);
            javaMounts.add(javaCell);
            javaNetwork.mount(c % 5, javaCell);
        }
        withoutMirror(javaNetwork);
        return new Fixture(mirrorMounts, mirrorNetwork, javaMounts, javaNetwork);
    }

    private static RustStorageIndex mirrorOf(NetworkStorage network) {
        try {
            var field = NetworkStorage.class.getDeclaredField("nativeIndex");
            field.setAccessible(true);
            return (RustStorageIndex) field.get(network);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reportDiagnostics(String phase, NetworkStorage network, int iterations) {
        var mirror = mirrorOf(network);
        if (mirror == null) {
            return;
        }
        var d = mirror.diag;
        System.out.printf("  %-30s rebuilds=%d incremental=%d syncWalks=%d pushes=%d (over %d queries)%n",
                phase, d[0], d[1], d[2], d[3], iterations);
    }

    /**
     * The steady state: nothing changes between queries, which is what a network that is not being written to looks
     * like. This is the case the mirror should win outright.
     */
    @Test
    void measureSteadyStateAggregate() {
        var keys = collectKeys();
        var maxCells = keys.size() / TYPES_PER_CELL;
        for (var cellCount : new int[] { 1, 4, maxCells / 2, maxCells }) {
            measureSteadyState(keys, cellCount);
        }
    }

    private void measureSteadyState(List<AEKey> keys, int cellCount) {
        var fixture = buildFixture(keys, cellCount);
        var iterations = 3000;

        System.out.printf("%n=== steady state, %d cells, %d types ===%n",
                fixture.mirrorMounts().size(), fixture.mirrorMounts().size() * TYPES_PER_CELL);

        var javaOut = new KeyCounter();
        var javaMicros = time(i -> {
            javaOut.clear();
            fixture.javaNetwork().getAvailableStacks(javaOut);
        }, iterations);
        // Reproduce StorageService's actual per-tick pattern: keep the counter it adopted and hand
        // it back, so the mirror can recognise it and skip the copy.
        var adopted = fixture.mirrorNetwork().getSharedAvailableStacks();
        assertThat(adopted).as("mirror must be usable").isNotNull();
        var mirrorMicros = time(i -> fixture.mirrorNetwork().getAvailableStacks(adopted), iterations);

        report("aggregate, no changes", "java", (long) (javaMicros * 1000 * iterations), iterations);
        report("aggregate, no changes", "mirror", (long) (mirrorMicros * 1000 * iterations), iterations);
        System.out.printf("  %-30s %-8s %10.2fx%n", "", "speedup", javaMicros / mirrorMicros);
        reportDiagnostics("steady state", fixture.mirrorNetwork(), iterations);
    }

    /**
     * The realistic tick: one cell changes, then the inventory is refreshed. The mirror should only have to touch what
     * changed, while the Java path re-reads every cell.
     */
    @Test
    void measureTickWithOneChange() {
        var keys = collectKeys();
        var cellCount = keys.size() / TYPES_PER_CELL;
        var fixture = buildFixture(keys, cellCount);
        var iterations = 3000;

        System.out.printf("%n=== one cell changes per tick, %d cells, %d types ===%n",
                fixture.mirrorMounts().size(), fixture.mirrorMounts().size() * TYPES_PER_CELL);

        var liveKey = keys.get(0);

        // The tick being measured is: one cell changes, then the consumer refreshes the aggregate.
        // The change is a swap - one item in, one item out - so the network neither grows nor shrinks
        // while the phase runs. Letting the network accumulate one item per iteration would instead
        // measure the consumer's per-key copy growing with the network, which is what made an earlier
        // version of this benchmark report the mirror as slower than plain Java.
        var javaOut = new KeyCounter();
        var javaLive = (BasicCellInventory) ((appeng.api.storage.cells.StorageCell) ((DriveWatcher) fixture
                .javaMounts().get(0)).getDelegate());
        var javaMicros = time(i -> {
            javaLive.insert(liveKey, 1, Actionable.MODULATE, src);
            javaLive.extract(liveKey, 1, Actionable.MODULATE, src);
            javaLive.insert(liveKey, 1, Actionable.MODULATE, src);
            javaOut.clear();
            fixture.javaNetwork().getAvailableStacks(javaOut);
        }, iterations);

        var mirror = mirrorOf(fixture.mirrorNetwork());
        var adopted = fixture.mirrorNetwork().getSharedAvailableStacks();
        assertThat(adopted).as("mirror must be usable").isNotNull();
        var mirrorLive = (BasicCellInventory) ((appeng.api.storage.cells.StorageCell) ((DriveWatcher) fixture
                .mirrorMounts().get(0)).getDelegate());
        // Warm up before profiling and timing, so the numbers describe the steady state.
        for (var i = 0; i < iterations / 4; i++) {
            mirrorLive.insert(liveKey, 1, Actionable.MODULATE, src);
            mirrorLive.extract(liveKey, 1, Actionable.MODULATE, src);
            mirrorLive.insert(liveKey, 1, Actionable.MODULATE, src);
            fixture.mirrorNetwork().getAvailableStacks(adopted);
        }
        mirror.resetProfiling();

        var mirrorStart = System.nanoTime();
        for (var i = 0; i < iterations; i++) {
            mirrorLive.insert(liveKey, 1, Actionable.MODULATE, src);
            mirrorLive.extract(liveKey, 1, Actionable.MODULATE, src);
            mirrorLive.insert(liveKey, 1, Actionable.MODULATE, src);
            fixture.mirrorNetwork().getAvailableStacks(adopted);
        }
        var mirrorMicros = (System.nanoTime() - mirrorStart) / 1000.0 / iterations;

        report("one change, then aggregate", "java", (long) (javaMicros * 1000 * iterations), iterations);
        report("one change, then aggregate", "mirror", (long) (mirrorMicros * 1000 * iterations),
                iterations);
        System.out.printf("  %-30s %-8s %10.2fx%n", "", "speedup", javaMicros / mirrorMicros);
        System.out.println("  " + mirror.profilingSummary(iterations));
        reportDiagnostics("with changes", fixture.mirrorNetwork(), iterations);

        // The same tick, but with the consumer owning its own counter. That shape pays one copy of the
        // aggregate, which the mirror cannot remove for it.
        var ownedOut = new KeyCounter();
        mirror.resetProfiling();
        var ownedMicros = time(i -> {
            mirrorLive.insert(liveKey, 1, Actionable.MODULATE, src);
            mirrorLive.extract(liveKey, 1, Actionable.MODULATE, src);
            mirrorLive.insert(liveKey, 1, Actionable.MODULATE, src);
            fixture.mirrorNetwork().getAvailableStacks(ownedOut);
        }, iterations);
        report("one change, caller-owned counter", "mirror", (long) (ownedMicros * 1000 * iterations),
                iterations);
        System.out.println("  " + mirror.profilingSummary(iterations));
    }

    /**
     * Entry-wise comparison. {@code Map.equals} cannot be used: the maps are identity maps and the values are boxed
     * longs.
     */
    private static void assertSameContents(KeyCounter actual, KeyCounter expected) {
        assertThat(actual.size()).isEqualTo(expected.size());
        for (var entry : expected) {
            assertThat(actual.get(entry.getKey()))
                    .as("amount for %s", entry.getKey())
                    .isEqualTo(entry.getLongValue());
        }
    }
}
