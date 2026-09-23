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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * The native index owns memory outside the Java heap. A network is discarded every time its grid empties, which happens
 * constantly in play, and nothing in the grid lifecycle closes the mirror, so the handle has to be released when the
 * mirror becomes unreachable.
 */
class RustStorageIndexLifecycleTest {
    @Test
    void explicitEnableStillRequiresAWorkingNativeBackend() {
        assertThat(RustStorageIndex.resolveEnabled(null, () -> true)).isTrue();
        assertThat(RustStorageIndex.resolveEnabled("true", () -> true)).isTrue();
        assertThat(RustStorageIndex.resolveEnabled("true", () -> false)).isFalse();

        var probes = new AtomicInteger();
        assertThat(RustStorageIndex.resolveEnabled("false", () -> {
            probes.incrementAndGet();
            return true;
        })).isFalse();
        assertThat(probes.get()).as("an explicit disable must not load the native library").isZero();
    }

    @Test
    void closeReleasesTheNativeIndex() {
        var mirror = RustStorageIndex.createIfAvailable();
        assumeTrue(mirror != null, "native library not available");

        assertThat(mirror.isOpen()).isTrue();
        mirror.close();
        assertThat(mirror.isOpen()).as("close must release the handle").isFalse();
        // Closing twice must not free the handle twice.
        mirror.close();
        assertThat(mirror.isOpen()).isFalse();
    }

    /**
     * A mirror that is dropped without being closed still has to release its handle. Grids are replaced as networks are
     * rebuilt, and there is no hook that knows a {@code NetworkStorage} is done with.
     */
    @Test
    void droppedMirrorIsReleasedByTheCleaner() throws InterruptedException {
        var created = RustStorageIndex.createIfAvailable();
        assumeTrue(created != null, "native library not available");

        // Hold only the native index, which outlives the mirror in this test so it can be observed.
        var nativeIndex = indexOf(created);
        assertThat(nativeIndex.isOpen()).isTrue();

        created = null;

        // The cleaner runs on its own thread once the mirror is unreachable. Give the collector and it a chance.
        var deadline = System.nanoTime() + 10_000_000_000L;
        while (nativeIndex.isOpen() && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(50);
        }

        assertThat(nativeIndex.isOpen())
                .as("a dropped mirror must not keep its native index alive")
                .isFalse();
    }

    private static appeng.storage.nativebridge.NativeNetworkIndex indexOf(RustStorageIndex mirror) {
        try {
            var field = RustStorageIndex.class.getDeclaredField("index");
            field.setAccessible(true);
            return (appeng.storage.nativebridge.NativeNetworkIndex) field.get(mirror);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
