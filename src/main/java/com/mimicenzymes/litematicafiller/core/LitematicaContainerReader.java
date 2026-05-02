package com.mimicenzymes.litematicafiller.core;

import com.mojang.logging.LogUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class LitematicaContainerReader {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static BlockPos[] getDoubleContainerHalves(net.minecraft.world.World world, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof ChestBlock) {
            ChestType type = state.get(ChestBlock.CHEST_TYPE);
            if (type != ChestType.SINGLE) {
                Direction facing = state.get(ChestBlock.FACING);
                Direction otherHalfDir = (type == ChestType.LEFT) ? facing.rotateYClockwise() : facing.rotateYCounterclockwise();
                BlockPos rightPos = (type == ChestType.RIGHT) ? pos : pos.offset(otherHalfDir);
                BlockPos leftPos = (type == ChestType.LEFT) ? pos : pos.offset(otherHalfDir);
                return new BlockPos[]{rightPos, leftPos};
            }
        } else if (state.isOf(net.minecraft.block.Blocks.BARREL)) {
            if (!com.mimicenzymes.litematicafiller.config.Configs.ENABLE_CARPET_LARGE_BARRELS.getBooleanValue()) {
                return null;
            }

            Direction facing = state.get(net.minecraft.block.BarrelBlock.FACING);
            Direction bottomDir = facing.getOpposite();
            BlockPos pos2 = pos.offset(bottomDir);
            BlockState state2 = world.getBlockState(pos2);

            if (state2.isOf(net.minecraft.block.Blocks.BARREL) && state2.get(net.minecraft.block.BarrelBlock.FACING) == facing.getOpposite()) {
                if (pos.compareTo(pos2) < 0) {
                    return new BlockPos[]{pos, pos2};
                } else {
                    return new BlockPos[]{pos2, pos};
                }
            }
        }
        return null;
    }

    public static Map<Integer, ItemStack> getRequiredItems(BlockPos worldPos, RegistryWrapper.WrapperLookup registries) {
        Map<Integer, ItemStack> items = new HashMap<>();
        var schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) return items;

        BlockState state = schematicWorld.getBlockState(worldPos);
        BlockPos[] halves = getDoubleContainerHalves(schematicWorld, worldPos, state);

        if (halves != null) {
            Map<Integer, ItemStack> rightHalf = getSingleContainerItems(schematicWorld, halves[0], registries);
            Map<Integer, ItemStack> leftHalf = getSingleContainerItems(schematicWorld, halves[1], registries);

            items.putAll(rightHalf);

            for (Map.Entry<Integer, ItemStack> entry : leftHalf.entrySet()) {
                items.put(entry.getKey() + 27, entry.getValue());
            }
        } else {
            items.putAll(getSingleContainerItems(schematicWorld, worldPos, registries));
        }

        MaterialReplacer.replaceInMap(items);

        return items;
    }

    private static Map<Integer, ItemStack> getSingleContainerItems(net.minecraft.world.World schematicWorld, BlockPos pos, RegistryWrapper.WrapperLookup registries) {
        Map<Integer, ItemStack> items = new HashMap<>();
        BlockEntity blockEntity = schematicWorld.getBlockEntity(pos);
        if (blockEntity == null) return items;

        NbtCompound nbt = blockEntity.createNbt(registries);
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

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return Collections.emptySet();

        NbtCompound nbt = blockEntity.createNbt(client.world.getRegistryManager());
        return parseDisabledSlots(nbt);
    }

    public static boolean doesCrafterNeedLocking(BlockPos pos, MinecraftClient client) {
        Set<Integer> schematicLocks = getDisabledSlots(pos);
        Set<Integer> cachedLocks = RealContainerCache.getCachedLocks(pos);
        if (cachedLocks != null) return !schematicLocks.equals(cachedLocks);

        BlockEntity realEntity = client.world.getBlockEntity(pos);
        if (realEntity == null) return true;
        return !schematicLocks.equals(parseDisabledSlots(realEntity.createNbt(client.world.getRegistryManager())));
    }

    private static Set<Integer> parseDisabledSlots(NbtCompound nbt) {
        Set<Integer> disabledSlots = new java.util.HashSet<>();
        if (nbt != null && nbt.contains("disabled_slots")) {
            net.minecraft.nbt.NbtElement elem = nbt.get("disabled_slots");

            if (elem instanceof net.minecraft.nbt.NbtList list) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) instanceof net.minecraft.nbt.AbstractNbtNumber num) {
                        disabledSlots.add(num.intValue());
                    }
                }
            }
            else if (elem instanceof net.minecraft.nbt.NbtIntArray intArray) {
                for (int val : intArray.getIntArray()) {
                    disabledSlots.add(val);
                }
            }
        }
        return disabledSlots;
    }

    public static Map<Integer, ItemStack> getRequiredItemsFromNbt(net.minecraft.nbt.NbtCompound nbt, net.minecraft.registry.DynamicRegistryManager registryManager) {
        if (!nbt.contains("Items")) return null;

        net.minecraft.nbt.NbtElement rawList = nbt.get("Items");
        if (!(rawList instanceof net.minecraft.nbt.NbtList itemsList)) return null;

        Map<Integer, ItemStack> items = new HashMap<>();

        for (int i = 0; i < itemsList.size(); i++) {
            net.minecraft.nbt.NbtElement element = itemsList.get(i);
            if (!(element instanceof net.minecraft.nbt.NbtCompound itemNbt)) continue;

            int slot = 0;
            if (itemNbt.contains("Slot")) {
                net.minecraft.nbt.NbtElement slotEl = itemNbt.get("Slot");
                if (slotEl instanceof net.minecraft.nbt.AbstractNbtNumber num) {
                    slot = num.byteValue() & 0xFF;
                }
            }

            final int finalSlot = slot;

            try {
                com.mojang.serialization.DataResult<net.minecraft.item.ItemStack> result =
                        net.minecraft.item.ItemStack.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, itemNbt);

                result.result().ifPresent(stack -> {
                    if (!stack.isEmpty()) {
                        items.put(finalSlot, stack);
                    }
                });
            } catch (Exception e) {
                LOGGER.warn("Failed to read required item stack from schematic NBT", e);
            }
        }

        MaterialReplacer.replaceInMap(items);

        return items;
    }
}
