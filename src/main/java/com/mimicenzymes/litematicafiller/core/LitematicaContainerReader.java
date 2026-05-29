package com.mimicenzymes.litematicafiller.core;

import com.mojang.logging.LogUtils;
import com.mimicenzymes.litematicafiller.config.CarpetLargeBarrelMode;
import com.mimicenzymes.litematicafiller.config.Configs;
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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class LitematicaContainerReader {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static BlockPos[] getDoubleContainerHalves(net.minecraft.world.World world, BlockPos pos, BlockState state) {
        return getDoubleContainerHalves(world, pos, state, -1);
    }

    public static BlockPos[] getDoubleContainerHalves(net.minecraft.world.World world, BlockPos pos, BlockState state, int knownSlotCount) {
        return getContainerHalves(world, pos, state, false, knownSlotCount);
    }

    public static BlockPos[] getRenderContainerHalves(net.minecraft.world.World world, BlockPos pos, BlockState state) {
        int knownSlotCount = RealContainerCache.getKnownSlotCount(pos);
        return getContainerHalves(world, pos, state, true, knownSlotCount);
    }

    public static BlockPos[] getLargeBarrelConfirmationPair(net.minecraft.world.World world, BlockPos pos, BlockState state) {
        return null;
    }

    public static BlockPos[] getPotentialLargeBarrelPair(net.minecraft.world.World world, BlockPos pos, BlockState state) {
        if (world == null || pos == null || state == null || !state.isOf(net.minecraft.block.Blocks.BARREL)) return null;
        Direction facing = state.get(net.minecraft.block.BarrelBlock.FACING);
        return getLargeBarrelPair(world, pos, facing, pos.offset(facing.getOpposite()));
    }

    private static BlockPos[] getContainerHalves(net.minecraft.world.World world, BlockPos pos, BlockState state, boolean renderOnly, int knownSlotCount) {
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
            BlockPos[] pair = getPotentialLargeBarrelPair(world, pos, state);
            if (pair == null) return null;

            return shouldUseLargeBarrels(world, pos, state, pair, renderOnly, knownSlotCount) ? pair : null;
        }
        return null;
    }

    private static boolean shouldUseLargeBarrels(net.minecraft.world.World world, BlockPos pos, BlockState state, BlockPos[] pair, boolean renderOnly, int knownSlotCount) {
        CarpetLargeBarrelMode mode = Configs.getCarpetLargeBarrelMode();
        if (mode == CarpetLargeBarrelMode.OFF) return false;
        return mode == CarpetLargeBarrelMode.ON;
    }

    private static BlockPos[] getLargeBarrelPair(net.minecraft.world.World world, BlockPos pos, Direction facing, BlockPos pos2) {
        BlockState state2 = world.getBlockState(pos2);
        if (!state2.isOf(net.minecraft.block.Blocks.BARREL) || state2.get(net.minecraft.block.BarrelBlock.FACING) != facing.getOpposite()) {
            return null;
        }

        BlockPos first = isLargeBarrelFirst(facing) ? pos : pos2;
        BlockPos second = first.equals(pos) ? pos2 : pos;
        return new BlockPos[]{first, second};
    }

    private static BlockPos findLargeBarrelMate(net.minecraft.world.World world, BlockPos pos, Direction facing) {
        BlockPos behind = pos.offset(facing.getOpposite());
        if (isLargeBarrelMate(world, behind, facing)) return behind;

        return null;
    }

    private static boolean isLargeBarrelMate(net.minecraft.world.World world, BlockPos pos, Direction facing) {
        BlockState state = world.getBlockState(pos);
        return state.isOf(net.minecraft.block.Blocks.BARREL) && state.get(net.minecraft.block.BarrelBlock.FACING) == facing.getOpposite();
    }

    private static boolean isLargeBarrelFirst(Direction facing) {
        return facing.getDirection() == Direction.AxisDirection.NEGATIVE;
    }

    public static Map<Integer, ItemStack> getRequiredItems(BlockPos worldPos, RegistryWrapper.WrapperLookup registries) {
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

    private static Map<Integer, ItemStack> getSingleContainerItems(net.minecraft.world.World schematicWorld, BlockPos pos, RegistryWrapper.WrapperLookup registries) {
        Map<Integer, ItemStack> items = new HashMap<>();
        items.putAll(LitematicaPlacementContainerData.getItems(pos, registries));
        if (!items.isEmpty()) return items;

        BlockEntity blockEntity = schematicWorld.getBlockEntity(pos);
        if (blockEntity == null) return items;

        NbtCompound nbt = blockEntity.createNbt(registries);
        if (nbt != null && nbt.contains("Items")) {
            items.putAll(RealContainerCache.parseNbtInventory(nbt, registries));
        }
        return items;
    }

    public static Set<Integer> getIgnoredSlots(BlockPos worldPos, RegistryWrapper.WrapperLookup registries) {
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
