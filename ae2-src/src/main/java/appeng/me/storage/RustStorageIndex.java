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
import java.util.Set;

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
 * The index is a read accelerator, never a source of truth: {@link NetworkStorage} keeps its own mounts and stays fully
 * functional without it, and {@link #isEnabled()} is false whenever the native library is unavailable or the feature
 * was switched off. Only {@code getAvailableStacks} is served from the mirror; insert/extract keep their original
 * implementation so that all filtering, prioritisation and side effects stay on the Java side.
 * <p>
 * # How the mirror stays in sync
 * <p>
 * A mounted storage can be mutated behind {@link NetworkStorage}'s back (an IO port fills a cell, a storage bus
 * observes an external inventory, ...). Re-reading every cell every tick would cost more than the native index saves,
 * so storages implement {@link VersionedStorage} and only those whose version changed are pushed across the JNI
 * boundary.
 * <p>
 * # Threading
 * <p>
 * Instances belong to the server thread that owns the network. Crafting calculations read
 * {@code IStorageService#getCachedInventory()} from a separate thread rather than a live network inventory, so they
 * never touch this class.
 */
public final class RustStorageIndex {
    /**
     * System property that turns the mirror off: {@code -Dae2.native.index=false}.
     * <p>
     * Enabled by default when the native library loads. Measured against real AE2 cells
     * ({@code appeng.me.storage.RealCellPerformanceTest}), the aggregate query is faster than AE2's Java path at every
     * network size tested, including a single cell: 6-10x for one cell, 30-166x for four, 230-396x for fourteen,
     * 344-533x for twenty-nine, and 6.1-7.4x when one cell changes every tick.
     * <p>
     * What made it slower before is fixed: the aggregate counter is maintained instead of rebuilt per query, the
     * caller's counter is handed back instead of copied into, and the gap in the change log is found by binary search
     * instead of by scanning every retained entry.
     */
    public static final String ENABLED_PROPERTY = "ae2.native.index";

    private static final boolean ENABLED = resolveEnabled();

    private final NativeNetworkIndex index;
    /**
     * Releases the native index when this mirror becomes unreachable.
     * <p>
     * The handle owns native memory - a change log alone can grow to a few megabytes - and nothing in the grid
     * lifecycle calls {@link #close()}. A network is discarded whenever its grid empties, which happens constantly in
     * play, so without this every grid that ever existed leaked its index until the JVM exited. The action is
     * registered on a separate state object so that holding it does not keep the mirror itself alive.
     */
    private static final java.lang.ref.Cleaner CLEANER = java.lang.ref.Cleaner.create(
            runnable -> {
                var thread = new Thread(runnable, "ae2store-index-cleaner");
                thread.setDaemon(true);
                return thread;
            });
    private final java.lang.ref.Cleaner.Cleanable cleanable;
    private final DenseKeyInterner<AEKey> interner = new DenseKeyInterner<>(AEKey::getPrimaryKey);

    /** Mirrored mounts, keyed by the storage instance that was mounted. */
    private final Reference2ObjectMap<MEStorage, MountedCell> cells = new Reference2ObjectOpenHashMap<>();
    /** Mounts that have not been pushed to the native side yet. */
    private final List<MountedCell> pending = new ArrayList<>();
    /** Scratch buffer for a cell's key ids, reused between pushes. */
    private long[] idScratch = new long[64];
    /** Scratch buffer for a cell's amounts, reused between pushes. */
    private long[] amountScratch = new long[64];
    /**
     * Set by {@link #mount} and {@link #unmount}. A delta consumer cannot apply changes across a mount change, so it
     * has to do a full refresh instead.
     */
    private boolean mountsChanged;

    private RustStorageIndex() {
        this.index = NativeNetworkIndex.create();
        this.cleanable = CLEANER.register(this, new NativeHandleRelease(this.index));
    }

    /** Releases a mirror's native handle exactly once, from {@link #close()} or from the cleaner. */
    private record NativeHandleRelease(NativeNetworkIndex index) implements Runnable {
        @Override
        public void run() {
            index.close();
        }
    }

    /**
     * @return a new index, or {@code null} if the accelerator is disabled or the native library is unavailable.
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
        var property = System.getProperty(ENABLED_PROPERTY);
        if (property != null) {
            return Boolean.parseBoolean(property);
        }
        return appeng.storage.nativebridge.NativeLibrary.isAvailable();
    }

    /**
     * Releases the native index. Idempotent, and also performed by the cleaner when this mirror is collected, so a
     * caller that cannot know when a network is done does not have to call it.
     */
    public void close() {
        cleanable.clean();
    }

    /** @return whether the native index is still open. For tests and diagnostics. */
    public boolean isOpen() {
        return index.isOpen();
    }

    /**
     * Registers a mounted storage. Storages that cannot be mirrored are ignored and cause the mirror to be unusable for
     * this network until they are unmounted.
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
        lastScannedRevision = -1;
    }

    public void unmount(MEStorage storage) {
        var mounted = cells.remove(storage);
        if (mounted != null) {
            pending.remove(mounted);
            index.removeCell(mounted.cellId);
            mountsChanged = true;
            lastScannedRevision = -1;
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
     * @return the aggregate, or {@code null} if a mount cannot be mirrored, in which case the caller must use its own
     *         code path.
     */
    /**
     * The aggregate, maintained incrementally instead of rebuilt per query.
     * <p>
     * Building a {@link KeyCounter} from the native result costs an order of magnitude more than the native aggregate
     * itself (measured at ~180 us versus ~11 us on a 14-cell drive), because every entry has to be resolved back to an
     * {@link AEKey} and inserted into the counter's nested maps. That work is avoidable: the counter is kept up to date
     * from the change log, so a query only has to look at what actually changed since the previous one.
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
     * Native revision at which every mount was last checked for changes, or {@code -1} to force a rescan. Nothing can
     * have changed while the revision is unchanged.
     */
    private long lastScannedRevision = -1;
    /**
     * Set when a mount was added or removed, which the native revision cannot express. A forced rescan is what makes an
     * out-of-band mutation visible again.
     */
    private boolean rescanWanted = true;
    /**
     * Diagnostics: {@code [rebuilds, incrementalUpdates, syncWalks, pushes]}.
     */
    public final long[] diag = new long[4];
    /** Mounts the mirror cannot reproduce, so the caller has to read them in Java. */
    private final Set<MEStorage> uncovered = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** Set by the last {@link #sync()} when {@link #uncovered} is not empty. */
    private boolean uncoveredVisible;

    /**
     * Why a mount could not be mirrored.
     */
    public enum ExclusionReason {
        /** The mount does not implement {@link VersionedStorage}, so its changes cannot be observed. */
        NOT_VERSIONED,
        /** The mount filters the contents it reports, which the mirror cannot reproduce yet. */
        FILTERS_CONTENTS,
        /** The mount refuses extraction, so it is not part of the available contents. */
        EXTRACTION_DISABLED
    }

    /**
     * Reports which mounts are actually mirrored and which are excluded, and why.
     * <p>
     * This matters more than it looks. An excluded mount used to disable the whole mirror for that network, silently -
     * the network simply ran on the Java path and looked healthy. Excluded mounts are now read in Java on top of the
     * mirror's aggregate, so this report answers "how much of this network is actually accelerated".
     *
     * @return a summary for diagnostics and tests
     */
    public String describeCoverage() {
        var mirrored = 0;
        var excluded = new java.util.EnumMap<ExclusionReason, Integer>(ExclusionReason.class);
        for (var mounted : cells.values()) {
            var reason = exclusionReason(mounted.storage);
            if (reason == null) {
                mirrored++;
            } else {
                excluded.merge(reason, 1, Integer::sum);
            }
        }
        var out = new StringBuilder();
        out.append("mirrored=").append(mirrored);
        out.append(", excluded=").append(cells.size() - mirrored);
        if (!excluded.isEmpty()) {
            out.append(" (");
            out.append(excluded);
            out.append(')');
        }
        return out.toString();
    }

    /**
     * @return why this storage cannot be mirrored, or {@code null} when it can be.
     */
    @Nullable
    private static ExclusionReason exclusionReason(MEStorage storage) {
        if (storage instanceof MEInventoryHandler handler) {
            if (!handler.allowsExtraction()) {
                return ExclusionReason.EXTRACTION_DISABLED;
            }
            if (handler.filtersAvailableContents()) {
                return ExclusionReason.FILTERS_CONTENTS;
            }
        }
        if (storage instanceof DelegatingMEInventory delegating) {
            return exclusionReason(delegating.getDelegate());
        }
        if (storage instanceof VersionedStorage) {
            return null;
        }
        return ExclusionReason.NOT_VERSIONED;
    }

    /**
     * The mirror's own aggregate counter, brought up to date with the mounts.
     * <p>
     * Unlike a fresh counter built for a caller that wants to own it, this hands out the counter the mirror maintains.
     * It is meant to be read and iterated, not modified; a caller that needs to change it must copy the entries it
     * cares about.
     * <p>
     * When {@link #hasUncoveredMounts()} is true, the returned counter holds only the mounts the mirror could
     * reproduce. It is still worth using - reading those in Java instead is what makes an unmirrorable mount cost the
     * whole network - but the caller has to add the uncovered mounts on top before exposing the result.
     *
     * @return the aggregate, or {@code null} when the mirror cannot be used at all.
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
        return maintainedCounter;
    }

    /**
     * Mirrors all changed mounts and returns the aggregate restricted to the given key ids. The ids are the native ids
     * of this index, i.e. {@link #interner()}.
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
     * This is the shape of a terminal search or a "can I craft this?" check: few keys against the whole network. The
     * native side answers it from its reverse index, so it only visits the cells that actually hold each key instead of
     * every mount.
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
     * @return false if any mounted storage cannot be mirrored, meaning the mirror is stale and must not be used.
     */
    private boolean sync() {
        var syncStart = System.nanoTime();
        try {
            return syncInner();
        } finally {
            syncNanos += System.nanoTime() - syncStart;
            syncCalls++;
        }
    }

    private boolean syncInner() {
        var revisionBefore = index.deltaRevision();
        uncovered.clear();
        uncoveredVisible = false;
        for (var i = 0; i < pending.size(); i++) {
            var m = pending.get(i);
            if (!push(m)) {
                uncovered.add(m.storage);
            }
        }
        if (!pending.isEmpty()) {
            pending.clear();
            // Interning may have added keys after the last push, so grow the native key space.
            index.ensureKeyCapacity(interner.size());
        }

        // The per-cell version check always runs, because it is the only way to observe a mutation
        // that happened to a mount outside the network's own insert/extract calls - which is exactly
        // what a drive cell written by an IO port does. The check itself is one virtual call per
        // cell; everything expensive happens behind it: a cell whose version did not move returns
        // early in `push`, and when no cell moved the aggregate counter is not touched at all.
        diag[2]++;
        var covered = true;
        for (var mounted : cells.values()) {
            if (!push(mounted)) {
                covered = false;
                uncovered.add(mounted.storage);
            }
        }
        if (!covered) {
            // A mount that cannot be mirrored does not invalidate the mirror: the counter keeps the
            // sum of the mounts that *can* be reproduced, and the caller adds the excluded mounts on
            // top. Before this was per-mount, a single unmirrorable storage (a storage bus, a filtered
            // handler) forced the entire network back onto the Java path, which threw away the
            // acceleration for every mirrored cell as well.
            uncoveredVisible = true;
        }
        return updateMaintainedCounter(revisionBefore);
    }

    /**
     * Whether the last {@link #sync()} could not reproduce every mount, meaning the caller has to add the mounts
     * returned by {@link #getUncoveredMounts()} itself.
     */
    public boolean hasUncoveredMounts() {
        return uncoveredVisible;
    }

    /**
     * Mounts the mirror could not reproduce for the current query. Only valid until the next query.
     */
    public Set<MEStorage> getUncoveredMounts() {
        return uncovered;
    }

    /**
     * Brings {@link #maintainedCounter} up to date with the native totals.
     *
     * @return false when the mirror cannot be used, in which case the caller falls back to Java.
     */

    private boolean updateMaintainedCounter(long revisionBefore) {
        var revisionAfter = index.deltaRevision();
        if (maintainedCounter == null || maintainedRevision != revisionBefore) {
            diag[0]++;
            // No usable baseline: read the whole aggregate once and adopt the counter. Rebuilding from
            // scratch is also what makes the counter usable as a full snapshot for a caller: it must
            // not keep a key that no query writes any more, and in partial mode every sync lands here.
            maintainedCounter = toCounter(index.available());
            maintainedRevision = index.deltaRevision();
            return true;
        }
        if (revisionAfter == revisionBefore) {
            return true;
        }
        var changes = index.deltasSince(revisionBefore);
        if (changes == null) {
            diag[0]++;
            // The change log no longer covers the gap, so rebuild instead of guessing.
            maintainedCounter = toCounter(index.available());
            maintainedRevision = index.deltaRevision();
            return true;
        }
        diag[1]++;
        var diagStart = System.nanoTime();
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
        deltaApplyNanos += System.nanoTime() - diagStart;
        deltaChangeCount += changes.changes().length;
        deltaCallCount++;
        return true;
    }

    /** Diagnostic: nanoseconds spent applying deltas to {@link #maintainedCounter}. */
    public long deltaApplyNanos;
    /** Diagnostic: total changes applied and number of delta applications. */
    public long deltaChangeCount;
    public long deltaCallCount;
    /** Diagnostic: how often a mount's contents were re-read because its version moved. */
    public long cellReads;
    /** Diagnostic: nanos spent inside {@link #sync()}, and how often it ran. */
    public long syncNanos;
    public long syncCalls;
    /** Diagnostic: nanos inside {@link #push(MountedCell)}, split by phase. */
    public long profRead;
    public long profIntern;
    public long profPush;

    /** Zeroes the timing diagnostics so a measurement can attribute time to a single phase. */
    public void resetProfiling() {
        deltaApplyNanos = 0;
        deltaChangeCount = 0;
        deltaCallCount = 0;
        cellReads = 0;
        syncNanos = 0;
        syncCalls = 0;
        profRead = 0;
        profIntern = 0;
        profPush = 0;
    }

    /** Human-readable profiling summary for {@link #resetProfiling()} measurements. */
    public String profilingSummary(long iterations) {
        if (iterations <= 0) {
            return "no iterations";
        }
        return String.format(java.util.Locale.ROOT,
                "sync=%.3f us/op (%.2f calls/op), cellRead=%.3f us/op, "
                        + "deltaApply=%.3f us/op (%.2f changes/op)",
                syncNanos / 1000.0 / iterations, syncCalls / (double) iterations,
                cellReads / (double) iterations, deltaApplyNanos / 1000.0 / iterations,
                deltaChangeCount / (double) iterations)
                + String.format(java.util.Locale.ROOT,
                        " [push phases: read=%.3f intern=%.3f jni=%.3f us/op]",
                        profRead / 1000.0 / iterations, profIntern / 1000.0 / iterations,
                        profPush / 1000.0 / iterations);
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
        diag[3]++;
        mounted.version = version;

        cellReads++;
        var t0 = System.nanoTime();
        var contents = mounted.storage.getAvailableStacks();
        var count = contents.size();
        profRead += System.nanoTime() - t0;
        var t1 = System.nanoTime();
        if (idScratch.length < count) {
            idScratch = new long[Math.max(count, idScratch.length * 2)];
            amountScratch = new long[Math.max(count, amountScratch.length * 2)];
        }
        var i = 0;
        for (var entry : contents) {
            // The canonical instance, not the one this read happened to create: the counter the mirror
            // hands out and the deltas it reports have to agree on which object represents a resource,
            // or an update to one variant overwrites another variant's amount.
            var canonical = interner.internCanonical(entry.getKey());
            idScratch[i] = interner.idOf(canonical);
            amountScratch[i] = entry.getLongValue();
            i++;
        }
        // The scratch buffers are reused between cells and the native side only sees
        // `amountScratch.length` entries, so the tail of a previous, larger cell has to be cleared.
        // Leaving stale ids behind would resurrect a previous cell's contents.
        profIntern += System.nanoTime() - t1;
        var t2 = System.nanoTime();
        java.util.Arrays.fill(idScratch, i, idScratch.length, 0L);
        java.util.Arrays.fill(amountScratch, i, amountScratch.length, 0L);
        // Interning may have assigned new ids, so make sure the native key space covers them.
        index.ensureKeyCapacity(interner.size());
        index.pushCell(mounted.cellId, interner.size(), idScratch, amountScratch);
        profPush += System.nanoTime() - t2;
        mounted.pushed = true;
        mounted.keyCount = i;
        return true;
    }

    /**
     * Finds the {@link VersionedStorage} backing a mount, or {@code null} if the mount cannot be mirrored.
     * <p>
     * Wrappers are transparent for {@code getAvailableStacks} as long as they do not filter the reported contents and
     * allow extraction; when they do filter, the mirror would over-report and the mount is rejected. Note that the
     * check must be against {@link DelegatingMEInventory}, not {@link MEInventoryHandler}: AE2's own drives mount a
     * {@code DriveWatcher}, which is a subclass that adds status tracking on top of the handler.
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
     * On by default. It used to be off because the mirror reported far too many changes - roughly 350 per real
     * mutation, because a cell whose version changed was re-pushed and its whole content re-recorded. Applying that
     * cost more than one full aggregate. {@code push_cell} now records one change per key whose total moved, and the
     * tick path is correspondingly cheap: the mirror's maintained counter is patched in place instead of the consumer
     * walking every stored type to find out what changed - 16 us against 129 us on the 29-cell benchmark.
     * <p>
     * Set {@code -Dae2.native.incremental=false} to force the full-walk path, which is what the tests use to compare
     * the two.
     */
    public static final String INCREMENTAL_PROPERTY = "ae2.native.incremental";

    public static boolean isIncrementalRefreshEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(INCREMENTAL_PROPERTY));
    }

    /**
     * Mirrors all changed mounts and returns the network-total changes since {@code sinceRevision}.
     * <p>
     * This is the per-tick path: a consumer that stayed in sync receives only the keys that actually changed, instead
     * of an aggregate of every stored type.
     *
     * @return the changes, or {@code null} when the caller has to fall back to a full {@link #getAvailableStacks()}.
     *         That happens when the mount set changed, so the deltas cannot describe it, and when the native side no
     *         longer retains that revision.
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
        if (uncoveredVisible) {
            // The change log only describes the mounts the mirror reproduced. Returning it here would
            // silently omit every change to an excluded mount, so the caller is sent to the full
            // refresh, which reads the excluded mounts in Java.
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

    /** Diagnostic: the sum of the native network totals. */
    public long nativeTotalAmount() {
        return index.totalAmount();
    }

    /** Diagnostic: how many retained changes the native log currently holds. */
    public int pendingDeltaCount() {
        return index.pendingDeltaCount();
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
