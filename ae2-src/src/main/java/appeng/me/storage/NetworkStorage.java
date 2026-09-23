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

package appeng.me.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import com.google.common.base.Preconditions;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.core.localization.GuiText;

/**
 * Manages all available {@link MEStorage} on the network.
 */
public class NetworkStorage implements MEStorage {
    private static final Comparator<Integer> PRIORITY_SORTER = (o1, o2) -> Integer.compare(o2, o1);

    // This flag prevents both concurrent modifications of the mounted storage while
    // they're being iterated, and recursive extract/insert/list operations.
    private boolean mountsInUse;

    private final NavigableMap<Integer, List<MEStorage>> priorityInventory;
    private final List<MEStorage> secondPassInventories = new ArrayList<>();

    /**
     * Optional native read accelerator for {@link #getAvailableStacks(KeyCounter)}. It mirrors this
     * storage's mounts and is maintained incrementally, so the per-tick aggregate query does not have
     * to walk every cell. {@code null} whenever the native library is unavailable or the feature is
     * disabled, in which case the original code path is used unchanged.
     */
    @Nullable
    private RustStorageIndex nativeIndex = RustStorageIndex.createIfAvailable();

    // Queued mount/unmount operations that occurred while an insert/extract was ongoing
    // Is only non-null if something is queued
    @Nullable
    private List<QueuedOperation> queuedOperations;

    public NetworkStorage() {
        this.priorityInventory = new TreeMap<>(PRIORITY_SORTER);
    }

    public void mount(int priority, MEStorage inventory) {
        if (mountsInUse) {
            if (queuedOperations == null) {
                queuedOperations = new ArrayList<>();
            }
            queuedOperations.add(new MountOperation(priority, inventory));
        } else {
            this.priorityInventory.computeIfAbsent(priority, k -> new ArrayList<>())
                    .add(inventory);
            var index = this.nativeIndex;
            if (index != null) {
                index.mount(inventory, priority);
            }
        }
    }

    public void unmount(MEStorage inventory) {
        if (mountsInUse) {
            if (queuedOperations == null) {
                queuedOperations = new ArrayList<>();
            }
            queuedOperations.add(new UnmountOperation(inventory));
        } else {
            var prioIt = this.priorityInventory.entrySet().iterator();
            while (prioIt.hasNext()) {
                var prioEntry = prioIt.next();

                var inventories = prioEntry.getValue();
                if (inventories.remove(inventory) && inventories.isEmpty()) {
                    prioIt.remove();
                }
            }
            var index = this.nativeIndex;
            if (index != null) {
                index.unmount(inventory);
            }
        }
    }

    public long insert(AEKey what, long amount, Actionable type, IActionSource src) {
        if (mountsInUse) {
            return 0; // Prevent recursive use
        }

        var remaining = amount;

        mountsInUse = true;
        try {
            for (var invList : this.priorityInventory.values()) {
                secondPassInventories.clear();

                // First give every inventory a chance to accept the item if it's preferential storage for the given
                // stack
                var ii = invList.iterator();
                while (ii.hasNext() && remaining > 0) {
                    var inv = ii.next();

                    if (isQueuedForRemoval(inv)) {
                        continue;
                    }

                    if (inv.isPreferredStorageFor(what, src)) {
                        remaining -= inv.insert(what, remaining, type, src);
                    } else {
                        secondPassInventories.add(inv);
                    }
                }

                // Then give every remaining inventory a chance
                for (var inv : secondPassInventories) {
                    if (remaining <= 0) {
                        break;
                    }

                    if (isQueuedForRemoval(inv)) {
                        continue;
                    }

                    remaining -= inv.insert(what, remaining, type, src);
                }
            }

        } finally {
            mountsInUse = false;
        }

        flushQueuedOperations();

        return amount - remaining;
    }

    private void flushQueuedOperations() {
        Preconditions.checkState(!this.mountsInUse);
        var queuedOperations = this.queuedOperations;
        if (queuedOperations != null) {
            this.queuedOperations = null;
            for (var op : queuedOperations) {
                if (op instanceof MountOperation mountOp) {
                    mount(mountOp.priority, mountOp.storage);
                } else if (op instanceof UnmountOperation unmountOp) {
                    unmount(unmountOp.storage);
                } else {
                    throw new IllegalStateException("Unknown operation: " + op);
                }
            }
        }
    }

