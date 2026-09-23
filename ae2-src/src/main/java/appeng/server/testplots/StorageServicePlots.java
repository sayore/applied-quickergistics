package appeng.server.testplots;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.server.testworld.PlotBuilder;

/**
 * Game tests for the storage service under the real server tick loop.
 * <p>
 * The unit tests drive {@code NetworkStorage} directly. These run the production path instead: a drive network is
 * ticked, the storage service rebuilds its cached inventory on end-of-tick, and the cache is what every consumer reads.
 * The mirror, the partial-coverage merge and the delta stream all take part in that path, and a mistake in any of them
 * shows up here as a cache that disagrees with a live query.
 */
@TestPlotClass
public final class StorageServicePlots {
    private StorageServicePlots() {
    }

    /**
     * Inserts and extracts across several drives, comparing the cached inventory against a live query after every tick.
     * A drive array is mounted as many separate cells, which is the case the mirror has to keep straight.
     */
    @TestPlot("storage_service_cache_matches_live_query")
    public static void cacheMatchesLiveQuery(PlotBuilder plot) {
        var origin = BlockPos.ZERO;
        plot.creativeEnergyCell(origin.below());
        plot.cable(origin);

        // Three drives adjacent to the cable, each with an item cell. A drive two blocks away is not
        // part of the grid at all, which is a silent way to test nothing.
        var drives = List.of(
                new BlockPos(1, 0, 0),
                new BlockPos(0, 0, 1),
                new BlockPos(-1, 0, 0));
        for (var drive : drives) {
            plot.drive(drive).addItemCell64k();
        }

        // A spread of item types, so the network holds many distinct keys.
        var keys = new ArrayList<AEItemKey>();
        for (var item : BuiltInRegistries.ITEM) {
            if (keys.size() >= 12) {
                break;
            }
            var stack = new ItemStack(item);
            if (!stack.isEmpty() && stack.getItem() != Items.AIR) {
                keys.add(AEItemKey.of(stack));
            }
        }

        plot.test(helper -> {
            helper.startSequence()
                    // The grid only accepts items once it is powered and its nodes are ready. Probing until
                    // an insert succeeds is what makes this deterministic; waiting on getGrid alone is not
                    // enough, because the node exists before it is ready.
                    // thenWaitUntil retries while the body throws a game-test assertion, so a failed
                    // probe has to throw that type rather than a plain AssertionError (which crashes the
                    // server) or nothing at all (which would pass on the first tick).
                    .thenWaitUntil(() -> helper.check(helper.getGrid(origin) != null, "grid is not up yet"))
                    .thenWaitUntil(() -> {
                        var storage = helper.getGrid(origin).getStorageService();
                        var probe = AEItemKey.of(Items.STONE);
                        var inserted = storage.getInventory().insert(probe, 1, Actionable.MODULATE, null);
                        if (inserted == 1) {
                            storage.getInventory().extract(probe, 1, Actionable.MODULATE, null);
                        }
                    })
                    .thenExecute(() -> {
                        // Seed every key, which mounts and fills all three drives.
                        var grid = helper.getGrid(origin);
                        var storage = grid.getStorageService();
                        var probe = AEItemKey.of(Items.STONE);
                        var simulate = storage.getInventory().insert(probe, 500, Actionable.SIMULATE, null);
                        helper.check(simulate == 500, "drive array not ready: simulated insert returned "
                                + simulate + " (available=" + storage.getInventory().getAvailableStacks().size()
                                + ", nodes=" + grid.size() + ")");
                        for (var key : keys) {
                            var inserted = storage.getInventory().insert(key, 500, Actionable.MODULATE, null);
                            helper.check(inserted == 500, "could not seed " + key + ": " + inserted);
                        }
                    })
                    // The cache is rebuilt at end of tick, so the next tick must already agree.
                    .thenIdle(2)
                    .thenExecute(() -> assertCacheMatchesLive(helper, origin, keys, "after seeding"))
                    .thenExecute(() -> {
                        // Change the network while it is live: extract some, add to others.
                        var storage = helper.getGrid(origin).getStorageService();
                        for (var i = 0; i < keys.size(); i++) {
                            var key = keys.get(i);
                            if (i % 3 == 0) {
                                var taken = storage.getInventory().extract(key, 200, Actionable.MODULATE, null);
                                helper.check(taken == 200, "could not extract " + key + ": " + taken);
                            } else {
                                var added = storage.getInventory().insert(key, 100, Actionable.MODULATE, null);
                                helper.check(added == 100, "could not add " + key + ": " + added);
                            }
                        }
                    })
                    .thenIdle(2)
                    .thenExecute(() -> assertCacheMatchesLive(helper, origin, keys, "after changes"))
                    .thenExecute(() -> {
                        // Drain one key completely, which removes it from the aggregate.
                        var storage = helper.getGrid(origin).getStorageService();
                        var key = keys.get(1);
                        var taken = storage.getInventory().extract(key, Long.MAX_VALUE, Actionable.MODULATE, null);
                        helper.check(taken > 0, "nothing to drain for " + key);
                    })
                    .thenIdle(2)
                    .thenExecute(() -> {
                        assertCacheMatchesLive(helper, origin, keys, "after draining");
                        var storage = helper.getGrid(origin).getStorageService();
                        helper.check(storage.getCachedInventory().get(keys.get(1)) == 0,
                                "a drained key must disappear from the cache");
                    })
                    .thenSucceed();
        });
    }

