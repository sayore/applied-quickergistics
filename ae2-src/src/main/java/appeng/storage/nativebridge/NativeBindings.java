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

/**
 * Raw JNI entry points for the {@code ae2store} Rust library.
 * <p>
 * Every method name maps 1:1 to the exported symbol
 * {@code Java_appeng_storage_nativebridge_NativeBindings_<name>} in {@code rust/ae2store-jni}.
 * Keeping the bindings in their own class (rather than making them private natives of
 * {@link NativeNetworkIndex}) means the Java name and the symbol name are identical, which removes a
 * whole class of {@code UnsatisfiedLinkError} mistakes.
 * <p>
 * Do not call this class directly from outside the bridge package; use {@link NativeNetworkIndex}.
 */
final class NativeBindings {
    private NativeBindings() {
    }

    static native long create();

    static native void destroy(long handle);

    static native void ensureKeyCapacity(long handle, int keyCapacity);

    static native int addCell(long handle, int keyCapacity, int priority);

    static native void removeCell(long handle, int cellId);

    static native void setCellPriority(long handle, int cellId, int priority);

    static native void setCellMaxPerKey(long handle, int cellId, long maxPerKey);

    static native void setCellWhitelist(long handle, int cellId, long[] whitelist);

    static native void pushCell(long handle, int cellId, int keyCapacity, long[] ids, long[] amounts);

    static native void applyCellDelta(long handle, int cellId, int keyId, long delta);

    static native long[] available(long handle, long[] filter);

    static native long totalOf(long handle, int keyId);

    static native long extract(long handle, int keyId, long amount, boolean simulate);

    static native long insert(long handle, int keyId, long amount, boolean simulate);

    static native boolean isPreferred(long handle, int keyId);

    static native void compact(long handle);

    static native long[] stats(long handle);

    static native long[] deltasSince(long handle, long sinceRevision);

    static native long deltaRevision(long handle);




}
