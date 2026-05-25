package com.mimicenzymes.litematicafiller.dependency;

import net.minecraft.item.ItemStack;

public interface IShulkerExtractor {

    boolean requestOpenShulker(int playerSlotIndex);

    default boolean requestOpenShulkerFromClick(ItemStack stack, int playerSlotIndex) {
        return requestOpenShulker(playerSlotIndex);
    }
}
