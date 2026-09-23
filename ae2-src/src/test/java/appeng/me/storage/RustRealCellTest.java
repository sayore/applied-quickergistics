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
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageCells;
import appeng.core.definitions.AEItems;
import appeng.me.cells.BasicCellInventory;
import appeng.me.helpers.BaseActionSource;
import appeng.util.BootstrapMinecraft;

/**
 * Exercises the native storage mirror against real AE2 storage cells instead of test doubles.
 * <p>
 * The mirror's own unit test uses hand-written storages, which cannot catch anything that depends on how AE2's real
 * cells behave: {@link BasicCellInventory} keeps its contents in the cell's {@link ItemStack}, {@link DriveWatcher}
 * wraps it, and {@link AEItemKey} instances are canonical. It also cannot catch the case where an
 * {@code MEInventoryHandler} wrapper filters the reported contents and the mirror has to refuse the mount.
 */
@BootstrapMinecraft
@ExtendWith(EphemeralTestServerProvider.class)
class RustRealCellTest {
    static {
        // The mirror is opt-in, and RustStorageIndex resolves that flag when its class is first
        // loaded - which the Minecraft bootstrap below can trigger. Set it before any of that runs.
        System.setProperty(RustStorageIndex.ENABLED_PROPERTY, "true");
    }

    private final BaseActionSource src = new BaseActionSource();

    RustRealCellTest(MinecraftServer server) {
    }

    private static AEItemKey key(ItemStack stack) {
        return AEItemKey.of(stack);
    }

    /** Builds a drive cell holding {@code stacks}, wrapped the way a drive mounts it. */
    private static DriveWatcher driveCell(List<ItemStack> stacks) {
        var cell = AEItems.ITEM_CELL_64K.stack();
        var inventory = BasicCellInventory.createInventory(cell, null);
        assertThat(inventory).isNotNull();
        var source = new BaseActionSource();
        for (var stack : stacks) {
            var inserted = inventory.insert(AEItemKey.of(stack), stack.getCount(), Actionable.MODULATE,
                    source);
            assertThat(inserted).as("inserting %s", stack).isEqualTo(stack.getCount());
        }
        return new DriveWatcher(inventory, () -> {
        });
    }

    /**
     * Aggregates a counter's non-zero contents by key.
     * <p>
     * {@code KeyCounter} stores amounts in reference-keyed maps, and {@code AEItemKey} instances are only canonical
     * within one item registry. Comparing two counters by key identity is therefore unreliable in the test environment,
     * which can end up with more than one {@code Item} instance for the same item, so the same logical item
     * legitimately lands in two buckets. Aggregating by key equality compares what the counters actually report: the
     * amounts per item.
     */
    private static Map<AEKey, Long> snapshot(KeyCounter counter) {
        var map = new java.util.LinkedHashMap<AEKey, Long>();
        for (var entry : counter) {
            var amount = entry.getLongValue();
            if (amount != 0) {
                map.merge(entry.getKey(), amount, Long::sum);
            }
        }
        return map;
    }

    /**
     * Compares two aggregates by key equality and primitive amount.
     * <p>
     * Neither {@code Map.equals} nor AssertJ's containment check works here: the keys are identity compared, and the
     * amounts are boxed longs.
     */
    private static void assertSameContents(Map<AEKey, Long> actual, Map<AEKey, Long> expected) {
        assertThat(actual.size()).as("distinct items").isEqualTo(expected.size());
        for (var entry : expected.entrySet()) {
            var actualValue = actual.get(entry.getKey());
            assertThat(actualValue)
                    .as("amount for %s", entry.getKey())
                    .isNotNull();
            assertThat(actualValue.longValue())
                    .as("amount for %s", entry.getKey())
                    .isEqualTo(entry.getValue().longValue());
        }
    }

    /** The aggregate the Java path produces for the given mounts. */
    private static Map<AEKey, Long> javaAggregate(List<MEStorage> mounts) {
        var counter = new KeyCounter();
        for (var mount : mounts) {
            mount.getAvailableStacks(counter);
        }
        return snapshot(counter);
    }

    @Test
    void mirrorMatchesRealCells() {
        var stacks = new ArrayList<ItemStack>();
        stacks.add(new ItemStack(Items.STONE, 1000));
        stacks.add(new ItemStack(Items.DIRT, 500));
        stacks.add(new ItemStack(Items.IRON_INGOT, 250));
        // A stack with a data component, so the key carries a secondary component.
        var named = new ItemStack(Items.DIAMOND_SWORD, 1);
        named.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Excalibur"));
        stacks.add(named);

        var first = driveCell(stacks);
        var secondStacks = new ArrayList<ItemStack>();
        secondStacks.add(new ItemStack(Items.STONE, 7));
        secondStacks.add(new ItemStack(Items.GOLD_INGOT, 42));
        var second = driveCell(secondStacks);

        var network = new NetworkStorage();
        network.mount(0, first);
        network.mount(5, second);

        var expected = javaAggregate(List.of(first, second));
        assertThat(expected).isNotEmpty();

        var actual = new KeyCounter();
        network.getAvailableStacks(actual);
        assertSameContents(snapshot(actual), expected);
    }

