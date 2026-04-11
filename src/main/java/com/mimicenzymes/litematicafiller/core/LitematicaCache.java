package com.mimicenzymes.litematicafiller.core;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

public class LitematicaCache
{
    private static final Map<BlockPos, Map<Integer, ItemStack>> CACHE = new HashMap<>();
    public static void clear()
    {
        CACHE.clear();
    }
    public static void put(BlockPos pos, Map<Integer, ItemStack> items)
    {
        CACHE.put(pos, items);
    }
    public static Map<Integer, ItemStack> get(BlockPos pos)
    {
        return CACHE.get(pos);
    }
}