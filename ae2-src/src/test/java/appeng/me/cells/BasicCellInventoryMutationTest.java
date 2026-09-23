/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package appeng.me.cells;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.core.definitions.AEItems;
import appeng.me.helpers.BaseActionSource;
import appeng.util.BootstrapMinecraft;

@BootstrapMinecraft
@ExtendWith(EphemeralTestServerProvider.class)
class BasicCellInventoryMutationTest {
    private final BaseActionSource source = new BaseActionSource();

    BasicCellInventoryMutationTest(MinecraftServer server) {
    }

    @Test
    void maintainsCountsAcrossInsertExtractAndPersistence() {
        var stack = AEItems.ITEM_CELL_64K.stack();
        var saves = new AtomicInteger();
        var inventory = BasicCellInventory.createInventory(stack, saves::incrementAndGet);
        assertThat(inventory).isNotNull();
        var stone = AEItemKey.of(new ItemStack(Items.STONE));
        var dirt = AEItemKey.of(new ItemStack(Items.DIRT));

        assertThat(inventory.insert(stone, 80, Actionable.MODULATE, source)).isEqualTo(80);
        assertThat(inventory.insert(dirt, 30, Actionable.MODULATE, source)).isEqualTo(30);
        assertThat(inventory.getStoredItemCount()).isEqualTo(110);
        assertThat(inventory.getStoredItemTypes()).isEqualTo(2);
        assertThat(inventory.storageVersion()).isEqualTo(2);
        assertThat(saves).hasValue(2);

        assertThat(inventory.insert(stone, 5, Actionable.SIMULATE, source)).isEqualTo(5);
        assertThat(inventory.extract(stone, 20, Actionable.SIMULATE, source)).isEqualTo(20);
        assertThat(inventory.getStoredItemCount()).isEqualTo(110);
        assertThat(inventory.storageVersion()).isEqualTo(2);
        assertThat(saves).hasValue(2);

        assertThat(inventory.insert(stone, 5, Actionable.MODULATE, source)).isEqualTo(5);
        assertThat(inventory.extract(stone, 20, Actionable.MODULATE, source)).isEqualTo(20);
        assertThat(inventory.extract(dirt, 100, Actionable.MODULATE, source)).isEqualTo(30);
        assertThat(inventory.getStoredItemCount()).isEqualTo(65);
        assertThat(inventory.getStoredItemTypes()).isEqualTo(1);
        assertThat(inventory.storageVersion()).isEqualTo(5);
        assertThat(saves).hasValue(5);

        inventory.persist();
        var reopened = BasicCellInventory.createInventory(stack, null);
        assertThat(reopened).isNotNull();
        assertThat(reopened.getStoredItemCount()).isEqualTo(65);
        assertThat(reopened.getStoredItemTypes()).isEqualTo(1);
        assertThat(reopened.insert(stone, 1, Actionable.MODULATE, source)).isEqualTo(1);
        assertThat(reopened.getStoredItemCount()).isEqualTo(66);
    }

    @Test
    void stillRejectsNonEmptyStorageCells() {
        var nestedStack = AEItems.ITEM_CELL_1K.stack();
        var nested = BasicCellInventory.createInventory(nestedStack, null);
        assertThat(nested).isNotNull();
        assertThat(nested.insert(AEItemKey.of(new ItemStack(Items.STONE)), 1, Actionable.MODULATE, source))
                .isEqualTo(1);

        var outer = BasicCellInventory.createInventory(AEItems.ITEM_CELL_64K.stack(), null);
        assertThat(outer).isNotNull();
        assertThat(outer.insert(AEItemKey.of(nestedStack), 1, Actionable.MODULATE, source)).isZero();
        assertThat(outer.getStoredItemCount()).isZero();
        assertThat(outer.storageVersion()).isZero();
    }
}
