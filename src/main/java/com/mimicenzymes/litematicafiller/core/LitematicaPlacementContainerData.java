package com.mimicenzymes.litematicafiller.core;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.util.SchematicUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class LitematicaPlacementContainerData {
    private static volatile Snapshot snapshot = Snapshot.empty();

    public static Set<BlockPos> rebuildIndex() {
        Snapshot next = buildSnapshot();
        snapshot = next;
        return next.positions();
    }

    public static void clear() {
        snapshot = Snapshot.empty();
    }

    public static Optional<NbtCompound> getNbt(BlockPos worldPos) {
        ensureInitialized();
        NbtCompound nbt = snapshot.nbtByWorldPos().get(worldPos);
        return nbt == null ? Optional.empty() : Optional.of(nbt);
    }

    public static Map<Integer, ItemStack> getItems(BlockPos worldPos, RegistryWrapper.WrapperLookup registries) {
        Optional<NbtCompound> nbt = getNbt(worldPos);
        if (nbt.isEmpty() || !nbt.get().contains("Items")) {
            return Collections.emptyMap();
        }

        return RealContainerCache.parseNbtInventory(nbt.get(), registries);
    }

    public static String getSchematicKey(BlockPos worldPos) {
        if (worldPos == null) return null;

        ensureInitialized();
        return snapshot.schematicKeyByWorldPos().get(worldPos);
    }

    private static Snapshot buildSnapshot() {
        Map<BlockPos, NbtCompound> nbtByWorldPos = new HashMap<>();
        Map<BlockPos, String> schematicKeyByWorldPos = new HashMap<>();
        Set<BlockPos> positions = new HashSet<>();
        MinecraftClient client = MinecraftClient.getInstance();

        try {
            for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
                if (placement == null || !placement.isEnabled()) continue;

                LitematicaSchematic schematic = placement.getSchematic();
                if (schematic == null) continue;

                for (String regionName : placement.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED).keySet()) {
                    SubRegionPlacement regionPlacement = placement.getRelativeSubRegionPlacement(regionName);
                    LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
                    Map<BlockPos, NbtCompound> regionBlockEntities = schematic.getBlockEntityMapForRegion(regionName);
                    if (regionPlacement == null || container == null || regionBlockEntities == null || regionBlockEntities.isEmpty()) {
                        continue;
                    }

                    for (Map.Entry<BlockPos, NbtCompound> entry : regionBlockEntities.entrySet()) {
                        BlockPos localPos = entry.getKey();
                        NbtCompound nbt = entry.getValue();
                        if (localPos == null || nbt == null) continue;

                        BlockPos worldPos = toWorldPos(localPos, schematic, regionName, placement, regionPlacement);
                        if (worldPos == null) continue;

                        if (!mapsBackToLocalPos(worldPos, localPos, schematic, regionName, placement, regionPlacement, container)) {
                            continue;
                        }

                        if (!isContainerBlockEntity(container, localPos, client, nbt)) continue;

                        BlockPos immutableWorldPos = worldPos.toImmutable();
                        positions.add(immutableWorldPos);
                        nbtByWorldPos.put(immutableWorldPos, nbt.copy());
                        schematicKeyByWorldPos.put(immutableWorldPos, SchematicMaterialReplacementContext.keyForPlacement(placement));
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return new Snapshot(
                Collections.unmodifiableSet(positions),
                Collections.unmodifiableMap(nbtByWorldPos),
                Collections.unmodifiableMap(schematicKeyByWorldPos),
                true
        );
    }

    private static void ensureInitialized() {
        if (snapshot.initialized()) return;

        synchronized (LitematicaPlacementContainerData.class) {
            if (!snapshot.initialized()) {
                snapshot = buildSnapshot();
            }
        }
    }

    private static BlockPos toWorldPos(BlockPos localPos, LitematicaSchematic schematic, String regionName, SchematicPlacement placement, SubRegionPlacement regionPlacement) {
        try {
            BlockPos regionPos = regionPlacement.getPos();
            BlockPos regionSize = schematic.getAreaSize(regionName);
            if (regionSize == null) return null;

            BlockPos regionEnd = PositionUtils.getRelativeEndPositionFromAreaSize(regionSize).add(regionPos);
            BlockPos regionMin = PositionUtils.getMinCorner(regionPos, regionEnd);
            BlockPos posWithinSubRegion = new BlockPos(
                    regionMin.getX() + localPos.getX() - regionPos.getX(),
                    regionMin.getY() + localPos.getY() - regionPos.getY(),
                    regionMin.getZ() + localPos.getZ() - regionPos.getZ()
            );
            BlockPos regionPosTransformed = PositionUtils.getTransformedBlockPos(regionPos, placement.getMirror(), placement.getRotation());
            BlockPos transformedLocal = PositionUtils.getTransformedPlacementPosition(posWithinSubRegion, placement, regionPlacement);
            return placement.getOrigin().add(regionPosTransformed).add(transformedLocal);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean mapsBackToLocalPos(BlockPos worldPos,
                                             BlockPos localPos,
                                             LitematicaSchematic schematic,
                                             String regionName,
                                             SchematicPlacement placement,
                                             SubRegionPlacement regionPlacement,
                                             LitematicaBlockStateContainer container) {
        try {
            BlockPos mappedLocalPos = SchematicUtils.getSchematicContainerPositionFromWorldPosition(
                    worldPos,
                    schematic,
                    regionName,
                    placement,
                    regionPlacement,
                    container
            );
            return localPos.equals(mappedLocalPos);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isContainerBlockEntity(LitematicaBlockStateContainer container, BlockPos localPos, MinecraftClient client, NbtCompound nbt) {
        try {
            BlockState state = container.get(localPos.getX(), localPos.getY(), localPos.getZ());
            if (state == null || state.isAir() || !state.hasBlockEntity()) return false;
            if (client.world == null) return true;

            try {
                var blockEntity = net.minecraft.block.entity.BlockEntity.createFromNbt(
                        localPos,
                        state,
                        nbt,
                        client.world.getRegistryManager()
                );
                return blockEntity instanceof net.minecraft.inventory.Inventory || nbt.contains("Items");
            } catch (Exception ignored) {
                return nbt.contains("Items");
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private record Snapshot(Set<BlockPos> positions, Map<BlockPos, NbtCompound> nbtByWorldPos, Map<BlockPos, String> schematicKeyByWorldPos, boolean initialized) {
        static Snapshot empty() {
            return new Snapshot(Collections.emptySet(), Collections.emptyMap(), Collections.emptyMap(), false);
        }
    }
}
