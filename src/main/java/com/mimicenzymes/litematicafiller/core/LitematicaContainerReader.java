package com.mimicenzymes.litematicafiller.core;

import com.mojang.logging.LogUtils;
import com.mimicenzymes.litematicafiller.config.CarpetLargeBarrelMode;
import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class LitematicaContainerReader {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static BlockPos[] getDoubleContainerHalves(net.minecraft.world.level.Level world, BlockPos pos, BlockState state) {
        return getDoubleContainerHalves(world, pos, state, -1);
    }

    public static BlockPos[] getDoubleContainerHalves(net.minecraft.world.level.Level world, BlockPos pos, BlockState state, int knownSlotCount) {
        return getContainerHalves(world, pos, state, false, knownSlotCount);
    }

    public static BlockPos[] getRenderContainerHalves(net.minecraft.world.level.Level world, BlockPos pos, BlockState state) {
        int knownSlotCount = RealContainerCache.getKnownSlotCount(pos);
        return getContainerHalves(world, pos, state, true, knownSlotCount);
    }

    private static BlockPos[] getContainerHalves(net.minecraft.world.level.Level world, BlockPos pos, BlockState state, boolean renderOnly, int knownSlotCount) {
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
            Direction facing = state.getValue(net.minecraft.world.level.block.BarrelBlock.FACING);
            BlockPos[] pair = getLargeBarrelPair(world, pos, facing, pos.relative(facing.getOpposite()));
            if (pair == null) return null;

            return shouldUseLargeBarrels(world, pos, state, pair, renderOnly, knownSlotCount) ? pair : null;
        }
        return null;
    }

    private static boolean shouldUseLargeBarrels(net.minecraft.world.level.Level world, BlockPos pos, BlockState state, BlockPos[] pair, boolean renderOnly, int knownSlotCount) {
        CarpetLargeBarrelMode mode = Configs.getCarpetLargeBarrelMode();
        if (mode == CarpetLargeBarrelMode.OFF) return false;
        if (mode == CarpetLargeBarrelMode.ON) return true;

        BlockPos mate = pair[0].equals(pos) ? pair[1] : pair[0];

        if (knownSlotCount >= 54) return true;
        if (knownSlotCount > 0) return false;

        int cachedSlotCount = RealContainerCache.getKnownSlotCount(pos);
        if (cachedSlotCount >= 54) return true;
        if (cachedSlotCount > 0) return false;

        int mateCachedSlotCount = RealContainerCache.getKnownSlotCount(mate);
        if (mateCachedSlotCount >= 54) return true;
        if (mateCachedSlotCount > 0) return false;

        return false;
    }

    private static BlockPos[] getLargeBarrelPair(net.minecraft.world.level.Level world, BlockPos pos, Direction facing, BlockPos pos2) {
        BlockState state2 = world.getBlockState(pos2);
        if (!state2.is(net.minecraft.world.level.block.Blocks.BARREL) || state2.getValue(net.minecraft.world.level.block.BarrelBlock.FACING) != facing.getOpposite()) {
            return null;
        }

        BlockPos first = isLargeBarrelFirst(facing) ? pos : pos2;
        BlockPos second = first.equals(pos) ? pos2 : pos;
        return new BlockPos[]{first, second};
    }

    private static BlockPos findLargeBarrelMate(net.minecraft.world.level.Level world, BlockPos pos, Direction facing) {
        BlockPos behind = pos.relative(facing.getOpposite());
        if (isLargeBarrelMate(world, behind, facing)) return behind;

        return null;
    }

    private static boolean isLargeBarrelMate(net.minecraft.world.level.Level world, BlockPos pos, Direction facing) {
        BlockState state = world.getBlockState(pos);
        return state.is(net.minecraft.world.level.block.Blocks.BARREL) && state.getValue(net.minecraft.world.level.block.BarrelBlock.FACING) == facing.getOpposite();
    }

    private static boolean isLargeBarrelFirst(Direction facing) {
        return facing.getAxisDirection() == Direction.AxisDirection.NEGATIVE;
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

            for (Map.Entry<Integer, ItemStack> entry : leftHalf.entrySet()) {
                items.put(entry.getKey() + 27, entry.getValue());
            }
        } else {
            items.putAll(getSingleContainerItems(schematicWorld, worldPos, registries));
        }

        MaterialReplacer.replaceInMap(items);

        return items;
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

    public static Set<Integer> getIgnoredSlots(BlockPos worldPos, HolderLookup.Provider registries) {
        Set<Integer> ignoredSlots = new HashSet<>();
        var schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) return ignoredSlots;

        BlockState state = schematicWorld.getBlockState(worldPos);
        BlockPos[] halves = getDoubleContainerHalves(schematicWorld, worldPos, state);

        if (halves != null) {
            collectIgnoredSlots(getSingleContainerItems(schematicWorld, halves[0], registries), 0, ignoredSlots);
            collectIgnoredSlots(getSingleContainerItems(schematicWorld, halves[1], registries), 27, ignoredSlots);
        } else {
            collectIgnoredSlots(getSingleContainerItems(schematicWorld, worldPos, registries), 0, ignoredSlots);
        }

        return ignoredSlots;
    }

    private static void collectIgnoredSlots(Map<Integer, ItemStack> items, int offset, Set<Integer> ignoredSlots) {
        for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
            if (MaterialReplacer.isIgnored(entry.getValue())) {
                ignoredSlots.add(entry.getKey() + offset);
            }
        }
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

    public static Map<Integer, ItemStack> getRequiredItemsFromNbt(net.minecraft.nbt.CompoundTag nbt, net.minecraft.core.HolderLookup.Provider registryManager) {
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
                LOGGER.warn("Failed to read required item stack from schematic NBT", e);
            }
        }

        MaterialReplacer.replaceInMap(items);

        return items;
    }
}
