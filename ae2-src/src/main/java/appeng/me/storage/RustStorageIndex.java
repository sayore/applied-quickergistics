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

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.VersionedStorage;
import appeng.storage.nativebridge.DenseKeyInterner;
import appeng.storage.nativebridge.NativeNetworkIndex;

/**
 * Optional native (Rust) mirror of a {@link NetworkStorage}'s contents.
 * <p>
 * The index is a read accelerator, never a source of truth: {@link NetworkStorage} keeps its own
 * mounts and stays fully functional without it, and {@link #isEnabled()} is false whenever the native
 * library is unavailable or the feature was switched off. Only {@code getAvailableStacks} is served
 * from the mirror; insert/extract keep their original implementation so that all filtering,
 * prioritisation and side effects stay on the Java side.
 * <p>
 * # How the mirror stays in sync
 * <p>
 * A mounted storage can be mutated behind {@link NetworkStorage}'s back (an IO port fills a cell, a
 * storage bus observes an external inventory, ...). Re-reading every cell every tick would cost more
 * than the native index saves, so storages implement {@link VersionedStorage} and only those whose
 * version changed are pushed across the JNI boundary.
 * <p>
 * # Threading
 * <p>
 * Instances belong to the server thread that owns the network. Crafting calculations read
 * {@code IStorageService#getCachedInventory()} from a separate thread rather than a live network
 * inventory, so they never touch this class.
 */
public final class RustStorageIndex {
    /**
     * System property to force the accelerator on or off. When unset it is enabled if the native
     * library could be loaded.
     */
    public static final String ENABLED_PROPERTY = "ae2.native.index";

    private static final boolean ENABLED = resolveEnabled();

    /**
     * Set to {@code true} to enable the mirror.
     * <p>
     * Off by default. Measurements against real AE2 cells
     * ({@code appeng.me.storage.RealCellPerformanceTest}) show the mirror at 0.65x to 1.04x of AE2's
     * existing Java path, so enabling it unconditionally would be a regression. Two causes are known
     * and both are fixable:
     * <ul>
     * <li>{@link #sync()} walks every mounted cell on every query to compare versions. The native work
     * is only ~12 us while a query costs ~170 us, so this loop dominates.</li>
     * <li>The aggregate counter is maintained but never received incrementally, because every
     * {@code push_cell} advances the revision and invalidates the delta range.</li>
     * </ul>
     */
    private final NativeNetworkIndex index;
    private final DenseKeyInterner<AEKey> interner = new DenseKeyInterner();

    /** Mirrored mounts, keyed by the storage instance that was mounted. */
    private final Reference2ObjectMap<MEStorage, MountedCell> cells = new Reference2ObjectOpenHashMap<>();
    /** Mounts that have not been pushed to the native side yet. */
    private final List<MountedCell> pending = new ArrayList<>();
    /** Scratch buffer for a cell's key ids, reused between pushes. */
    private long[] idScratch = new long[64];
    /** Scratch buffer for a cell's amounts, reused between pushes. */
    private long[] amountScratch = new long[64];
    /**
     * Set by {@link #mount} and {@link #unmount}. A delta consumer cannot apply changes across a
     * mount change, so it has to do a full refresh instead.
     */
    private boolean mountsChanged;

    private RustStorageIndex() {
        this.index = NativeNetworkIndex.create();
    }

