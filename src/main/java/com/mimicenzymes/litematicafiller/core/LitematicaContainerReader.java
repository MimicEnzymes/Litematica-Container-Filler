package com.mimicenzymes.litematicafiller.core;

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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class LitematicaContainerReader {
    public static BlockPos[] getDoubleContainerHalves(net.minecraft.world.level.Level Level, BlockPos pos, BlockState state) {
        return getDoubleContainerHalves(Level, pos, state, -1);
    }

    public static BlockPos[] getDoubleContainerHalves(net.minecraft.world.level.Level Level, BlockPos pos, BlockState state, int knownSlotCount) {
        return getContainerHalves(Level, pos, state, false, knownSlotCount);
    }

    public static BlockPos[] getRenderContainerHalves(net.minecraft.world.level.Level Level, BlockPos pos, BlockState state) {
        int knownSlotCount = RealContainerCache.getKnownSlotCount(pos);
        return getContainerHalves(Level, pos, state, true, knownSlotCount);
    }

    public static BlockPos[] getLargeBarrelConfirmationPair(net.minecraft.world.level.Level Level, BlockPos pos, BlockState state) {
        return null;
    }

    public static BlockPos[] getPotentialLargeBarrelPair(net.minecraft.world.level.Level Level, BlockPos pos, BlockState state) {
        if (Level == null || pos == null || state == null || !state.is(net.minecraft.world.level.block.Blocks.BARREL)) return null;
        Direction facing = state.getValue(net.minecraft.world.level.block.BarrelBlock.FACING);
        return getLargeBarrelPair(Level, pos, facing, pos.relative(facing.getOpposite()));
    }

    private static BlockPos[] getContainerHalves(net.minecraft.world.level.Level Level, BlockPos pos, BlockState state, boolean renderOnly, int knownSlotCount) {
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
            BlockPos[] pair = getPotentialLargeBarrelPair(Level, pos, state);
            if (pair == null) return null;

            return shouldUseLargeBarrels(Level, pos, state, pair, renderOnly, knownSlotCount) ? pair : null;
        }
        return null;
    }

    private static boolean shouldUseLargeBarrels(net.minecraft.world.level.Level Level, BlockPos pos, BlockState state, BlockPos[] pair, boolean renderOnly, int knownSlotCount) {
        CarpetLargeBarrelMode mode = Configs.getCarpetLargeBarrelMode();
        if (mode == CarpetLargeBarrelMode.OFF) return false;
        return mode == CarpetLargeBarrelMode.ON;
    }

    private static BlockPos[] getLargeBarrelPair(net.minecraft.world.level.Level Level, BlockPos pos, Direction facing, BlockPos pos2) {
        BlockState state2 = Level.getBlockState(pos2);
        if (!state2.is(net.minecraft.world.level.block.Blocks.BARREL) || state2.getValue(net.minecraft.world.level.block.BarrelBlock.FACING) != facing.getOpposite()) {
            return null;
        }

        BlockPos first = isLargeBarrelFirst(facing) ? pos : pos2;
        BlockPos second = first.equals(pos) ? pos2 : pos;
        return new BlockPos[]{first, second};
    }

    private static BlockPos findLargeBarrelMate(net.minecraft.world.level.Level Level, BlockPos pos, Direction facing) {
        BlockPos behind = pos.relative(facing.getOpposite());
        if (isLargeBarrelMate(Level, behind, facing)) return behind;

        return null;
    }

    private static boolean isLargeBarrelMate(net.minecraft.world.level.Level Level, BlockPos pos, Direction facing) {
        BlockState state = Level.getBlockState(pos);
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

        String schematicKey = findSchematicKeyForPosition(worldPos);

        if (halves != null) {
            Map<Integer, ItemStack> rightHalf = getSingleContainerItems(schematicWorld, halves[0], registries);
            Map<Integer, ItemStack> leftHalf = getSingleContainerItems(schematicWorld, halves[1], registries);
            Map<Integer, ItemStack> combined = RealContainerCache.combineDoubleContainerItems(rightHalf, leftHalf);
            if (combined != null) items.putAll(combined);
        } else {
            items.putAll(getSingleContainerItems(schematicWorld, worldPos, registries));
        }

        MaterialReplacer.replaceInMap(items, schematicKey);

        return items;
    }

    private static Map<Integer, ItemStack> getSingleContainerItems(net.minecraft.world.level.Level schematicWorld, BlockPos pos, HolderLookup.Provider registries) {
        Map<Integer, ItemStack> items = new HashMap<>();
        items.putAll(LitematicaPlacementContainerData.getItems(pos, registries));
        if (!items.isEmpty()) return items;

        BlockEntity blockEntity = schematicWorld.getBlockEntity(pos);
        if (blockEntity == null) return items;

        CompoundTag nbt = createRawNbt(blockEntity, registries);
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
        String schematicKey = findSchematicKeyForPosition(worldPos);

        if (halves != null) {
            Map<Integer, ItemStack> combined = RealContainerCache.combineDoubleContainerItems(
                    getSingleContainerItems(schematicWorld, halves[0], registries),
                    getSingleContainerItems(schematicWorld, halves[1], registries));
            collectIgnoredSlots(combined, ignoredSlots, schematicKey);
        } else {
            collectIgnoredSlots(getSingleContainerItems(schematicWorld, worldPos, registries), ignoredSlots, schematicKey);
        }

        return ignoredSlots;
    }

    private static void collectIgnoredSlots(Map<Integer, ItemStack> items, Set<Integer> ignoredSlots, String schematicKey) {
        if (items == null) return;
        for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
            if (MaterialReplacer.isIgnored(entry.getValue(), schematicKey)) {
                ignoredSlots.add(entry.getKey());
            }
        }
    }

    public static Set<Integer> getDisabledSlots(BlockPos worldPos) {
        var schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) return Collections.emptySet();

        var placementNbt = LitematicaPlacementContainerData.getNbt(worldPos);
        if (placementNbt.isPresent()) {
            return parseDisabledSlots(placementNbt.get());
        }

        BlockEntity blockEntity = schematicWorld.getBlockEntity(worldPos);
        if (blockEntity == null) return Collections.emptySet();

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return Collections.emptySet();

        CompoundTag nbt = createRawNbt(blockEntity, client.level.registryAccess());
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
                var ops = registryManager != null ? registryManager.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE) : net.minecraft.nbt.NbtOps.INSTANCE;
                com.mojang.serialization.DataResult<net.minecraft.world.item.ItemStack> result =
                        net.minecraft.world.item.ItemStack.OPTIONAL_CODEC.parse(ops, itemNbt);

                result.result().ifPresent(stack -> {
                    if (!stack.isEmpty()) {
                        items.put(finalSlot, stack);
                    }
                });
            } catch (Exception ignored) {}
        }

        MaterialReplacer.replaceInMap(items);

        return items;
    }

    public static CompoundTag createRawNbt(BlockEntity blockEntity, HolderLookup.Provider registries) {
        if (blockEntity == null) return null;

        MaterialReplacer.pushNbtReplacementSuppression();
        try {
            return blockEntity.saveWithoutMetadata(registries);
        } finally {
            MaterialReplacer.popNbtReplacementSuppression();
        }
    }

    public static String findSchematicKeyForPosition(BlockPos worldPos) {
        if (worldPos == null) return null;

        try {
            var manager = fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager();
            if (manager == null) return null;

            for (fi.dy.masa.litematica.schematic.placement.SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
                if (placement == null || !placement.isEnabled()) continue;

                for (fi.dy.masa.litematica.selection.Box box : placement.getSubRegionBoxes(
                        fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values()) {
                    BlockPos p1 = box.getPos1();
                    BlockPos p2 = box.getPos2();
                    int minX = Math.min(p1.getX(), p2.getX());
                    int maxX = Math.max(p1.getX(), p2.getX());
                    int minY = Math.min(p1.getY(), p2.getY());
                    int maxY = Math.max(p1.getY(), p2.getY());
                    int minZ = Math.min(p1.getZ(), p2.getZ());
                    int maxZ = Math.max(p1.getZ(), p2.getZ());
                    if (worldPos.getX() >= minX && worldPos.getX() <= maxX
                            && worldPos.getY() >= minY && worldPos.getY() <= maxY
                            && worldPos.getZ() >= minZ && worldPos.getZ() <= maxZ) {
                        return SchematicMaterialReplacementContext.keyForPlacement(placement);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }
}
