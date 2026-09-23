/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
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

package appeng.me.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.math.StatsAccumulator;
import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueOutput;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridServiceProvider;
import appeng.api.networking.storage.IStorageService;
import appeng.api.networking.storage.IStorageWatcherNode;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.IStorageMounts;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.me.helpers.InterestManager;
import appeng.me.helpers.StackWatcher;
import appeng.me.storage.NetworkStorage;
import appeng.me.storage.RustStorageIndex;
import appeng.util.JsonStreamUtil;

public class StorageService implements IStorageService, IGridServiceProvider {
    private static final Gson GSON = new Gson();

    /**
     * Tracks the storage service's state for each grid node that provides storage to the network.
     */
    private final Map<IGridNode, ProviderState> nodeProviders = new IdentityHashMap<>();
    /**
     * Tracks state for storage providers that are provided by other grid services (i.e. crafting).
     */
    private final List<ProviderState> globalProviders = new ArrayList<>();
    private final SetMultimap<AEKey, StackWatcher<IStorageWatcherNode>> interests = HashMultimap.create();
    private final InterestManager<StackWatcher<IStorageWatcherNode>> interestManager = new InterestManager<>(
            this.interests);
    private final NetworkStorage storage;
    /**
     * Publicly exposed cached available stacks.
     */
    /**
     * The network's available stacks, replaced wholesale when the native mirror can hand out its maintained counter
     * (see the fast path in {@link #updateCachedStacks()}).
     */
    private KeyCounter cachedAvailableStacks = new KeyCounter();
    /**
     * Private cached amounts, to ensure that we send correct change notifications even if
     * {@link #cachedAvailableStacks} is modified by mistake.
     */
    private final Object2LongMap<AEKey> cachedAvailableAmounts = new Object2LongOpenHashMap<>();
    private boolean cachedStacksNeedUpdate = true;
    /**
     * Native revision the cached inventory corresponds to, or {@code -1} when the incremental path cannot be used (no
     * native mirror, or the cache has never been built).
     */
    private long cachedRevision = -1;
    /**
     * The mounts the mirror could not reproduce, read into a counter owned by this service.
     * <p>
     * {@code null} means the cache is purely the mirror's counter and may be handed out directly. Non-null marks that
     * the exposed cache is a merge, which also disables the incremental path, because that path only knows about the
     * mirrored mounts.
     */
    @Nullable
    private KeyCounter uncoveredStacks;
    /**
     * Tracks the stack watcher associated with a given grid node. Needed to clean up watchers when the node leaves the
     * grid.
     */
    private final Map<IGridNode, StackWatcher<IStorageWatcherNode>> watchers = new IdentityHashMap<>();

    private final StatsAccumulator inventoryRefreshStats = new StatsAccumulator();

    public StorageService() {
        this.storage = new NetworkStorage();
    }

    @Override
    public void onServerEndTick() {
        if (interestManager.isEmpty()) {
            // lazily rebuild cache list
            cachedStacksNeedUpdate = true;
        } else {
            // we need to rebuild the cache every tick to notify listeners
            updateCachedStacks();
        }
    }

