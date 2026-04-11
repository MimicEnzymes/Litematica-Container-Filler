package com.mimicenzymes.litematicafiller.core;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

public class LitematicaContainerReader {
    public static BlockPos[] getDoubleContainerHalves(net.minecraft.world.level.Level world, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof ChestBlock) {
            ChestType type = state.getValue(ChestBlock.TYPE);
            if (type != ChestType.SINGLE) {
                Direction facing = state.getValue(ChestBlock.FACING);
                Direction otherHalfDir = (type == ChestType.LEFT) ? facing.getClockWise() : facing.getCounterClockWise();
                BlockPos rightPos = (type == ChestType.RIGHT) ? pos : pos.relative(otherHalfDir);
                BlockPos leftPos = (type == ChestType.LEFT) ? pos : pos.relative(otherHalfDir);
                return new BlockPos[]{rightPos, leftPos};
            }
        } else if (state.is(net.minecraft.world.level.block.Blocks.BARREL)) {
            // 木桶的底部等于它的朝向的反方向
            Direction facing = state.getValue(net.minecraft.world.level.block.BarrelBlock.FACING);
            Direction bottomDir = facing.getOpposite();
            BlockPos pos2 = pos.relative(bottomDir);
            BlockState state2 = world.getBlockState(pos2);

            // 检查与之底部相连的方块是否也是木桶，并且它的朝向刚好和当前木桶相反
            if (state2.is(net.minecraft.world.level.block.Blocks.BARREL) && state2.getValue(net.minecraft.world.level.block.BarrelBlock.FACING) == facing.getOpposite()) {
                // 找到相连的大木桶，通过坐标比较保证主次顺序永远一致，避免两半的物品槽位反转
                if (pos.compareTo(pos2) < 0) {
                    return new BlockPos[]{pos, pos2};
                } else {
                    return new BlockPos[]{pos2, pos};
                }
            }
        }
        return null;
    }

    public static Map<Integer, ItemStack> getRequiredItems(BlockPos worldPos, HolderLookup.Provider registries) {
        Map<Integer, ItemStack> items = new HashMap<>();
        var schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) return items;

        BlockState state = schematicWorld.getBlockState(worldPos);
        BlockPos[] halves = getDoubleContainerHalves(schematicWorld, worldPos, state);

        if (halves != null) {
            Map<Integer, ItemStack> rightHalf = getSingleContainerItems(schematicWorld, halves[0], registries);
            Map<Integer, ItemStack> leftHalf = getSingleContainerItems(schematicWorld, halves[1], registries);

            items.putAll(rightHalf);
            leftHalf.forEach((slot, stack) -> items.put(slot + 27, stack));
            return items;
        }

        return getSingleContainerItems(schematicWorld, worldPos, registries);
    }

    private static Map<Integer, ItemStack> getSingleContainerItems(net.minecraft.world.level.Level schematicWorld, BlockPos pos, HolderLookup.Provider registries) {
        Map<Integer, ItemStack> items = new HashMap<>();
        BlockEntity blockEntity = schematicWorld.getBlockEntity(pos);
        if (blockEntity == null) return items;

        CompoundTag nbt = blockEntity.saveWithoutMetadata(registries);
        if (nbt != null && nbt.contains("Items")) {
            items.putAll(RealContainerCache.parseNbtInventory(nbt, registries));
        }
        return items;
    }

    public static Set<Integer> getDisabledSlots(BlockPos worldPos) {
        var schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) return Collections.emptySet();

        BlockEntity blockEntity = schematicWorld.getBlockEntity(worldPos);
        if (blockEntity == null) return Collections.emptySet();

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return Collections.emptySet();

        CompoundTag nbt = blockEntity.saveWithoutMetadata(client.level.registryAccess());
        return parseDisabledSlots(nbt);
    }

    public static boolean doesCrafterNeedLocking(BlockPos pos, Minecraft client) {
        Set<Integer> schematicLocks = getDisabledSlots(pos);
        Set<Integer> cachedLocks = RealContainerCache.getCachedLocks(pos);
        if (cachedLocks != null) return !schematicLocks.equals(cachedLocks);

        BlockEntity realEntity = client.level.getBlockEntity(pos);
        if (realEntity == null) return true;
        return !schematicLocks.equals(parseDisabledSlots(realEntity.saveWithoutMetadata(client.level.registryAccess())));
    }

    private static Set<Integer> parseDisabledSlots(CompoundTag nbt) {
        Set<Integer> disabledSlots = new java.util.HashSet<>();
        if (nbt != null && nbt.contains("disabled_slots")) {
            net.minecraft.nbt.Tag elem = nbt.get("disabled_slots");

            if (elem instanceof net.minecraft.nbt.ListTag list) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) instanceof net.minecraft.nbt.NumericTag num) {
                        disabledSlots.add(num.intValue());
                    }
                }
            }
            else if (elem instanceof net.minecraft.nbt.IntArrayTag intArray) {
                for (int val : intArray.getAsIntArray()) {
                    disabledSlots.add(val);
                }
            }
        }
        return disabledSlots;
    }

    public static Map<Integer, ItemStack> getRequiredItemsFromNbt(net.minecraft.nbt.CompoundTag nbt, net.minecraft.core.RegistryAccess registryManager) {
        if (!nbt.contains("Items")) return null;

        net.minecraft.nbt.Tag rawList = nbt.get("Items");
        if (!(rawList instanceof net.minecraft.nbt.ListTag itemsList)) return null;

        Map<Integer, ItemStack> items = new HashMap<>();

        for (int i = 0; i < itemsList.size(); i++) {
            net.minecraft.nbt.Tag element = itemsList.get(i);
            if (!(element instanceof net.minecraft.nbt.CompoundTag itemNbt)) continue;

            int slot = 0;
            if (itemNbt.contains("Slot")) {
                net.minecraft.nbt.Tag slotEl = itemNbt.get("Slot");
                if (slotEl instanceof net.minecraft.nbt.NumericTag num) {
                    slot = num.byteValue() & 0xFF;
                }
            }

            final int finalSlot = slot;

            try {
                com.mojang.serialization.DataResult<net.minecraft.world.item.ItemStack> result =
                        net.minecraft.world.item.ItemStack.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, itemNbt);

                result.result().ifPresent(stack -> {
                    if (!stack.isEmpty()) {
                        items.put(finalSlot, stack);
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return items;
    }
}