    @Test
    void mirrorTracksRealCellMutations() {
        var stacks = new ArrayList<ItemStack>();
        stacks.add(new ItemStack(Items.STONE, 100));
        var cell = driveCell(stacks);

        var network = new NetworkStorage();
        network.mount(0, cell);
        var expected = javaAggregate(List.of(cell));

        // Pull the real inventory back out of the wrapper and mutate it behind the network's back,
        // which is exactly what an IO port or a crafting CPU does.
        var inventory = (BasicCellInventory) ((appeng.api.storage.cells.StorageCell) cell.getDelegate());
        var stone = AEItemKey.of(new ItemStack(Items.STONE));
        inventory.insert(stone, 900, Actionable.MODULATE, src);
        inventory.insert(AEItemKey.of(new ItemStack(Items.EMERALD)), 5, Actionable.MODULATE, src);
        inventory.extract(stone, 50, Actionable.MODULATE, src);

        expected = javaAggregate(List.of(cell));
        var actual = new KeyCounter();
        network.getAvailableStacks(actual);
        assertSameContents(snapshot(actual), expected);

        // Also check that extracting through the network reports the same amount the cell does.
        var extracted = network.extract(stone, 10_000, Actionable.SIMULATE, src);
        assertThat(extracted).isEqualTo(950);
    }

    /**
     * The counter the mirror hands out must describe the same network contents as the Java path.
     * <p>
     * {@code StorageService} adopts this counter instead of copying it, so an error here would show up as a wrong
     * terminal or a wrong autocrafting decision, not as a crash.
     */
    @Test
    void sharedCounterMatchesJavaAggregate() {
        var first = driveCell(List.of(new ItemStack(Items.STONE, 100), new ItemStack(Items.DIRT, 50)));
        var second = driveCell(List.of(new ItemStack(Items.STONE, 7)));
        var network = new NetworkStorage();
        network.mount(0, first);
        network.mount(3, second);

        var mirror = mirrorOf(network);
        assertThat(mirror).as("the mirror must be enabled for this test").isNotNull();

        // First query builds the maintained counter and hands it out.
        var shared = mirror.getSharedAvailableStacks();
        assertThat(shared).isNotNull();
        assertSameContents(snapshot(shared), javaAggregate(List.of(first, second)));

        // Repeated queries must return the same still-correct counter.
        assertThat(mirror.getSharedAvailableStacks()).isSameAs(shared);
        assertSameContents(snapshot(shared), javaAggregate(List.of(first, second)));
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

    /**
     * A single mount the mirror cannot reproduce must be visible, because it currently disables the mirror for the
     * whole network - and without a report that degradation looks like a healthy network that simply runs slower.
     */
    @Test
    void coverageReportsExcludedMounts() {
        var cell = driveCell(List.of(new ItemStack(Items.STONE, 10)));

        var allMirrorable = new NetworkStorage();
        allMirrorable.mount(0, cell);
        var mirror = mirrorOf(allMirrorable);
        assertThat(mirror).isNotNull();
        // Force a sync so the mounts are classified.
        allMirrorable.getAvailableStacks(new KeyCounter());
        assertThat(mirror.describeCoverage()).isEqualTo("mirrored=1, excluded=0");
        assertThat(mirror.getUncoveredMounts()).isEmpty();

        // A filtering handler is the storage-bus shape: the mirror cannot reproduce its filter, so it
        // has to be reported as excluded rather than silently over-reporting.
        var filtering = new appeng.me.storage.MEInventoryHandler(cell) {
        };
        filtering.setExtractFiltering(false, true);
        var withFilter = new NetworkStorage();
        withFilter.mount(0, filtering);
        var filterMirror = mirrorOf(withFilter);
        assertThat(filterMirror).isNotNull();
        withFilter.getAvailableStacks(new KeyCounter());
        assertThat(filterMirror.describeCoverage())
                .contains("excluded=1")
                .contains("FILTERS_CONTENTS");
        assertThat(filterMirror.getUncoveredMounts()).containsExactly(filtering);
    }

    /**
     * The point of partial coverage: a mount the mirror cannot reproduce must cost only itself.
     * <p>
     * Before this, one such mount disabled the mirror for the whole network. The result has to stay exact either way,
     * which is what this checks - a mixed network must read the same as if the mirror were not there at all, both
     * before and after the covered cells change.
     */
    @Test
    void mixedNetworkMatchesJavaAggregate() {
        var coveredCell = driveCell(
                List.of(new ItemStack(Items.STONE, 1000), new ItemStack(Items.DIRT, 400)));
        var secondCovered = driveCell(List.of(new ItemStack(Items.IRON_INGOT, 77)));

        // A storage-bus-shaped mount: it filters what it reports, so the mirror cannot reproduce it.
        var busBacking = driveCell(
                List.of(new ItemStack(Items.STONE, 5), new ItemStack(Items.GOLD_INGOT, 9)));
        var bus = new appeng.me.storage.MEInventoryHandler(busBacking) {
        };
        bus.setExtractFiltering(false, true);

        var network = new NetworkStorage();
        network.mount(0, coveredCell);
        network.mount(1, secondCovered);
        network.mount(2, bus);

        var mirror = mirrorOf(network);
        assertThat(mirror).isNotNull();

        var expected = javaAggregate(List.of(coveredCell, secondCovered, bus));
        var actual = new KeyCounter();
        network.getAvailableStacks(actual);
        assertSameContents(snapshot(actual), expected);

        // And again once a covered cell changes, which exercises the incremental path.
        var cell = (BasicCellInventory) ((appeng.api.storage.cells.StorageCell) coveredCell.getDelegate());
        cell.insert(AEItemKey.of(new ItemStack(Items.EMERALD)), 42, Actionable.MODULATE, src);
        expected = javaAggregate(List.of(coveredCell, secondCovered, bus));
        actual = new KeyCounter();
        network.getAvailableStacks(actual);
        assertSameContents(snapshot(actual), expected);
    }

    @Test
    void storageCellApiWrapsBasicInventory() {
        // Guards the assumption that a 64k item cell is a StorageCell that BasicCellInventory backs,
        // so this test keeps testing real cells if AE2 changes the wiring.
        var cell = AEItems.ITEM_CELL_64K.stack();
        assertThat(StorageCells.getCellInventory(cell, null)).isInstanceOf(BasicCellInventory.class);
    }
}
