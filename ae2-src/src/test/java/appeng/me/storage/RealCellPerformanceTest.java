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
import net.minecraft.world.item.Item;
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
 * The synthetic benchmark in {@code harness/} measures the same operations but against hand-written
 * storages, so it cannot see the cost of AE2's own wrappers
 * ({@link DriveWatcher} and {@link MEInventoryHandler}) or of
 * {@link BasicCellInventory}'s bookkeeping. This test builds the real thing from the item registry and
 * reports timings on stdout.
 * <p>
 * It runs headlessly through {@code @BootstrapMinecraft}, so it needs no GPU and no server boot.
 * <p>
 * The numbers are informational, not assertions: a virtual machine's timings say little about a real
 * server, and a hard threshold here would only make the suite flaky. What <em>is</em> asserted is that
 * both paths return the same aggregate, so a timing run can never silently measure a broken mirror.
 */
@BootstrapMinecraft
@ExtendWith(EphemeralTestServerProvider.class)
class RealCellPerformanceTest {
    /** Real AE2 caps a cell at 63 types; using the cap keeps the drive realistic. */
    private static final int TYPES_PER_CELL = 63;

    private final BaseActionSource src = new BaseActionSource();
    private final MinecraftServer server;

    RealCellPerformanceTest(MinecraftServer server) {
        // The mirror is opt-in; these tests exist to exercise it.
        System.setProperty(RustStorageIndex.ENABLED_PROPERTY, "true");
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
     * The enable flag is resolved once per JVM, so this is the only way to compare both paths in a
     * single process without differences in JIT warmup, world state or machine load polluting the
     * comparison.
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

    @Test
    void measureRealDriveArray() throws Exception {
        // The incremental path is off by default; this test exists to measure it, so enable it here
        // rather than through a Gradle property the test JVM would not see.
        System.setProperty(RustStorageIndex.INCREMENTAL_PROPERTY, "true");
        var keys = collectKeys();

        // Cell counts are bounded by the available item types; use a small and a full drive array.
        var maxCells = keys.size() / TYPES_PER_CELL;
        for (var cellCount : new int[] { Math.max(1, maxCells / 2), maxCells }) {
            var mounts = new ArrayList<MEStorage>();
            var network = new NetworkStorage();
            for (var c = 0; c < cellCount; c++) {
                var from = c * TYPES_PER_CELL;
                var to = Math.min(from + TYPES_PER_CELL, keys.size());
                if (from >= to) {
                    break;
                }
                var cell = buildCell(keys, from, to);
                mounts.add(cell);
                network.mount(c % 5, cell);
            }

            var storedTypes = 0;
            for (var i = 0; i < mounts.size() * TYPES_PER_CELL && i < keys.size(); i++) {
                storedTypes++;
            }

            System.out.printf("%n=== real drive array: %d cells, %d stored types, mirror %s ===%n",
                    mounts.size(), storedTypes,
                    RustStorageIndex.isEnabled() ? "ENABLED" : "disabled");

            // Build a second, identical network that runs the original Java path so both can be
            // timed under the same conditions.
            var javaMounts = new ArrayList<MEStorage>();
            var javaNetwork = new NetworkStorage();
            for (var c = 0; c < mounts.size(); c++) {
                var from = c * TYPES_PER_CELL;
                var to = Math.min(from + TYPES_PER_CELL, keys.size());
                var cell = buildCell(keys, from, to);
                javaMounts.add(cell);
                javaNetwork.mount(c % 5, cell);
            }
            withoutMirror(javaNetwork);

            // Both paths must agree before anything is timed.
            var expected = javaAggregate(mounts);
            var reference = expected;
            var actual = new KeyCounter();
            network.getAvailableStacks(actual);
            assertThat(actual.size()).as("aggregate size").isEqualTo(expected.size());

            var iterations = 400;
            // Mirror what AE2 actually does per tick: clear the one counter, fill it once. No
            // mutation happens between the measurements, and the cell inventories are already
            // loaded, so this isolates the aggregate cost rather than cell initialisation.
            var javaOut = new KeyCounter();
            var javaMicros = time(i -> {
                javaOut.clear();
                javaNetwork.getAvailableStacks(javaOut);
                if (javaOut.isEmpty()) {
                    throw new IllegalStateException();
                }
            }, iterations);
            var mirrorOut = new KeyCounter();
            var mirrorMicros = time(i -> {
                mirrorOut.clear();
                network.getAvailableStacks(mirrorOut);
                if (mirrorOut.isEmpty()) {
                    throw new IllegalStateException();
                }
            }, iterations);

            report("full aggregate (every tick)", "java", (long) (javaMicros * 1000 * iterations),
                    iterations);
            report("full aggregate (every tick)", "mirror", (long) (mirrorMicros * 1000 * iterations),
                    iterations);
            System.out.printf("  %-34s %-8s %10.2fx%n", "", "speedup", javaMicros / mirrorMicros);

            // Compare handling out the maintained counter directly against copying it into a
            // caller-owned one, so the cost of the copy is visible.
            var sharedField = NetworkStorage.class.getDeclaredField("nativeIndex");
            sharedField.setAccessible(true);
            var sharedMirror = (RustStorageIndex) sharedField.get(network);
            if (sharedMirror != null) {
                var sharedMicros = time(i -> {
                    var shared = sharedMirror.getSharedAvailableStacks();
                    if (shared == null || shared.isEmpty()) {
                        throw new IllegalStateException();
                    }
                }, iterations);
                report("shared counter, no copy", "mirror",
                        (long) (sharedMicros * 1000 * iterations), iterations);
            }

            // Extract fan-out on the same network.
            var presentKey = keys.get(0);
            var absentKey = keys.get(keys.size() - 1);
            assertThat(reference.get(presentKey)).isPositive();
            assertThat(reference.get(absentKey)).isZero();

            var simIterations = 4000;
            var presentMicros = time(i -> network.extract(presentKey, 500, Actionable.SIMULATE, src),
                    simIterations);
            var absentMicros = time(i -> network.extract(absentKey, 500, Actionable.SIMULATE, src),
                    simIterations);
            report("extract SIMULATE, key present", "network",
                    (long) (presentMicros * 1000 * simIterations), simIterations);
            report("extract SIMULATE, key absent", "network",
                    (long) (absentMicros * 1000 * simIterations), simIterations);

            System.out.println();
            assertSameContents(actual, expected);
        }
    }

    private static void assertSameContents(KeyCounter actual, KeyCounter expected) {
        assertThat(actual.size()).isEqualTo(expected.size());
        for (var entry : expected) {
            assertThat(actual.get(entry.getKey()))
                    .as("amount for %s", entry.getKey())
                    .isEqualTo(entry.getLongValue());
        }
    }
}
