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

    static {
        System.setProperty(RustStorageIndex.INCREMENTAL_PROPERTY, "false");
    }

    /**
     * The delta stream has to replay to exactly the aggregate a full refresh produces.
     * <p>
     * {@code StorageService} now takes the delta path by default, and it patches the mirror's own maintained counter in
     * place rather than walking the stored types. A drift between the two would therefore not show up as a wrong delta
     * - it would silently corrupt the counter that every terminal, monitor and autocrafting decision reads. This drives
     * real cells through random mutations and compares the replayed counter against a fresh full refresh after every
     * round.
     */
    @Test
    void deltaStreamReplaysToTheSameAggregateAsAFullRefresh() {
        var cell = driveCell(List.of(new ItemStack(Items.STONE, 100), new ItemStack(Items.DIRT, 50)));
        var second = driveCell(List.of(new ItemStack(Items.STONE, 7), new ItemStack(Items.GOLD_INGOT, 3)));
        var network = new NetworkStorage();
        network.mount(0, cell);
        network.mount(3, second);

        var mirror = mirrorOf(network);
        assertThat(mirror).as("the mirror must be enabled for this test").isNotNull();

        var inventory = (BasicCellInventory) ((appeng.api.storage.cells.StorageCell) cell.getDelegate());
        var secondInventory = (BasicCellInventory) ((appeng.api.storage.cells.StorageCell) second.getDelegate());
        var keys = List.of(
                AEItemKey.of(new ItemStack(Items.STONE)),
                AEItemKey.of(new ItemStack(Items.DIRT)),
                AEItemKey.of(new ItemStack(Items.GOLD_INGOT)),
                AEItemKey.of(new ItemStack(Items.EMERALD)));
        var rnd = new java.util.Random(9876);

        // The consumer's own copy of the aggregate, independent of the mirror's live counter. The
        // mirror patches that counter in place when deltas are applied, so a test that replayed onto
        // it would be comparing the mirror against itself.
        var replayed = new KeyCounter();
        network.getAvailableStacks(replayed);
        var revision = mirror.revision();

        for (var round = 0; round < 400; round++) {
            var target = rnd.nextBoolean() ? inventory : secondInventory;
            var key = keys.get(rnd.nextInt(keys.size()));
            var amount = rnd.nextInt(40);
            if (rnd.nextBoolean()) {
                target.insert(key, amount, Actionable.MODULATE, src);
            } else {
                target.extract(key, amount, Actionable.MODULATE, src);
            }

            var live = new KeyCounter();
            network.getAvailableStacks(live);
            assertSameContents(snapshot(live), javaAggregate(List.of(cell, second)));
            var deltas = network.deltasSince(revision);
            if (deltas == null) {
                // The mirror is allowed to refuse (a mount changed, or the log moved past this
                // revision). A consumer then rebuilds, exactly as it does in production. The replay
                // path is what is under test, so this is still a valid round.
                replayed.clear();
                network.getAvailableStacks(replayed);
                revision = mirror.revision();
            } else {
                for (var change : deltas.changes()) {
                    if (change.newTotal() == 0) {
                        replayed.remove(change.key());
                    } else {
                        replayed.set(change.key(), change.newTotal());
                    }
                }
                revision = deltas.revision();
            }

            // Compare against a fresh full refresh: the replayed copy must have landed on exactly the
            // aggregate a consumer that fell behind would read.
            var expectedNow = javaAggregate(List.of(cell, second));
            try {
                assertSameContents(snapshot(replayed), expectedNow);
            } catch (AssertionError e) {
                throw new AssertionError("round " + round + " (key " + key + ", amount " + amount + "): "
                        + e.getMessage() + " replayed=" + snapshot(replayed) + " fresh=" + expectedNow, e);
            }
        }
    }

    /**
     * The single-key and multi-key amount lookups must agree with the full aggregate, including after a mutation that
     * happened behind the network's back. They are the shape a terminal search or a "can I craft this?" check uses.
     */
    @Test
    void perKeyAmountLookupsMatchTheAggregate() {
        var cell = driveCell(List.of(new ItemStack(Items.STONE, 100), new ItemStack(Items.DIRT, 50)));
        var second = driveCell(List.of(new ItemStack(Items.STONE, 7)));
        var network = new NetworkStorage();
        network.mount(0, cell);
        network.mount(3, second);

        var mirror = mirrorOf(network);
        assertThat(mirror).as("the mirror must be enabled for this test").isNotNull();

        var stone = AEItemKey.of(new ItemStack(Items.STONE));
        var dirt = AEItemKey.of(new ItemStack(Items.DIRT));
        var emerald = AEItemKey.of(new ItemStack(Items.EMERALD));

        assertThat(mirror.amountOf(stone)).isEqualTo(107);
        assertThat(mirror.amountOf(dirt)).isEqualTo(50);
        assertThat(mirror.amountOf(emerald)).as("a key the network never held").isZero();
        assertThat(mirror.amountsOf(new AEKey[] { stone, emerald, dirt }))
                .containsExactly(107, 0, 50);

        // The lookup has to see a mutation that happened behind the network's back, not a cached total.
        var inventory = (BasicCellInventory) ((appeng.api.storage.cells.StorageCell) cell.getDelegate());
        inventory.insert(stone, 900, Actionable.MODULATE, src);
        inventory.extract(dirt, 20, Actionable.MODULATE, src);

        assertThat(mirror.amountOf(stone)).as("mirror must mirror the mount before answering").isEqualTo(1007);
        assertThat(mirror.amountOf(dirt)).isEqualTo(30);
        assertThat(mirror.amountsOf(new AEKey[] { stone, dirt })).containsExactly(1007, 30);
        assertThat(mirror.amountsOf(new AEKey[0])).isEmpty();
    }

    /**
     * The filtered query takes native key ids, so its contract is that ids come from
     * {@link RustStorageIndex#interner()}. It must agree with the full aggregate for the keys it is asked about.
     */
    @Test
    void filteredQueryMatchesTheAggregate() {
        var cell = driveCell(List.of(new ItemStack(Items.STONE, 100), new ItemStack(Items.DIRT, 50)));
        var network = new NetworkStorage();
        network.mount(0, cell);
        var mirror = mirrorOf(network);
        assertThat(mirror).isNotNull();

        var stone = AEItemKey.of(new ItemStack(Items.STONE));
        var dirt = AEItemKey.of(new ItemStack(Items.DIRT));
        // Intern both keys first, as a caller would have to.
        assertThat(mirror.amountsOf(new AEKey[] { stone, dirt })).containsExactly(100, 50);

        var stoneId = mirror.interner().idOf(stone);
        var dirtId = mirror.interner().idOf(dirt);
        assertThat(stoneId).isNotNegative();
        assertThat(dirtId).isNotNegative();

        var filtered = mirror.getAvailableStacks(new int[] { stoneId });
        assertThat(filtered).isNotNull();
        assertThat(filtered.get(stone)).isEqualTo(100);
        assertThat(filtered.get(dirt)).as("a key outside the filter").isZero();
    }

    /**
     * The write path is pure Java - the mirror serves only the aggregate - but it is what players hit when they pull
     * items out, and its priority order is load-bearing: a lower-priority cell drains first, and extraction must stop
     * as soon as the request is satisfied.
     */
    @Test
    void extractionDrainsCellsInAscendingPriorityOrder() {
        var low = driveCell(List.of(new ItemStack(Items.STONE, 3)));
        var middle = driveCell(List.of(new ItemStack(Items.STONE, 4)));
        var high = driveCell(List.of(new ItemStack(Items.STONE, 5)));
        var network = new NetworkStorage();
        // Mounted out of order on purpose, so the priority map has to sort rather than preserve it.
        network.mount(5, high);
        network.mount(-1, low);
        network.mount(2, middle);

        var stone = AEItemKey.of(new ItemStack(Items.STONE));
        var lowInv = (BasicCellInventory) ((appeng.api.storage.cells.StorageCell) low.getDelegate());
        var middleInv = (BasicCellInventory) ((appeng.api.storage.cells.StorageCell) middle.getDelegate());
        var highInv = (BasicCellInventory) ((appeng.api.storage.cells.StorageCell) high.getDelegate());

        // A simulate must report what is available without touching anything.
        assertThat(network.extract(stone, 100, Actionable.SIMULATE, src)).isEqualTo(12);
        assertThat(lowInv.getAvailableStacks().get(stone)).isEqualTo(3);
        assertThat(highInv.getAvailableStacks().get(stone)).isEqualTo(5);

        // Draining one item takes it from the lowest priority cell.
        assertThat(network.extract(stone, 1, Actionable.MODULATE, src)).isEqualTo(1);
        assertThat(lowInv.getAvailableStacks().get(stone)).isEqualTo(2);
        assertThat(middleInv.getAvailableStacks().get(stone)).isEqualTo(4);

        // Draining past the first cell continues into the next one, and stops at the request. The low
        // cell has 2 left, so 2 come from it and 2 from the next cell in priority order.
        assertThat(network.extract(stone, 4, Actionable.MODULATE, src)).isEqualTo(4);
        assertThat(lowInv.getAvailableStacks().get(stone)).as("the low cell drains first").isZero();
        assertThat(middleInv.getAvailableStacks().get(stone)).isEqualTo(2);
        assertThat(highInv.getAvailableStacks().get(stone)).as("the highest priority is untouched").isEqualTo(5);

        // Asking for more than exists takes everything and reports what it took.
        assertThat(network.extract(stone, 1_000, Actionable.MODULATE, src)).isEqualTo(7);
        assertThat(lowInv.getAvailableStacks().get(stone)).isZero();
        assertThat(middleInv.getAvailableStacks().get(stone)).isZero();
        assertThat(highInv.getAvailableStacks().get(stone)).isZero();

        // Nothing left to take.
        assertThat(network.extract(stone, 1, Actionable.MODULATE, src)).isZero();
    }

    /**
     * A larger network than the other tests build, mutated every round, with the mirror compared against Java each
     * time. This is the shape of a base that is actually being used: many cells, many distinct keys, several mutated
     * per tick, and the delta stream patching the counter in between.
     */
    @Test
    void manyCellsStayCorrectWhileSeveralChangeEveryRound() {
        var pool = new ArrayList<List<ItemStack>>();
        for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            var stack = new ItemStack(item);
            if (!stack.isEmpty()) {
                pool.add(List.of(stack));
            }
            if (pool.size() >= 1600) {
                break;
            }
        }
        assertThat(pool.size()).as("the registry must offer enough item types").isGreaterThan(1000);

        var rnd = new java.util.Random(0xBADC0DE);
        // Four keys per cell, so a cell still has room to grow during the run.
        var cellCount = 30;
        var inventories = new ArrayList<BasicCellInventory>();
        var mounts = new ArrayList<MEStorage>();
        var network = new NetworkStorage();
        for (var c = 0; c < cellCount; c++) {
            var stacks = new ArrayList<ItemStack>();
            for (var k = 0; k < 4; k++) {
                stacks.add(pool.get(rnd.nextInt(pool.size())).get(0).copyWithCount(20 + rnd.nextInt(40)));
            }
            var mount = driveCell(stacks);
            mounts.add(mount);
            inventories.add((BasicCellInventory) ((appeng.api.storage.cells.StorageCell) mount.getDelegate()));
            network.mount(rnd.nextInt(7) - 3, mount);
        }

        var revision = network.revision();
        var replayed = new KeyCounter();
        network.getAvailableStacks(replayed);

        for (var round = 0; round < 300; round++) {
            // Several cells change per round, which is what a busy network looks like.
            for (var mutation = 0; mutation < 3; mutation++) {
                var inventory = inventories.get(rnd.nextInt(inventories.size()));
                var key = AEItemKey.of(pool.get(rnd.nextInt(pool.size())).get(0));
                var amount = rnd.nextInt(15);
                if (rnd.nextBoolean()) {
                    inventory.insert(key, amount, Actionable.MODULATE, src);
                } else {
                    inventory.extract(key, amount, Actionable.MODULATE, src);
                }
            }

            assertMatchesReference(network, mounts, round);

            var deltas = network.deltasSince(revision);
            if (deltas == null) {
                replayed.clear();
                network.getAvailableStacks(replayed);
                revision = network.revision();
            } else {
                for (var change : deltas.changes()) {
                    if (change.newTotal() == 0) {
                        replayed.remove(change.key());
                    } else {
                        replayed.set(change.key(), change.newTotal());
                    }
                }
                revision = deltas.revision();
            }
            var fresh = new KeyCounter();
            network.getAvailableStacks(fresh);
            assertSameContents(snapshot(replayed), snapshot(fresh));
        }
    }

    /** Compares the network's aggregate against a plain Java merge of the same mounts. */
    private static void assertMatchesReference(NetworkStorage network, List<MEStorage> mounts, int round) {
        var actual = new KeyCounter();
        network.getAvailableStacks(actual);
        var expected = javaAggregate(mounts);
        try {
            assertSameContents(snapshot(actual), expected);
        } catch (AssertionError e) {
            throw new AssertionError("round " + round + ": " + e.getMessage(), e);
        }
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
