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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The mirror identifies keys by an interned id, so what the interner considers "the same key" decides whether the
 * native index and the Java counter agree.
 */
class DenseKeyInternerTest {
    /**
     * AE2 keys are value objects: {@code AEItemKey.of(stack)} returns a fresh instance per call, while
     * {@code getPrimaryKey()} returns the shared item. Two instances of one resource have to share one id, or the
     * native index sums the resource while the Java counter keeps the variants apart.
     */
    private record Key(Object primaryKey, int variant) {
    }

    private static DenseKeyInterner<Key> interner() {
        return new DenseKeyInterner<>(Key::primaryKey);
    }

    @Test
    void variantsOfOnePrimaryKeyShareAnId() {
        var interner = interner();
        var item = new Object();
        var first = new Key(item, 1);
        var second = new Key(item, 2);

        var firstId = interner.intern(first);
        var secondId = interner.intern(second);

        assertThat(secondId).as("both variants describe the same resource").isEqualTo(firstId);
        assertThat(interner.size()).isEqualTo(1);
    }

    @Test
    void internCanonicalHandsBackOneInstanceForEveryVariant() {
        var interner = interner();
        var item = new Object();
        var first = new Key(item, 1);
        var second = new Key(item, 2);

        var canonical = interner.internCanonical(first);
        assertThat(canonical).isSameAs(first);
        assertThat(interner.internCanonical(second)).as("the canonical instance must not change").isSameAs(first);
        assertThat(interner.keyOf(interner.intern(second))).isSameAs(first);
    }

    @Test
    void distinctPrimaryKeysStayDistinct() {
        var interner = interner();
        var first = new Key(new Object(), 1);
        var second = new Key(new Object(), 1);

        assertThat(interner.intern(first)).isNotEqualTo(interner.intern(second));
        assertThat(interner.size()).isEqualTo(2);
    }

    @Test
    void lookupByTheOriginalInstanceStaysStable() {
        var interner = interner();
        var item = new Object();
        var first = new Key(item, 1);
        var second = new Key(item, 2);

        var id = interner.intern(first);
        interner.intern(second);
        assertThat(interner.idOf(first)).isEqualTo(id);
        assertThat(interner.idOf(second)).isEqualTo(id);
        assertThat(interner.idOf(new Key(item, 3))).as("an unseen instance has no id yet").isEqualTo(-1);
        assertThat(interner.intern(new Key(item, 3))).isEqualTo(id);
        assertThat(interner.size()).isEqualTo(1);
    }
}
