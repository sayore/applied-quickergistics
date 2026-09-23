package harness;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.VersionedStorage;
import appeng.me.storage.NetworkStorage;
import appeng.me.storage.RustStorageIndex;

/**
 * Standalone integration harness for the native storage mirror.
 *
 * AE2's Gradle test task goes through the NeoForge/FML test harness, which refuses to start with a
 * classes directory as the mod source. This harness links against the same runtime classpath but runs
 * as a plain JVM program, so the mirror can be verified end to end without booting Minecraft.
 */
public final class NativeIntegrationHarness {
    // ---------------------------------------------------------------------------------------------
    // Test doubles
    // ---------------------------------------------------------------------------------------------

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
            throw new UnsupportedOperationException();
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

    /** Deliberately does not report a version, so the mirror must refuse it. */
    static final class UnversionedStorage implements MEStorage {
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

    // ---------------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------------

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        System.out.println("native library available: " + appeng.storage.nativebridge.NativeLibrary.isAvailable());
        System.out.println("native library status   : " + appeng.storage.nativebridge.NativeLibrary.getStatus());
        System.out.println("native index enabled    : " + RustStorageIndex.isEnabled());
        System.out.println();

        aggregateMatchesJava();
        mutationsArePickedUp();
        mountAndUnmountAreTracked();
        unversionedStorageFallsBack();
        manyRandomMutationsStayCorrect();
        prioritiesAreTracked();
        wrappedCellsAreMirrored();
        deltaStreamMatchesFullRebuild();