    private void updateCachedStacks() {
        var time = System.nanoTime();

        try {
            cachedStacksNeedUpdate = false;

            // Fast path: when the native mirror can report exactly what changed since the last
            // refresh, only the changed keys have to be touched instead of every key in the network.
            // That is the difference between work proportional to the size of the network and work
            // proportional to what actually happened during this tick.
            if (cachedRevision >= 0 && uncoveredStacks == null && RustStorageIndex.isIncrementalRefreshEnabled()) {
                var deltas = storage.deltasSince(cachedRevision);
                if (deltas != null) {
                    applyDeltas(deltas);
                    return;
                }
            }

            // Nothing can have changed while the mirror's revision is unchanged, and the mirror
            // reports out-of-band mutations through that revision (an IO port filling a cell bumps
            // the cell's version). Without this, an idle network still walked every stored type here
            // to compare it against the previous amounts - 1827 map lookups per tick in the benchmark,
            // and it grows with the network, which is exactly when an idle tick should cost nothing.
            var revisionNow = storage.revision();
            if (revisionNow >= 0 && revisionNow == cachedRevision && uncoveredStacks == null) {
                return;
            }

            // Fast path: the mirror maintains the aggregate itself, so take its counter instead of
            // copying every entry into this one. The copy is what made the mirror slower than plain
            // Java (measured at ~187 us for 1827 stored types), while handing the counter over costs
            // under a microsecond.
            var shared = storage.getSharedAvailableStacks();
            var uncovered = shared != null ? storage.getUncoveredMounts() : Collections.<MEStorage>emptySet();
            if (shared != null && uncovered.isEmpty()) {
                cachedAvailableStacks = shared;
                uncoveredStacks = null;
            } else {
                // The result this service exposes has to include the mounts the mirror cannot
                // reproduce (a storage bus, a filtered handler). They are read into a counter of
                // their own and merged into one this service owns: the mirror's counter is shared with
                // the mirror and must not be written to, and accumulating into it would add the
                // uncovered mounts again on every query.
                if (uncoveredStacks == null) {
                    uncoveredStacks = new KeyCounter();
                } else {
                    uncoveredStacks.clear();
                }
                if (shared == null) {
                    // No mirror at all. `clear` only empties the inner maps, so the outer map has to
                    // be cleaned up explicitly.
                    cachedAvailableStacks.clear();
                    storage.getAvailableStacks(cachedAvailableStacks);
                    cachedAvailableStacks.removeEmptySubmaps();
                } else {
                    storage.gatherUncovered(uncoveredStacks, uncovered);
                    // The cache is rebuilt from scratch: the mirrored amounts are written absolutely
                    // and the uncovered amounts are accumulated on top, so a key that is in neither
                    // anymore has to go rather than linger at its previous amount.
                    cachedAvailableStacks.clear();
                    // `clear` only empties the inner maps, so the outer map has to be cleaned up too.
                    for (var entry : shared) {
                        cachedAvailableStacks.set(entry.getKey(), entry.getLongValue());
                    }
                    for (var entry : uncoveredStacks) {
                        // Keys that are only in an uncovered mount have nothing to add to.
                        cachedAvailableStacks.add(entry.getKey(), entry.getLongValue());
                    }
                    cachedAvailableStacks.removeEmptySubmaps();
                }
            }

            // Post watcher update for currently available stacks
            for (var entry : cachedAvailableStacks) {
                var what = entry.getKey();
                var newAmount = entry.getLongValue();
                if (newAmount != cachedAvailableAmounts.getLong(what)) {
                    postWatcherUpdate(what, newAmount);
                }
            }
            // Post watcher update for removed stacks
            for (var what : cachedAvailableAmounts.keySet()) {
                var newAmount = cachedAvailableStacks.get(what);
                if (newAmount == 0) {
                    postWatcherUpdate(what, newAmount);
                }
            }

            // Update private amounts
            cachedAvailableAmounts.clear();
            for (var entry : cachedAvailableStacks) {
                cachedAvailableAmounts.put(entry.getKey(), entry.getLongValue());
            }

            // Remember which revision this cache reflects, so the next tick can go incremental. A
            // cache that also contains uncovered mounts is not purely mirrored, so it is rebuilt on
            // every tick instead.
            cachedRevision = uncoveredStacks == null ? storage.revision() : -1;
        } finally {
            inventoryRefreshStats.add(System.nanoTime() - time);
        }
    }

    /**
     * Applies a replayable run of network-total changes to the cached inventory and notifies the watchers whose value
     * changed.
     * <p>
     * The observable result is identical to a full rebuild: a watcher is notified when its key's total changes, and a
     * key that drops to zero is removed from the cache rather than left behind as a zero entry, which would leak when
     * keys churn.
     */
    private void applyDeltas(RustStorageIndex.ChangeSet deltas) {
        for (var change : deltas.changes()) {
            var what = change.key();
            var oldAmount = change.oldTotal();
            var newAmount = change.newTotal();

            if (newAmount == 0) {
                cachedAvailableStacks.remove(what);
                cachedAvailableAmounts.removeLong(what);
            } else {
                cachedAvailableStacks.set(what, newAmount);
                cachedAvailableAmounts.put(what, newAmount);
            }

            if (oldAmount != newAmount) {
                postWatcherUpdate(what, newAmount);
            }
        }
        cachedRevision = deltas.revision();
    }

    private void postWatcherUpdate(AEKey what, long newAmount) {
        for (var watcher : interestManager.get(what)) {
            watcher.getHost().onStackChange(what, newAmount);
        }
        for (var watcher : interestManager.getAllStacksWatchers()) {
            watcher.getHost().onStackChange(what, newAmount);
        }
    }

