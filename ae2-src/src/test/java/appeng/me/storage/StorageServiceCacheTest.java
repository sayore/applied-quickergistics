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

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.me.service.StorageService;

/**
 * The cached inventory is what every terminal, monitor, level emitter and crafting calculation reads. It is maintained
 * incrementally from the mirror's change log, patching the mirror's own counter in place, so a mistake here corrupts
 * the counter itself rather than producing a wrong delta.
 */
class StorageServiceCacheTest {
    private static final RustStorageIndexTest.FakeKey KEY0 = new RustStorageIndexTest.FakeKey(0);
    private static final RustStorageIndexTest.FakeKey KEY1 = new RustStorageIndexTest.FakeKey(1);
    private static final RustStorageIndexTest.FakeKey KEY2 = new RustStorageIndexTest.FakeKey(2);
    private static final RustStorageIndexTest.FakeKey KEY3 = new RustStorageIndexTest.FakeKey(3);
    private static final RustStorageIndexTest.FakeKey KEY4 = new RustStorageIndexTest.FakeKey(4);
    private static final RustStorageIndexTest.FakeKey KEY5 = new RustStorageIndexTest.FakeKey(5);
    private static final List<RustStorageIndexTest.FakeKey> KEYS = List.of(KEY0, KEY1, KEY2, KEY3, KEY4, KEY5);

    /** Reference: merge the mounts' contents from scratch, as the Java path would. */
    private static KeyCounter reference(List<MEStorage> mounts) {
        var out = new KeyCounter();
        for (var mount : mounts) {
            mount.getAvailableStacks(out);
        }
        return out;
    }

    private static String describe(KeyCounter counter) {
        var out = new StringBuilder();
        for (var entry : counter) {
            out.append(entry.getKey()).append('=').append(entry.getLongValue()).append(' ');
        }
        return out.toString();
    }

    private static void assertMatches(KeyCounter actual, KeyCounter expected, String when) {
        // Identity maps, so compare entry-wise rather than with Map#equals.
        var actualMap = new java.util.IdentityHashMap<AEKey, Long>();
        for (var entry : actual) {
            actualMap.put(entry.getKey(), entry.getLongValue());
        }
        var expectedMap = new java.util.IdentityHashMap<AEKey, Long>();
        for (var entry : expected) {
            expectedMap.put(entry.getKey(), entry.getLongValue());
        }
        assertThat(actualMap).as("%s: size", when).hasSameSizeAs(expectedMap);
        for (var entry : expectedMap.entrySet()) {
            assertThat(actualMap)
                    .as("%s: amount for %s", when, entry.getKey())
                    .containsEntry(entry.getKey(), entry.getValue());
        }
    }

    private static void injectStorage(StorageService service, NetworkStorage storage) {
        try {
            Field field = StorageService.class.getDeclaredField("storage");
            field.setAccessible(true);
            field.set(service, storage);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Drives the cached inventory through the incremental path and compares it against a full merge after every
     * mutation. The first read builds the cache; every later one takes the delta path, which patches the mirror's
     * counter in place.
     */
    @Test
    void cachedInventoryStaysCorrectAcrossIncrementalRefreshes() {
        var keys = KEYS;
        var versioned = new RustStorageIndexTest.FakeStorage();
        var unversioned = new RustStorageIndexTest.UnversionedStorage();
        versioned.set(keys.get(0), 10);
        versioned.set(keys.get(1), 20);
        unversioned.contents.put(keys.get(2), 30);

        var mounts = List.<MEStorage>of(versioned, unversioned);
        var storage = new NetworkStorage();
        storage.mount(0, versioned);
        storage.mount(1, unversioned);

        var service = new StorageService();
        injectStorage(service, storage);

        var rnd = new java.util.Random(4242);
        for (var round = 0; round < 300; round++) {
            var key = keys.get(rnd.nextInt(keys.size()));
            var amount = rnd.nextInt(80);
            switch (rnd.nextInt(6)) {
                case 0, 1, 2 -> versioned.set(key, amount);
                case 3 -> unversioned.contents.put(key, (long) amount);
                case 4 -> unversioned.contents.removeLong(key);
                default -> versioned.set(key, 0);
            }

            if (round < 4) {
            }
            if (round < 4) {
            }
            // A tick passes, so the cache is invalidated and rebuilt from whatever changed. This is
            // what StorageService.onServerEndTick does when nothing is watching.
            service.invalidateCache();
            assertMatches(service.getCachedInventory(), reference(mounts), "round " + round);

            // Reading again without a change must not disturb the cache.
            assertMatches(service.getCachedInventory(), reference(mounts), "round " + round + " reread");
        }
    }

    /**
     * An unmirrored mount can change without the mirror's revision moving, because the mirror does not observe it. The
     * cache therefore has to keep rebuilding while such a mount is present, and may only start trusting the revision
     * once it is gone - otherwise a storage bus's contents would stop updating in terminals whenever the rest of the
     * network was idle.
     */
    @Test
    void unmirroredMountsKeepTheCacheHonestWhileTheMirrorIsIdle() {
        var versioned = new RustStorageIndexTest.FakeStorage();
        versioned.set(KEY0, 10);
        var unversioned = new RustStorageIndexTest.UnversionedStorage();
        unversioned.contents.put(KEY1, 20);

        var storage = new NetworkStorage();
        storage.mount(0, versioned);
        storage.mount(1, unversioned);
        var service = new StorageService();
        injectStorage(service, storage);

        // The unmirrored mount changes while the mirror's revision stays put.
        service.invalidateCache();
        assertThat(service.getCachedInventory().get(KEY1)).isEqualTo(20);
        var revision = storage.revision();

        unversioned.contents.put(KEY1, 99);
        unversioned.contents.put(KEY2, 5);
        service.invalidateCache();
        assertThat(storage.revision())
                .as("the mirror cannot see this change")
                .isEqualTo(revision);
        var refreshed = service.getCachedInventory();
        assertThat(refreshed.get(KEY1)).as("an unmirrored change must still reach the cache").isEqualTo(99);
        assertThat(refreshed.get(KEY2)).isEqualTo(5);
        assertThat(refreshed.get(KEY0)).as("the mirrored part must survive the merge").isEqualTo(10);
        assertMatches(refreshed, reference(List.of(versioned, unversioned)), "after the unmirrored change");

        // Removing it returns the cache to the purely mirrored case, where the revision is trusted.
        storage.unmount(unversioned);
        service.invalidateCache();
        var afterUnmount = service.getCachedInventory();
        assertThat(afterUnmount.get(KEY0)).isEqualTo(10);
        assertThat(afterUnmount.get(KEY1)).as("the unmirrored mount is gone").isZero();
        assertThat(afterUnmount.get(KEY2)).isZero();
        assertMatches(afterUnmount, reference(List.of(versioned)), "after unmounting the unmirrored mount");
    }

    /**
     * An untouched network must not rebuild or walk anything: the cache is already correct and the mirror's revision
     * has not moved.
     */
    @Test
    void idleReadsReturnTheSameCacheWithoutChanges() {
        var key = KEY0;
        var versioned = new RustStorageIndexTest.FakeStorage();
        versioned.set(key, 5);
        var storage = new NetworkStorage();
        storage.mount(0, versioned);

        var service = new StorageService();
        injectStorage(service, storage);

        var first = service.getCachedInventory();
        assertThat(first.get(key)).isEqualTo(5);
        var revision = storage.revision();
        for (var i = 0; i < 100; i++) {
            assertThat(service.getCachedInventory()).isSameAs(first);
        }
        assertThat(storage.revision()).as("reads must not mutate the network").isEqualTo(revision);
    }
}
