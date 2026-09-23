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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.VersionedStorage;

/**
 * Verifies that the optional native storage mirror produces exactly the same aggregate as AE2's Java implementation,
 * and that it stays in sync across mutations, mounts and unmounts.
 * <p>
 * No Minecraft bootstrap is required: only the aggregate read path is exercised.
 */
class RustStorageIndexTest {
    /**
     * A minimal, Minecraft-free stand-in for a storage cell. AE2's real cells are {@link VersionedStorage}; this one
     * tracks a version counter the same way.
     */
    static class FakeStorage implements MEStorage, VersionedStorage {
        final Object2LongMap<AEKey> contents = new Object2LongOpenHashMap<>();
        long version;

        @Override
        public long storageVersion() {
            return version;
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            for (var entry : contents.object2LongEntrySet()) {
                out.add(entry.getKey(), entry.getLongValue());
            }
        }

        void set(AEKey key, long amount) {
            if (amount == 0) {
                contents.removeLong(key);
            } else {
                contents.put(key, amount);
            }
            version++;
        }

        @Override
        public net.minecraft.network.chat.Component getDescription() {
            throw new UnsupportedOperationException();
        }
    }

    /** A storage that deliberately does not report a version. */
    static class UnversionedStorage implements MEStorage {
        final Object2LongMap<AEKey> contents = new Object2LongOpenHashMap<>();

        @Override
        public void getAvailableStacks(KeyCounter out) {
            for (var entry : contents.object2LongEntrySet()) {
                out.add(entry.getKey(), entry.getLongValue());
            }
        }