    /**
     * When a node joins the grid, we automatically register provided {@link IStorageProvider} and
     * {@link IStorageWatcherNode}.
     */
    @Override
    public void addNode(IGridNode node, @Nullable CompoundTag savedData) {
        var storageProvider = node.getService(IStorageProvider.class);
        if (storageProvider != null) {
            ProviderState state = new ProviderState(storageProvider);
            this.nodeProviders.put(node, state);
            state.mount();
        }

        var watcher = node.getService(IStorageWatcherNode.class);
        if (watcher != null) {
            var iw = new StackWatcher<>(interestManager, watcher);
            this.watchers.put(node, iw);
            watcher.updateWatcher(iw);
        }
    }

    /**
     * When a node leaves the grid, we automatically unregister the previously registered {@link IStorageProvider} or
     * {@link IStorageWatcherNode}.
     */
    @Override
    public void removeNode(IGridNode node) {
        var watcher = this.watchers.remove(node);
        if (watcher != null) {
            watcher.destroy();
        }

        var providerState = this.nodeProviders.remove(node);
        if (providerState != null) {
            providerState.unmount();
        }
    }

    @Override
    public MEStorage getInventory() {
        return storage;
    }

    @Override
    public KeyCounter getCachedInventory() {
        if (cachedStacksNeedUpdate) {
            updateCachedStacks();
        }
        return cachedAvailableStacks;
    }

    @Override
    public void addGlobalStorageProvider(IStorageProvider provider) {
        for (var state : globalProviders) {
            if (state.provider == provider) {
                throw new IllegalArgumentException("Duplicate storage provider registration for " + provider);
            }
        }

        var state = new ProviderState(provider);
        this.globalProviders.add(state);
        state.mount();
    }

    @Override
    public void removeGlobalStorageProvider(IStorageProvider provider) {
        var it = this.globalProviders.iterator();
        while (it.hasNext()) {
            var state = it.next();
            if (state.provider == provider) {
                it.remove();
                state.unmount();
            }
        }
    }

    @Override
    public void refreshNodeStorageProvider(IGridNode node) {
        var state = nodeProviders.get(node);
        if (state == null) {
            throw new IllegalArgumentException("The given node is not part of this grid or has no storage provider.");
        }
        state.update();
    }

    @Override
    public void refreshGlobalStorageProvider(IStorageProvider provider) {
        for (var state : globalProviders) {
            if (state.provider == provider) {
                state.update();
                return;
            }
        }

        throw new IllegalArgumentException("Storage provider " + provider + " is not part of this grid.");
    }

    @Override
    public void invalidateCache() {
        cachedStacksNeedUpdate = true;
    }

    /**
     * A {@link IStorageProvider}-specific mount table facade which allows the provider to easily mount/remount its
     * storage.
     */
    private class ProviderState implements IStorageMounts {
        private final IStorageProvider provider;
        private final Set<MEStorage> inventories = new HashSet<>();
        private boolean mounted;

        public ProviderState(IStorageProvider provider) {
            this.provider = provider;
        }

        /**
         * Performs the first mount operation on this storage provider, which does not assume any of the provider's
         * inventories are currently mounted and need to be removed first.
         */
        private void mount() {
            Preconditions.checkState(!mounted, "Can't mount a provider's inventories when it's already mounted");

            mounted = true;
            provider.mountInventories(this);
        }

        @Override
        public void mount(MEStorage inventory, int priority) {
            Preconditions.checkState(mounted, "Cannot use StorageMounts after the storage has been unmounted.");

            if (!inventories.add(inventory)) {
                throw new IllegalStateException("Cannot mount the same inventory twice.");
            }

            // Mount this inventory into the network storage
            storage.mount(priority, inventory);
        }

        public void update() {
            unmount();
            mount();
        }

        public void unmount() {
            if (!mounted) {
                return;
            }
            mounted = false;

            for (var inventory : inventories) {
                unmount(inventory);
            }
            inventories.clear();
        }

        private void unmount(MEStorage inventory) {
            storage.unmount(inventory);
        }
    }

    @Override
    public void debugDump(JsonWriter writer, HolderLookup.Provider registries) throws IOException {

        JsonStreamUtil.writeProperties(Map.of(
                "inventoryRefreshTime", JsonStreamUtil.toMap(inventoryRefreshStats)), writer);

        writer.name("cachedAvailableStacks");
        writer.beginArray();
        for (var entry : cachedAvailableStacks) {
            writer.beginObject();
            writer.name("key");
            var serializedKey = new CompoundTag();
            entry.getKey().toTagGeneric(TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries));
            var jsonKey = Dynamic.convert(NbtOps.INSTANCE, JsonOps.INSTANCE, serializedKey);
            GSON.toJson(jsonKey, writer);
            writer.name("amount");
            writer.value(entry.getLongValue());
            writer.endObject();
        }
        writer.endArray();

    }
}
