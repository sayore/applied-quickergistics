/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package appeng.storage.nativebridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

class NativeNetworkIndexTest {
    @Test
    void packedPushReadsOnlyThePopulatedPrefix() {
        assumeTrue(NativeLibrary.isAvailable());

        try (var index = NativeNetworkIndex.create()) {
            var cell = index.addCell(4, 0);
            var scratch = new long[] { 0, 5, 2, 7, 3, 99 };

            index.pushCell(cell, 4, scratch, 2);
            assertThat(index.available()).containsExactly(0, 5, 2, 7);
            assertThat(index.totalOf(3)).isZero();

            scratch[0] = 2;
            scratch[1] = 3;
            index.pushCell(cell, 4, scratch, 1);
            assertThat(index.available()).containsExactly(2, 3);

            index.pushCell(cell, 4, scratch, 0);
            assertThat(index.available()).isEmpty();

            var before = index.deltaRevision();
            index.pushCell(cell, 4, new long[] { 1, 9 }, 1);
            var deltas = index.deltaBatchSince(before);
            assertThat(deltas).isNotNull();
            assertThat(deltas.size()).isEqualTo(1);
            assertThat(deltas.keyIdAt(0)).isEqualTo(1);
            assertThat(deltas.oldTotalAt(0)).isZero();
            assertThat(deltas.newTotalAt(0)).isEqualTo(9);
            assertThat(deltas.cellIdAt(0)).isEqualTo(cell);
            assertThat(deltas.revision()).isEqualTo(index.deltaRevision());

            assertThatThrownBy(() -> index.pushCell(cell, 4, scratch, 4))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
