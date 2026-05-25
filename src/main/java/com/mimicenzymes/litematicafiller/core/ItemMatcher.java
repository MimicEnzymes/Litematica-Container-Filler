package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.List;

public class ItemMatcher {

    public static boolean isSameItem(ItemStack current, ItemStack required) {
        if (current.isEmpty() || required.isEmpty()) return false;
        if (ItemStack.isSameItemSameComponents(current, required)) return true;
        return Configs.MATCH_SHULKER_BOXES_BY_CONTENT.getBooleanValue()
                && isShulkerBox(current)
                && isShulkerBox(required)
                && hasSameContainerContents(current, required);
    }

    public static int matchingHash(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        if (Configs.MATCH_SHULKER_BOXES_BY_CONTENT.getBooleanValue() && isShulkerBox(stack)) {
            int hash = ShulkerBoxBlock.class.hashCode();
            for (ItemStack inner : getContainerSlots(stack)) {
                hash = 31 * hash + matchingHash(inner);
                hash = 31 * hash + inner.getCount();
            }
            return hash;
        }
        return ItemStack.hashItemAndComponents(stack);
    }

    private static boolean hasSameContainerContents(ItemStack current, ItemStack required) {
        List<ItemStack> currentSlots = getContainerSlots(current);
        List<ItemStack> requiredSlots = getContainerSlots(required);
        if (currentSlots.size() != requiredSlots.size()) return false;

        for (int i = 0; i < currentSlots.size(); i++) {
            ItemStack currentSlot = currentSlots.get(i);
            ItemStack requiredSlot = requiredSlots.get(i);
            if (currentSlot.isEmpty() != requiredSlot.isEmpty()) return false;
            if (currentSlot.isEmpty()) continue;
            if (currentSlot.getCount() != requiredSlot.getCount()) return false;
            if (!isSameItem(currentSlot, requiredSlot)) return false;
        }
        return true;
    }

    private static List<ItemStack> getContainerSlots(ItemStack stack) {
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container == null) return List.of();

        NonNullList<ItemStack> copiedSlots = NonNullList.withSize(27, ItemStack.EMPTY);
        container.copyInto(copiedSlots);

        List<ItemStack> slots = new ArrayList<>(copiedSlots.size());
        copiedSlots.forEach(slot -> {
            ItemStack copy = slot.copy();
            copy.setCount(slot.getCount());
            slots.add(copy);
        });
        return slots;
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }
}