    /**
     * @return a new index, or {@code null} if the accelerator is disabled or the native library is
     *         unavailable.
     */
    @Nullable
    public static RustStorageIndex createIfAvailable() {
        if (!ENABLED) {
            return null;
        }
        try {
            return new RustStorageIndex();
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    private static boolean resolveEnabled() {
        // Opt-in until the two known costs above are fixed.
        return Boolean.getBoolean(ENABLED_PROPERTY)
                && appeng.storage.nativebridge.NativeLibrary.isAvailable();
    }

    public void close() {
        index.close();
    }

    /**
     * Registers a mounted storage. Storages that cannot be mirrored are ignored and cause the mirror
     * to be unusable for this network until they are unmounted.
     */
    public void mount(MEStorage storage, int priority) {
        if (cells.containsKey(storage)) {
            setPriority(storage, priority);
            return;
        }
        var cellId = index.addCell(interner.size(), priority);
        var mounted = new MountedCell(storage, cellId, priority);
        cells.put(storage, mounted);
        pending.add(mounted);
        mountsChanged = true;
    }

    public void unmount(MEStorage storage) {
        var mounted = cells.remove(storage);
        if (mounted != null) {
            pending.remove(mounted);
            index.removeCell(mounted.cellId);
            mountsChanged = true;
        }
    }

    public void setPriority(MEStorage storage, int priority) {
        var mounted = cells.get(storage);
        if (mounted != null && mounted.priority != priority) {
            mounted.priority = priority;
            index.setCellPriority(mounted.cellId, priority);
        }
    }

    /**
     * Mirrors all changed mounts and returns the aggregate.
     *
     * @return the aggregate, or {@code null} if a mount cannot be mirrored, in which case the caller
     *         must use its own code path.
     */
    /**
     * The aggregate, maintained incrementally instead of rebuilt per query.
     * <p>
     * Building a {@link KeyCounter} from the native result costs an order of magnitude more than the
     * native aggregate itself (measured at ~180 us versus ~11 us on a 14-cell drive), because every
     * entry has to be resolved back to an {@link AEKey} and inserted into the counter's nested maps.
     * That work is avoidable: the counter is kept up to date from the change log, so a query only has
     * to look at what actually changed since the previous one.
     * <p>
     * May be {@code null} before the first successful sync.
     */
    @Nullable
    private KeyCounter maintainedCounter;
    /**
     * Revision {@link #maintainedCounter} reflects, or {@code -1} when it has to be rebuilt.
     */
    private long maintainedRevision = -1;

    /**
     * The mirror's own aggregate counter, brought up to date with the mounts.
     * <p>
     * Unlike {@link #getAvailableStacks()}, which materialises a fresh counter for a caller that wants
     * to own it, this hands out the counter the mirror maintains. It is meant to be read and iterated,
     * not modified; a caller that needs to change it must copy the entries it cares about.
     *
     * @return the aggregate, or {@code null} when the mirror cannot be used.
     */
    @Nullable
    public KeyCounter getSharedAvailableStacks() {
        if (!sync()) {
            return null;
        }
        return maintainedCounter;
    }

    @Nullable
    public KeyCounter getAvailableStacks() {
        if (!sync()) {
            return null;
        }
        if (maintainedCounter == null) {
            return null;
        }
        return maintainedCounter;
    }

    /**
     * Mirrors all changed mounts and returns the aggregate restricted to the given key ids. The ids
     * are the native ids of this index, i.e. {@link #interner()}.
     */
    @Nullable
    public KeyCounter getAvailableStacks(int[] filterKeyIds) {
        if (!sync()) {
            return null;
        }
        return toCounter(index.available(filterKeyIds));
    }

    /**
     * @return the mirrored amount for a single key, or {@code -1} if the mirror cannot be used.
     */
    public long amountOf(AEKey key) {
        if (!sync()) {
            return -1;
        }
        var id = interner.idOf(key);
        return id < 0 ? 0 : index.totalOf(id);
    }

    /**
     * Mirrors all changed mounts and returns the amounts of the given keys.
     * <p>
     * This is the shape of a terminal search or a "can I craft this?" check: few keys against the
     * whole network. The native side answers it from its reverse index, so it only visits the cells
     * that actually hold each key instead of every mount.
     *
     * @return the amounts in the order of {@code keys}, or {@code null} if the mirror cannot be used.
     */
    @Nullable
    public long[] amountsOf(AEKey[] keys) {
        if (!sync()) {
            return null;
        }
        var ids = new int[keys.length];
        var result = new long[keys.length];
        for (var i = 0; i < keys.length; i++) {
            var id = interner.idOf(keys[i]);
            ids[i] = id;
            if (id < 0) {
                result[i] = 0;
            }
        }
        if (ids.length == 0) {
            return result;
        }
        var flat = index.available(ids);
        // `available` only returns non-zero entries, so zero the result and fill what came back.
        java.util.Arrays.fill(result, 0L);
        for (var i = 0; i + 1 < flat.length; i += 2) {
            var id = (int) flat[i];
            for (var k = 0; k < ids.length; k++) {
                if (ids[k] == id) {
                    result[k] = flat[i + 1];
                }
            }
        }
        return result;
    }

    @Nullable
    public AEKey keyOf(int keyId) {
        return interner.keyOf(keyId);
    }

    public int idOf(AEKey key) {
        return interner.idOf(key);
    }

    private KeyCounter toCounter(long[] flat) {
        var result = new KeyCounter();
        var keys = interner.keys();
        for (var i = 0; i + 1 < flat.length; i += 2) {
            result.add(keys.get((int) flat[i]), flat[i + 1]);
        }
        return result;
    }

    /**
     * Pushes every mount whose contents changed.
     *
     * @return false if any mounted storage cannot be mirrored, meaning the mirror is stale and must
     *         not be used.
     */
    private boolean sync() {
        var revisionBefore = index.deltaRevision();
        for (var i = 0; i < pending.size(); i++) {
            if (!push(pending.get(i))) {
                return false;
            }
        }
        if (!pending.isEmpty()) {
            pending.clear();
            // Interning may have added keys after the last push, so grow the native key space.
            index.ensureKeyCapacity(interner.size());
        }

        // Long-lived cells are re-read in the same pass, but `push` compares the storage version it
        // last mirrored against the current one, so a cell that several mutations touched during one
        // tick is still only re-read once per sync.
        for (var mounted : cells.values()) {
            if (!push(mounted)) {
                return false;
            }
        }
        return updateMaintainedCounter(revisionBefore);
    }

    /**
     * Brings {@link #maintainedCounter} up to date with the native totals.
     *
     * @return false when the mirror cannot be used, in which case the caller falls back to Java.
     */
    private boolean updateMaintainedCounter(long revisionBefore) {
        var revisionAfter = index.deltaRevision();
        if (maintainedCounter == null || maintainedRevision != revisionBefore) {
            // No usable baseline: read the whole aggregate once and adopt the counter.
            maintainedCounter = toCounter(index.available());
            maintainedRevision = index.deltaRevision();
            return true;
        }
        if (revisionAfter == revisionBefore) {
            return true;
        }
        var changes = index.deltasSince(revisionBefore);
        if (changes == null) {
            // The change log no longer covers the gap, so rebuild instead of guessing.
            maintainedCounter = toCounter(index.available());
            maintainedRevision = index.deltaRevision();
            return true;
        }
        var keys = interner.keys();
        for (var change : changes.changes()) {
            var key = keys.get(change.keyId());
            if (change.newTotal() == 0) {
                maintainedCounter.remove(key);
            } else {
                maintainedCounter.set(key, change.newTotal());
            }
        }
        maintainedRevision = changes.revision();
        return true;
    }

    /**
     * @return false if this storage cannot be mirrored.
     */
    private boolean push(MountedCell mounted) {
        var versioned = resolveVersioned(mounted.storage);
        if (versioned == null) {
            return false;
        }
        var version = versioned.storageVersion();
        if (mounted.pushed && version == mounted.version) {
            return true;
        }
        mounted.version = version;

        var contents = mounted.storage.getAvailableStacks();
        var count = contents.size();
        if (idScratch.length < count) {
            idScratch = new long[Math.max(count, idScratch.length * 2)];
            amountScratch = new long[Math.max(count, amountScratch.length * 2)];
        }
        var i = 0;
        for (var entry : contents) {
            idScratch[i] = interner.intern(entry.getKey());
            amountScratch[i] = entry.getLongValue();
            i++;
        }
        // The scratch buffers are reused between cells and the native side only sees
        // `amountScratch.length` entries, so the tail of a previous, larger cell has to be cleared.
        // Leaving stale ids behind would resurrect a previous cell's contents.
        java.util.Arrays.fill(idScratch, i, idScratch.length, 0L);
        java.util.Arrays.fill(amountScratch, i, amountScratch.length, 0L);
        // Interning may have assigned new ids, so make sure the native key space covers them.
        index.ensureKeyCapacity(interner.size());
        index.pushCell(mounted.cellId, interner.size(), idScratch, amountScratch);
        mounted.pushed = true;
        mounted.keyCount = i;
        return true;
    }

    /**
     * Finds the {@link VersionedStorage} backing a mount, or {@code null} if the mount cannot be
     * mirrored.
     * <p>
     * Wrappers are transparent for {@code getAvailableStacks} as long as they do not filter the
     * reported contents and allow extraction; when they do filter, the mirror would over-report and
     * the mount is rejected. Note that the check must be against {@link DelegatingMEInventory}, not
     * {@link MEInventoryHandler}: AE2's own drives mount a {@code DriveWatcher}, which is a subclass
     * that adds status tracking on top of the handler.
     */
    @Nullable
    private static VersionedStorage resolveVersioned(MEStorage storage) {
        if (storage instanceof MEInventoryHandler handler
                && (handler.filtersAvailableContents() || !handler.allowsExtraction())) {
            return null;
        }
        if (storage instanceof DelegatingMEInventory delegating) {
            return resolveVersioned(delegating.getDelegate());
        }
        if (storage instanceof VersionedStorage versioned) {
            return versioned;
        }
        return null;
    }

    /**
     * Diagnostic snapshot, used by tests and debug tooling.
     */
    public record Stats(int mountedCells, int internedKeys, int distinctKeys, long totalAmount) {
    }

    public Stats stats() {
        var nativeStats = index.stats();
        return new Stats(
                (int) nativeStats[0],
                interner.size(),
                (int) nativeStats[2],
                nativeStats[3]);
    }

    /**
     * Whether the incremental tick refresh may be used.
     * <p>
     * Off by default on purpose. The change log itself is complete and verified, but the mirror
     * currently reports too many changes: it records roughly 350 changes per actual mutation on a
     * 400-cell network, because a cell whose version changed is re-pushed and its whole content is
     * subtracted and re-added. Applying that many changes costs more than one full aggregate, so the
     * path stays disabled until the mirror's push behaviour is narrowed. See the crate documentation
     * for the measurement.
     */
    public static final String INCREMENTAL_PROPERTY = "ae2.native.incremental";

    public static boolean isIncrementalRefreshEnabled() {
        return Boolean.getBoolean(INCREMENTAL_PROPERTY);
    }

    /**
     * Mirrors all changed mounts and returns the network-total changes since {@code sinceRevision}.
     * <p>
     * This is the per-tick path: a consumer that stayed in sync receives only the keys that actually
     * changed, instead of an aggregate of every stored type.
     *
     * @return the changes, or {@code null} when the caller has to fall back to a full
     *         {@link #getAvailableStacks()}. That happens when the mount set changed, so the deltas
     *         cannot describe it, and when the native side no longer retains that revision.
     */
    @Nullable
    public ChangeSet deltasSince(long sinceRevision) {
        if (!sync()) {
            return null;
        }
        if (mountsChanged) {
            mountsChanged = false;
            return null;
        }
        var nativeChanges = index.deltasSince(sinceRevision);
        if (nativeChanges == null) {
            return null;
        }
        var keys = interner.keys();
        var changes = new ArrayList<KeyChange>(nativeChanges.changes().length);
        for (var change : nativeChanges.changes()) {
            changes.add(new KeyChange(keys.get(change.keyId()), change.oldTotal(), change.newTotal()));
        }
        return new ChangeSet(nativeChanges.revision(), changes);
    }

    /**
     * The revision the mirror's totals are at, or {@code -1} when the mirror is unusable.
     */
    public long revision() {
        return index.deltaRevision();
    }

    /** One key's total changing from {@code oldTotal} to {@code newTotal}. */
    public record KeyChange(AEKey key, long oldTotal, long newTotal) {
    }

    /** A replayable run of changes ending at {@code revision}. */
    public record ChangeSet(long revision, List<KeyChange> changes) {
    }

    /** Diagnostic: {@code [realPushes, totalPushes]}. */
    public long[] pushStats() {
        return index.pushStats();
    }

    public NativeNetworkIndex nativeIndex() {
        return index;
    }

    public DenseKeyInterner<AEKey> interner() {
        return interner;
    }

    private static final class MountedCell {
        final MEStorage storage;
        final int cellId;
        int priority;
        long version = Long.MIN_VALUE;
        boolean pushed;
        int keyCount;

        MountedCell(MEStorage storage, int cellId, int priority) {
            this.storage = storage;
            this.cellId = cellId;
            this.priority = priority;
        }
    }
}