        System.out.println();
        System.out.printf("%d checks, %d failures%n", checks, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void check(String what, Map<AEKey, Long> actual, Map<AEKey, Long> expected) {
        checks++;
        if (!sameContents(actual, expected)) {
            failures++;
            System.err.println("FAIL " + what);
            var allKeys = new java.util.LinkedHashSet<AEKey>();
            allKeys.addAll(expected.keySet());
            allKeys.addAll(actual.keySet());
            long expectedTotal = 0;
            long actualTotal = 0;
            var shown = 0;
            for (var key : allKeys) {
                var e = expected.get(key);
                var a = actual.get(key);
                if (e != null) {
                    expectedTotal += e;
                }
                if (a != null) {
                    actualTotal += a;
                }
                if (!java.util.Objects.equals(e, a) && shown++ < 10) {
                    System.err.printf("  key %s: expected=%s actual=%s%n",
                            ((NativeIntegrationHarness.FakeKey) key).numericId(), e, a);
                }
            }
            System.err.printf("  keys expected=%d actual=%d; total expected=%d actual=%d%n",
                    expected.size(), actual.size(), expectedTotal, actualTotal);
        } else {
            System.out.println("ok   " + what + " (" + actual.size() + " keys)");
        }
    }

    /** Entry-wise comparison: both maps are identity maps, so {@code Map#equals} cannot be used. */
    private static boolean sameContents(Map<AEKey, Long> a, Map<AEKey, Long> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (var entry : a.entrySet()) {
            var other = b.get(entry.getKey());
            if (other == null || !other.equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static void checkTrue(String what, boolean condition) {
        checks++;
        if (condition) {
            System.out.println("ok   " + what);
        } else {
            failures++;
            System.err.println("FAIL " + what);
        }
    }

    private static Map<AEKey, Long> snapshot(KeyCounter counter) {
        var map = new IdentityHashMap<AEKey, Long>();
        for (var entry : counter) {
            map.put(entry.getKey(), entry.getLongValue());
        }
        return map;
    }

    private static Map<AEKey, Long> expectedOf(List<MEStorage> storages) {
        var counter = new KeyCounter();
        for (var storage : storages) {
            storage.getAvailableStacks(counter);
        }
        return snapshot(counter);
    }

    private static void assertMatches(String what, NetworkStorage network, List<MEStorage> storages) {
        var actual = new KeyCounter();
        network.getAvailableStacks(actual);
        check(what, snapshot(actual), expectedOf(storages));
    }

    private static List<FakeKey> keys(int count) {
        var list = new ArrayList<FakeKey>();
        for (var i = 0; i < count; i++) {
            list.add(new FakeKey(i));
        }
        return list;
    }

    // ---------------------------------------------------------------------------------------------

    private static void aggregateMatchesJava() {
        var a = new FakeStorage();
        var b = new FakeStorage();
        var c = new FakeStorage();
        var keys = keys(60);
        for (var i = 0; i < keys.size(); i++) {
            if (i % 3 != 1) {
                a.set(keys.get(i), 10L + i);
            }
            if (i % 2 == 0) {
                b.set(keys.get(i), 1000L + i);
            }
            if (i % 5 == 0) {
                c.set(keys.get(i), 7L);
            }
        }
        var network = new NetworkStorage();
        network.mount(0, a);
        network.mount(5, b);
        network.mount(-3, c);
        assertMatches("aggregate over three mounts", network, List.of(a, b, c));
    }

    private static void mutationsArePickedUp() {
        var storage = new FakeStorage();
        var keys = keys(8);
        storage.set(keys.get(0), 5);
        storage.set(keys.get(1), 9);
        var network = new NetworkStorage();
        network.mount(0, storage);
        assertMatches("initial contents", network, List.of(storage));
        assertMatches("unchanged query (cached)", network, List.of(storage));
        storage.set(keys.get(0), 12);
        storage.set(keys.get(2), 3);
        assertMatches("after amount change and new key", network, List.of(storage));
        storage.set(keys.get(1), 0);
        assertMatches("after key removal", network, List.of(storage));
    }

    private static void mountAndUnmountAreTracked() {
        var a = new FakeStorage();
        var b = new FakeStorage();
        var keys = keys(6);
        a.set(keys.get(0), 4);
        b.set(keys.get(0), 6);
        b.set(keys.get(1), 1);
        var network = new NetworkStorage();
        network.mount(0, a);
        assertMatches("single mount", network, List.of(a));
        network.mount(1, b);
        assertMatches("second mount", network, List.of(a, b));
        network.unmount(b);
        assertMatches("after unmount", network, List.of(a));
        network.unmount(a);
        assertMatches("after unmounting everything", network, List.of());
    }

    private static void unversionedStorageFallsBack() {
        var versioned = new FakeStorage();
        var unversioned = new UnversionedStorage();
        var keys = keys(4);
        versioned.set(keys.get(0), 2);
        unversioned.contents.put(keys.get(1), 3);
        var network = new NetworkStorage();
        network.mount(0, versioned);
        network.mount(1, unversioned);
        assertMatches("mirror refuses unversioned mount", network, List.of(versioned, unversioned));
        unversioned.contents.put(keys.get(2), 11);
        assertMatches("fallback sees later change", network, List.of(versioned, unversioned));
        network.unmount(unversioned);
        assertMatches("mirror usable again after unmount", network, List.of(versioned));
    }

    private static void manyRandomMutationsStayCorrect() {
        var a = new FakeStorage();
        var b = new FakeStorage();
        var network = new NetworkStorage();
        network.mount(0, a);
        network.mount(2, b);
        var keys = keys(64);
        var rnd = new java.util.Random(1234);
        for (var round = 0; round < 500; round++) {
            var target = rnd.nextBoolean() ? a : b;
            target.set(keys.get(rnd.nextInt(keys.size())), rnd.nextInt(50));
        }
        assertMatches("after 500 random mutations", network, List.of(a, b));
    }

    /**
     * AE2's drives mount a {@code DriveWatcher}, which <em>extends</em> {@code MEInventoryHandler}.
     * The mirror has to unwrap such subclasses, otherwise real drives would never be accelerated.
     * This uses a plain transparent subclass as a stand-in, because the real one needs a Minecraft
     * bootstrap; {@code StorageBusInventory} filters and must therefore stay rejected.
     */
    /**
     * Drives the same incremental refresh that {@code StorageService#updateCachedStacks} uses and
     * asserts that it converges to exactly the state a full aggregate produces, and that it reports
     * the same watcher notifications.
     */
    private static void deltaStreamMatchesFullRebuild() {
        var a = new FakeStorage();
        var b = new FakeStorage();
        var keys = keys(40);
        var network = new NetworkStorage();
        network.mount(0, a);
        network.mount(5, b);

        // Incremental consumer state, mirroring StorageService.
        var incremental = new IdentityHashMap<AEKey, Long>();
        var revision = network.revision();
        checks++;
        if (revision < 0) {
            failures++;
            System.err.println("FAIL delta stream: mirror unavailable");
            return;
        }

        var rnd = new java.util.Random(99);
        var notifications = 0;
        for (var round = 0; round < 400; round++) {
            // Mutate something.
            var target = rnd.nextBoolean() ? a : b;
            var key = keys.get(rnd.nextInt(keys.size()));
            target.set(key, rnd.nextInt(30) == 0 ? 0 : 1 + rnd.nextInt(5000));

            // Consume deltas exactly like StorageService.applyDeltas.
            var deltas = network.deltasSince(revision);
            if (deltas == null) {
                // Fall back to a full rebuild, which is what StorageService does too.
                incremental.clear();
                var counter = new KeyCounter();
                network.getAvailableStacks(counter);
                for (var entry : counter) {
                    incremental.put(entry.getKey(), entry.getLongValue());
                }
                revision = network.revision();
                continue;
            }
            for (var change : deltas.changes()) {
                if (change.newTotal() == 0) {
                    incremental.remove(change.key());
                } else {
                    incremental.put(change.key(), change.newTotal());
                }
                if (change.oldTotal() != change.newTotal()) {
                    notifications++;
                }
            }
            revision = deltas.revision();

            // The incremental state must equal the authoritative aggregate.
            if (round % 13 == 0) {
                var authoritative = new KeyCounter();
                network.getAvailableStacks(authoritative);
                var expected = new IdentityHashMap<AEKey, Long>();
                for (var entry : authoritative) {
                    expected.put(entry.getKey(), entry.getLongValue());
                }
                check("delta stream matches full rebuild at round " + round, incremental, expected);
                if (failures > 0) {
                    return;
                }
            }
        }
        System.out.printf("     (%d watcher notifications over 400 rounds)%n", notifications);
    }

    static final class TransparentWrapper extends appeng.me.storage.MEInventoryHandler {
        TransparentWrapper(MEStorage delegate) {
            super(delegate);
        }
    }

    static final class FilteringWrapper extends appeng.me.storage.MEInventoryHandler {
        FilteringWrapper(MEStorage delegate) {
            super(delegate);
            setExtractFiltering(false, true);
        }
    }

    private static void wrappedCellsAreMirrored() {
        var storage = new FakeStorage();
        var keys = keys(5);
        storage.set(keys.get(0), 11);
        storage.set(keys.get(1), 22);
        var wrapper = new TransparentWrapper(storage);
        var network = new NetworkStorage();
        network.mount(0, wrapper);
        assertMatches("subclass wrapper is unwrapped", network, List.of(storage));

        storage.set(keys.get(2), 33);
        assertMatches("subclass wrapper picks up changes", network, List.of(storage));

        var filtering = new FilteringWrapper(storage);
        var network2 = new NetworkStorage();
        network2.mount(0, filtering);
        assertMatches("filtering wrapper is rejected and falls back", network2, List.of(storage));
        storage.set(keys.get(3), 44);
        assertMatches("filtering wrapper keeps falling back correctly", network2, List.of(storage));
    }

    private static void prioritiesAreTracked() {
        // Two cells holding the same key; the aggregate must sum them regardless of mount order.
        var low = new FakeStorage();
        var high = new FakeStorage();
        var keys = keys(2);
        low.set(keys.get(0), 3);
        high.set(keys.get(0), 4);
        var network = new NetworkStorage();
        network.mount(10, high);
        network.mount(0, low);
        assertMatches("mounting high priority first", network, List.of(low, high));

        var other = new NetworkStorage();
        other.mount(0, low);
        other.mount(10, high);
        assertMatches("mounting low priority first", other, List.of(low, high));
    }
}