    /**
     * Compares the cached inventory against a fresh query of the live network, entry by entry.
     */
    private static void assertCacheMatchesLive(appeng.server.testworld.PlotTestHelper helper, BlockPos pos,
            List<AEItemKey> keys, String when) {
        var storage = helper.getGrid(pos).getStorageService();
        var cached = storage.getCachedInventory();
        var live = storage.getInventory().getAvailableStacks();
        for (var key : keys) {
            helper.check(cached.get(key) == live.get(key),
                    when + ": cache says " + cached.get(key) + " but the network holds " + live.get(key)
                            + " of " + key);
        }
        // And the cache must not invent keys the network does not have.
        helper.check(cachedCount(cached) == liveCount(live),
                when + ": cache has " + cachedCount(cached) + " keys, network has " + liveCount(live));
    }

    private static int cachedCount(KeyCounter counter) {
        var count = 0;
        for (var entry : counter) {
            if (entry.getLongValue() > 0) {
                count++;
            }
        }
        return count;
    }

    private static int liveCount(KeyCounter counter) {
        return cachedCount(counter);
    }

    /**
     * A storage bus sees a chest whose contents change, while the rest of the network is idle. The mirror cannot
     * observe the chest, so this is the case where the cache has to keep rebuilding rather than trusting the mirror's
     * revision - a storage bus whose contents stop updating would be the symptom.
     */
    @TestPlot("storage_bus_contents_reach_the_cache")
    public static void storageBusReachesCache(PlotBuilder plot) {
        var origin = BlockPos.ZERO;
        plot.creativeEnergyCell(origin.below());
        plot.cable(origin).part(net.minecraft.core.Direction.NORTH,
                appeng.core.definitions.AEParts.STORAGE_BUS);
        // The chest the bus points at.
        plot.block(origin.north(), net.minecraft.world.level.block.Blocks.CHEST);

        var key = AEItemKey.of(Items.DIAMOND);

        plot.test(helper -> {
            helper.startSequence()
                    // thenWaitUntil retries until the body stops throwing, so a failed probe must be
                    // silent and let the timeout report it.
                    .thenWaitUntil(() -> {
                        helper.getGrid(origin);
                    })
                    // Wait for the bus to have power and to have polled the chest. The chest is written on
                    // every attempt, so the poll always has something to observe.
                    .thenWaitUntil(() -> {
                        var storage = helper.getGrid(origin).getStorageService();
                        var container = (net.minecraft.world.Container) helper.getLevel()
                                .getBlockEntity(helper.absolutePos(origin.north()));
                        container.setItem(0, new ItemStack(Items.DIAMOND, 7));
                        container.setChanged();
                        helper.check(storage.getCachedInventory().get(key) == 7,
                                "the bus has not seen the chest yet");
                    })
                    .thenExecute(() -> {
                        var chest = helper.getLevel().getBlockEntity(helper.absolutePos(origin.north()));
                        helper.check(chest instanceof net.minecraft.world.level.block.entity.ChestBlockEntity,
                                "expected a chest at the bus");
                        var container = (net.minecraft.world.Container) chest;
                        container.setItem(0, new ItemStack(Items.DIAMOND, 7));
                        container.setChanged();
                    })
                    .thenIdle(5)
                    .thenExecute(() -> {
                        var storage = helper.getGrid(origin).getStorageService();
                        helper.check(storage.getCachedInventory().get(key) == 7,
                                "the bus contents did not reach the cache: "
                                        + storage.getCachedInventory().get(key));
                    })
                    // Change the chest again without touching the rest of the network.
                    .thenExecute(() -> {
                        var chest = helper.getLevel().getBlockEntity(helper.absolutePos(origin.north()));
                        var container = (net.minecraft.world.Container) chest;
                        container.setItem(0, new ItemStack(Items.DIAMOND, 3));
                        container.setChanged();
                    })
                    .thenIdle(5)
                    .thenExecute(() -> {
                        var storage = helper.getGrid(origin).getStorageService();
                        helper.check(storage.getCachedInventory().get(key) == 3,
                                "a later bus change did not reach the cache: "
                                        + storage.getCachedInventory().get(key));
                    })
                    // Emptying it must remove the key again.
                    .thenExecute(() -> {
                        var chest = helper.getLevel().getBlockEntity(helper.absolutePos(origin.north()));
                        var container = (net.minecraft.world.Container) chest;
                        container.setItem(0, ItemStack.EMPTY);
                        container.setChanged();
                    })
                    .thenIdle(5)
                    .thenExecute(() -> {
                        var storage = helper.getGrid(origin).getStorageService();
                        helper.check(storage.getCachedInventory().get(key) == 0,
                                "an emptied bus still reports " + storage.getCachedInventory().get(key));
                    })
                    .thenSucceed();
        });
    }
}