        @Override
        public net.minecraft.network.chat.Component getDescription() {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * A stand-in key with the identity semantics of AE2's canonical keys: two lookups of the same resource return the
     * same instance, and {@code getPrimaryKey()} is the key itself. Only the methods the storage path actually uses are
     * implemented; the rest throw, because a test that reaches them would be testing something else.
     */
    static final class FakeKey extends AEKey {
        private final int id;

        FakeKey(int id) {
            this.id = id;
        }

        int numericId() {
            return id;
        }

        @Override
        public Object getPrimaryKey() {
            return this;
        }

        @Override
        public boolean equals(Object o) {
            return this == o;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }

        @Override
        public AEKeyType getType() {
            throw new UnsupportedOperationException("not needed by the storage aggregate path");
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public void toTag(net.minecraft.world.level.storage.ValueOutput output) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected net.minecraft.network.chat.Component computeDisplayName() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasComponents() {
            return false;
        }

        @Override
        public net.minecraft.resources.Identifier getId() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeToPacket(net.minecraft.network.RegistryFriendlyByteBuf data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addDrops(long amount, List<net.minecraft.world.item.ItemStack> drops,
                net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
            throw new UnsupportedOperationException();
        }
    }

    private static FakeKey key(int id) {
        return new FakeKey(id);
    }

    /** Compares by key identity, which is how AE2 keys behave. */
    private static Map<AEKey, Long> toMap(KeyCounter counter) {
        var map = new IdentityHashMap<AEKey, Long>();
        for (var entry : counter) {
            map.put(entry.getKey(), entry.getLongValue());
        }
        return map;
    }

    private static List<FakeKey> keys(int count) {
        var list = new ArrayList<FakeKey>();
        for (var i = 0; i < count; i++) {
            list.add(key(i));
        }
        return list;
    }

    /**
     * The baseline the mirror has to match: merging every mounted storage's contents.
     */
    private static Map<AEKey, Long> expected(List<MEStorage> storages) {
        var counter = new KeyCounter();
        for (var storage : storages) {
            storage.getAvailableStacks(counter);
        }
        return toMap(counter);
    }

    private static void assertMatchesReference(NetworkStorage network, List<MEStorage> storages) {
        var actual = new KeyCounter();
        network.getAvailableStacks(actual);
        var actualMap = toMap(actual);
        var expectedMap = expected(storages);
        // Both are identity maps, so Map#equals cannot be used; compare entry-wise instead.
        assertThat(actualMap).hasSameSizeAs(expectedMap);
        for (var entry : expectedMap.entrySet()) {
            assertThat(actualMap)
                    .as("amount for %s", entry.getKey())
                    .containsEntry(entry.getKey(), entry.getValue());
        }
    }

    @Test
    void aggregateMatchesJavaImplementation() {
        var storageA = new FakeStorage();
        var storageB = new FakeStorage();
        var storageC = new FakeStorage();
        var keys = keys(40);
        for (var i = 0; i < keys.size(); i++) {
            if (i % 3 != 1) {
                storageA.set(keys.get(i), 10L + i);
            }
            if (i % 2 == 0) {
                storageB.set(keys.get(i), 1000L + i);
            }
            if (i % 5 == 0) {
                storageC.set(keys.get(i), 7L);
            }
        }

        var network = new NetworkStorage();
        network.mount(0, storageA);
        network.mount(5, storageB);
        network.mount(-3, storageC);

        assertMatchesReference(network, List.of(storageA, storageB, storageC));
    }

    @Test
    void mutationsArePickedUp() {
        var storage = new FakeStorage();
        var keys = keys(8);
        storage.set(keys.get(0), 5);
        storage.set(keys.get(1), 9);

        var network = new NetworkStorage();
        network.mount(0, storage);
        assertMatchesReference(network, List.of(storage));

        // A second query without any change must return the same (cached) aggregate.
        assertMatchesReference(network, List.of(storage));

        // Now change the contents and make sure the mirror notices it.
        storage.set(keys.get(0), 12);
        storage.set(keys.get(2), 3);
        assertMatchesReference(network, List.of(storage));

        // And remove a key entirely.
        storage.set(keys.get(1), 0);
        assertMatchesReference(network, List.of(storage));
    }

    @Test
    void mountAndUnmountAreTracked() {
        var storageA = new FakeStorage();
        var storageB = new FakeStorage();
        var keys = keys(6);
        storageA.set(keys.get(0), 4);
        storageB.set(keys.get(0), 6);
        storageB.set(keys.get(1), 1);

        var network = new NetworkStorage();
        network.mount(0, storageA);
        assertMatchesReference(network, List.of(storageA));

        network.mount(1, storageB);
        assertMatchesReference(network, List.of(storageA, storageB));

        network.unmount(storageB);
        assertMatchesReference(network, List.of(storageA));

        network.unmount(storageA);
        assertMatchesReference(network, List.of());
    }

    @Test
    void unversionedStorageIsReadInJavaWhileTheRestStaysMirrored() {
        var versioned = new FakeStorage();
        var unversioned = new UnversionedStorage();
        var keys = keys(4);
        versioned.set(keys.get(0), 2);
        unversioned.contents.put(keys.get(1), 3);

        var network = new NetworkStorage();
        network.mount(0, versioned);
        network.mount(1, unversioned);

        // The mirror cannot track the unversioned storage, so that one mount has to be read in Java.
        // The mirrored mount must not be dragged onto the Java path with it: the mirror has to stay
        // available, with the uncovered mount listed separately so the caller can add it.
        assertMatchesReference(network, List.of(versioned, unversioned));
        assertThat(network.getSharedAvailableStacks())
                .as("the mirrored mount stays available despite the unversioned mount")
                .isNotNull();
        // Querying twice must not count the uncovered mount twice. This is why the uncovered mounts
        // are gathered into a caller-owned counter instead of the mirror's shared one.
        var twice = new KeyCounter();
        network.getAvailableStacks(twice);
        network.getAvailableStacks(twice);
        assertThat(toMap(twice))
                .as("repeated queries must not accumulate the uncovered mount")
                .containsEntry(keys.get(1), 3L);

        // A change to the uncovered mount has to show up even though it produces no delta.
        unversioned.contents.put(keys.get(2), 11);
        assertMatchesReference(network, List.of(versioned, unversioned));

        // A change to the covered mount has to show up as well while the uncovered one is present.
        // The native side only advances its revision when a total actually changes, so bumping the
        // version without changing the content must not leave the mirror reading a stale total.
        versioned.set(keys.get(0), 2);
        assertMatchesReference(network, List.of(versioned, unversioned));
        versioned.set(keys.get(0), 7);
        assertMatchesReference(network, List.of(versioned, unversioned));

        // The incremental path would describe only the mirrored mounts, so it must refuse to serve.
        assertThat(network.deltasSince(network.revision()))
                .as("deltas must not omit an uncovered mount's changes")
                .isNull();
    }

    /**
     * Partial coverage means an excluded mount must not contribute to the mirrored total, otherwise the caller would
     * count it twice. This is exactly what a filtered storage bus causes.
     */
    @Test
    void excludedMountIsAddedExactlyOnce() {
        var versioned = new FakeStorage();
        var filtered = new MEInventoryHandler(new FakeStorage());
        filtered.setPartitionList(new appeng.util.prioritylist.DefaultPriorityList());
        filtered.setExtractFiltering(true, true);

        var keys = keys(3);
        versioned.set(keys.get(0), 5);
        filtered.insert(keys.get(1), 13, Actionable.MODULATE, null);

        var network = new NetworkStorage();
        network.mount(0, versioned);
        network.mount(3, filtered);

        for (var round = 0; round < 3; round++) {
            assertMatchesReference(network, List.of(versioned, filtered));
        }
    }

    @Test
    void aggregateAfterManyMutationsStaysCorrect() {
        var storageA = new FakeStorage();
        var storageB = new FakeStorage();
        var network = new NetworkStorage();
        network.mount(0, storageA);
        network.mount(2, storageB);

        var keys = keys(64);
        var rnd = new java.util.Random(1234);
        for (var round = 0; round < 200; round++) {
            var target = rnd.nextBoolean() ? storageA : storageB;
            var k = keys.get(rnd.nextInt(keys.size()));
            var amount = rnd.nextInt(50);
            target.set(k, amount);
            if (round % 17 == 0) {
                assertMatchesReference(network, List.of(storageA, storageB));
            }
        }
        assertMatchesReference(network, List.of(storageA, storageB));
    }
}
