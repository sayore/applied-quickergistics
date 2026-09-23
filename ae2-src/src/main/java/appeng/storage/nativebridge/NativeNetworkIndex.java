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

package appeng.storage.nativebridge;

import org.jetbrains.annotations.Nullable;

/**
 * Thin, allocation-conscious wrapper around the {@code ae2store} Rust network index.
 * <p>
 * This class is deliberately free of any Minecraft or AE2 API dependency so it can be unit tested and benchmarked in a
 * plain JVM. The AE2 integration lives in {@code appeng.me.storage.rust}.
 * <p>
 * Instances are <strong>not</strong> thread-safe, matching the threading rules of the Minecraft server thread that owns
 * them.
 * <p>
 * The native object is never allowed to hold Java references: keys are identified by dense {@code int} ids that the
 * caller assigns. This makes the handle a plain owned Rust allocation with no GC bookkeeping.
 */
public final class NativeNetworkIndex implements AutoCloseable {
    private static final int STATS_CELL_COUNT = 0;
    private static final int STATS_KEY_CAPACITY = 1;
    private static final int STATS_DISTINCT_KEYS = 2;
    private static final int STATS_TOTAL_AMOUNT = 3;
    private static final int STATS_REVISION = 4;

    private long handle;

    private NativeNetworkIndex(long handle) {
        this.handle = handle;
    }

    /**
     * Creates a new native index.
     *
     * @throws IllegalStateException if the native library is not available.
     */
    public static NativeNetworkIndex create() {
        if (!NativeLibrary.isAvailable()) {
            throw new IllegalStateException("ae2store native library is not available: "
                    + NativeLibrary.getStatus());
        }
        var handle = NativeBindings.create();
        if (handle == 0) {
            throw new IllegalStateException("ae2store native index allocation failed");
        }
        return new NativeNetworkIndex(handle);
    }

    private void requireOpen() {
        if (handle == 0) {
            throw new IllegalStateException("NativeNetworkIndex is closed");
        }
    }

    @Override
    public void close() {
        var h = handle;
        if (h != 0) {
            handle = 0;
            NativeBindings.destroy(h);
        }
    }

    public boolean isOpen() {
        return handle != 0;
    }

    public void ensureKeyCapacity(int keyCapacity) {
        requireOpen();
        NativeBindings.ensureKeyCapacity(handle, keyCapacity);
    }

    /**
     * Registers a storage. {@code keyCapacity} must be at least the highest key id in use plus one.
     *
     * @return the cell id used by all other methods.
     */
    public int addCell(int keyCapacity, int priority) {
        requireOpen();
        var cellId = NativeBindings.addCell(handle, keyCapacity, priority);
        if (cellId < 0) {
            throw new IllegalStateException("native index rejected the new cell");
        }
        return cellId;
    }

    public void removeCell(int cellId) {
        requireOpen();
        NativeBindings.removeCell(handle, cellId);
    }

    public void setCellPriority(int cellId, int priority) {
        requireOpen();
        NativeBindings.setCellPriority(handle, cellId, priority);
    }

    public void setCellMaxPerKey(int cellId, long maxPerKey) {
        requireOpen();
        NativeBindings.setCellMaxPerKey(handle, cellId, maxPerKey);
    }

    /**
     * Installs a whitelist filter. {@code null} clears the filter.
     */
    public void setCellWhitelist(int cellId, int[] whitelist) {
        requireOpen();
        NativeBindings.setCellWhitelist(handle, cellId,
                whitelist == null ? null : widen(whitelist));
    }

    /**
     * Replaces a cell's contents. Both arrays must have the same length and contain no zero amounts.
     */
    public void pushCell(int cellId, int keyCapacity, long[] ids, long[] amounts) {
        requireOpen();
        NativeBindings.pushCell(handle, cellId, keyCapacity, ids, amounts);
    }

    public void applyCellDelta(int cellId, int keyId, long delta) {
        requireOpen();
        NativeBindings.applyCellDelta(handle, cellId, keyId, delta);
    }

    /**
     * Aggregates the whole index, optionally restricted to the given key ids.
     *
     * @return a flat array laid out as {@code [id0, amount0, id1, amount1, ...]} sorted by id.
     */
    public long[] available() {
        requireOpen();
        return NativeBindings.available(handle, null);
    }

    public long[] available(int[] filter) {
        requireOpen();
        return NativeBindings.available(handle, filter == null ? null : widen(filter));
    }

    public long totalOf(int keyId) {
        requireOpen();
        return NativeBindings.totalOf(handle, keyId);
    }

    public long extract(int keyId, long amount, boolean simulate) {
        requireOpen();
        return NativeBindings.extract(handle, keyId, amount, simulate);
    }

    public long insert(int keyId, long amount, boolean simulate) {
        requireOpen();
        return NativeBindings.insert(handle, keyId, amount, simulate);
    }

    public boolean isPreferred(int keyId) {
        requireOpen();
        return NativeBindings.isPreferred(handle, keyId);
    }

    public void compact() {
        requireOpen();
        NativeBindings.compact(handle);
    }

    /**
     * A change to one key's network-wide total.
     */
    public record TotalChange(int keyId, long oldTotal, long newTotal, int cellId) {
    }

    /** A replayable run of changes ending at {@code revision}. */
    public record ChangeSet(long revision, TotalChange[] changes) {
    }

    /**
     * Changes to network totals since {@code sinceRevision}.
     *
     * @return the changes, or {@code null} when that range is no longer replayable and the caller has to recompute the
     *         aggregate instead.
     */
    @Nullable
    public ChangeSet deltasSince(long sinceRevision) {
        requireOpen();
        var flat = NativeBindings.deltasSince(handle, sinceRevision);
        if (flat.length < 2 || flat[0] == 0) {
            return null;
        }
        var revision = flat[1];
        var count = (flat.length - 2) / 4;
        var changes = new TotalChange[count];
        for (var i = 0; i < count; i++) {
            var at = 2 + i * 4;
            changes[i] = new TotalChange((int) flat[at], flat[at + 1], flat[at + 2], (int) flat[at + 3]);
        }
        return new ChangeSet(revision, changes);
    }

    /**
     * @return {@code [realPushes, totalPushes]}. Diagnostic only.
     */
    public long[] pushStats() {
        requireOpen();
        return NativeBindings.pushStats(handle);
    }

    /** Diagnostic: the number of changes the native log currently retains. */
    public int pendingDeltaCount() {
        requireOpen();
        return (int) NativeBindings.pendingDeltaCount(handle);
    }

    /**
     * @return the revision the network totals are currently at.
     */
    public long deltaRevision() {
        requireOpen();
        return NativeBindings.deltaRevision(handle);
    }

    /**
     * @return {@code [cellCount, keyCapacity, distinctKeys, totalAmount, revision]}.
     */
    public long[] stats() {
        requireOpen();
        return NativeBindings.stats(handle);
    }

    public int cellCount() {
        return (int) stats()[STATS_CELL_COUNT];
    }

    public int keyCapacity() {
        return (int) stats()[STATS_KEY_CAPACITY];
    }

    public int distinctKeys() {
        return (int) stats()[STATS_DISTINCT_KEYS];
    }

    public long totalAmount() {
        return stats()[STATS_TOTAL_AMOUNT];
    }

    public long revision() {
        return stats()[STATS_REVISION];
    }

    private static long[] widen(int[] values) {
        var out = new long[values.length];
        for (var i = 0; i < values.length; i++) {
            out[i] = values[i] & 0xFFFFFFFFL;
        }
        return out;
    }
}