    private boolean isQueuedForRemoval(MEStorage inv) {
        if (queuedOperations != null) {
            for (var queuedOperation : queuedOperations) {
                if (queuedOperation instanceof UnmountOperation unmountOperation && unmountOperation.storage == inv) {
                    return true;
                }
            }
        }
        return false;
    }

    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (mountsInUse) {
            return 0; // Prevent recursive use
        }

        var extracted = 0L;

        mountsInUse = true;
        try {
            for (var invList : this.priorityInventory.descendingMap().values()) {
                var ii = invList.iterator();
                while (ii.hasNext() && extracted < amount) {
                    var inv = ii.next();

                    if (isQueuedForRemoval(inv)) {
                        continue;
                    }

                    extracted += inv.extract(what, amount - extracted, mode, source);
                }
            }
        } finally {
            mountsInUse = false;
        }

        flushQueuedOperations();

        return extracted;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        if (mountsInUse) {
            return; // Prevent recursive use
        }

        // Fast path: let the native mirror serve the aggregate. It returns null when it cannot be
        // used (native library missing, a mount that cannot report changes, ...), in which case the
        // original implementation below runs unchanged.
        var index = this.nativeIndex;
        if (index != null) {
            var accelerated = index.getSharedAvailableStacks();
            if (accelerated != null) {
                // Whatever the mirror could not reproduce is read here in Java and added on top, so
                // one such mount costs only itself instead of disabling the mirror for the network.
                var uncovered = index.getUncoveredMounts();
                if (!uncovered.isEmpty()) {
                    mountsInUse = true;
                    try {
                        for (var invList : this.priorityInventory.values()) {
                            for (var inv : invList) {
                                if (uncovered.contains(inv)) {
                                    inv.getAvailableStacks(accelerated);
                                }
                            }
                        }
                    } finally {
                        mountsInUse = false;
                    }
                }

                // A caller that passes back the exact counter this method handed out last time is
                // already up to date, so there is nothing to copy. StorageService does this every
                // tick; copying instead cost ~232 us for 1827 stored types, while the maintained
                // counter is ready in under a microsecond.
                if (out == accelerated) {
                    return;
                }
                // `out` may already have been cleared via KeyCounter#clear, which only clears the
                // inner variant counters and keeps the primary-key entries. Setting absolute amounts
                // is therefore correct regardless of the state `out` arrives in.
                for (var entry : accelerated) {
                    out.set(entry.getKey(), entry.getLongValue());
                }
                return;
            }
        }

        mountsInUse = true;
        try {
            for (var i : this.priorityInventory.values()) {
                for (var j : i) {
                    j.getAvailableStacks(out);
                }
            }
        } finally {
            mountsInUse = false;
        }
    }

    /**
     * The native mirror's maintained aggregate, or {@code null} when the mirror is unavailable.
     * <p>
     * The returned counter must be treated as read-only and must not be retained across ticks. It
     * exists so a consumer that just needs the current content can avoid copying every entry.
     */
    @Nullable
    public KeyCounter getSharedAvailableStacks() {
        var index = this.nativeIndex;
        if (index == null || mountsInUse) {
            return null;
        }
        return index.getSharedAvailableStacks();
    }

    /**
     * Network-total changes since {@code sinceRevision}, for consumers that stay in sync tick by tick.
     * <p>
     * Returns {@code null} when the caller has to use {@link #getAvailableStacks(KeyCounter)} instead:
     * the native mirror is unavailable, or the changes are not replayable from that revision (a mount
     * changed, or the native side no longer retains it).
     */
    @Nullable
    public RustStorageIndex.ChangeSet deltasSince(long sinceRevision) {
        var index = this.nativeIndex;
        if (index == null || mountsInUse) {
            return null;
        }
        return index.deltasSince(sinceRevision);
    }

    /**
     * The revision the network totals are at, or {@code -1} when the native mirror is unavailable.
     */
    public long revision() {
        var index = this.nativeIndex;
        return index == null ? -1 : index.revision();
    }

    @Override
    public Component getDescription() {
        return GuiText.MENetworkStorage.text();
    }

    sealed interface QueuedOperation permits MountOperation, UnmountOperation {
    }

    private record MountOperation(int priority, MEStorage storage) implements QueuedOperation {
    }

    private record UnmountOperation(MEStorage storage) implements QueuedOperation {
    }
}
