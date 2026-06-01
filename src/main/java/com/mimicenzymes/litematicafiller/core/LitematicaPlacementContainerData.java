package com.mimicenzymes.litematicafiller.core;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.util.SchematicUtils;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;

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

    public static Optional<CompoundTag> getNbt(BlockPos worldPos) {
        ensureInitialized();
        CompoundTag nbt = snapshot.nbtByWorldPos().get(worldPos);
        return nbt == null ? Optional.empty() : Optional.of(nbt);
    }

    public static Map<Integer, ItemStack> getItems(BlockPos worldPos, HolderLookup.Provider registries) {
        Optional<CompoundTag> nbt = getNbt(worldPos);
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
        Map<BlockPos, CompoundTag> nbtByWorldPos = new HashMap<>();
        Map<BlockPos, String> schematicKeyByWorldPos = new HashMap<>();
        Set<BlockPos> positions = new HashSet<>();
        Minecraft client = Minecraft.getInstance();

        try {
            for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
                if (placement == null || !placement.isEnabled()) continue;

                LitematicaSchematic schematic = placement.getSchematic();
                if (schematic == null) continue;

                for (String regionName : placement.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED).keySet()) {
                    SubRegionPlacement regionPlacement = placement.getRelativeSubRegionPlacement(regionName);
                    LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
                    Map<BlockPos, CompoundTag> regionBlockEntities = schematic.getBlockEntityMapForRegion(regionName);
                    if (regionPlacement == null || container == null || regionBlockEntities == null || regionBlockEntities.isEmpty()) {
                        continue;
                    }

                    for (Map.Entry<BlockPos, CompoundTag> entry : regionBlockEntities.entrySet()) {
                        BlockPos localPos = entry.getKey();
                        CompoundTag nbt = entry.getValue();
                        if (localPos == null || nbt == null) continue;

                        BlockPos worldPos = toWorldPos(localPos, schematic, regionName, placement, regionPlacement);
                        if (worldPos == null) continue;

                        if (!mapsBackToLocalPos(worldPos, localPos, schematic, regionName, placement, regionPlacement, container)) {
                            continue;
                        }

                        if (!isContainerBlockEntity(container, localPos, client, nbt)) continue;

                        positions.add(worldPos.immutable());
                        nbtByWorldPos.put(worldPos.immutable(), nbt.copy());
                        schematicKeyByWorldPos.put(worldPos.immutable(), SchematicMaterialReplacementContext.keyForPlacement(placement));
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

            BlockPos regionEnd = PositionUtils.getRelativeEndPositionFromAreaSize(regionSize).offset(regionPos);
            BlockPos regionMin = PositionUtils.getMinCorner(regionPos, regionEnd);
            BlockPos posWithinSubRegion = new BlockPos(
                    regionMin.getX() + localPos.getX() - regionPos.getX(),
                    regionMin.getY() + localPos.getY() - regionPos.getY(),
                    regionMin.getZ() + localPos.getZ() - regionPos.getZ()
            );
            BlockPos regionPosTransformed = PositionUtils.getTransformedBlockPos(regionPos, placement.getMirror(), placement.getRotation());
            BlockPos transformedLocal = PositionUtils.getTransformedPlacementPosition(posWithinSubRegion, placement, regionPlacement);
            return placement.getOrigin().offset(regionPosTransformed).offset(transformedLocal);
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

    private static boolean isContainerBlockEntity(LitematicaBlockStateContainer container, BlockPos localPos, Minecraft client, CompoundTag nbt) {
        try {
            BlockState state = container.get(localPos.getX(), localPos.getY(), localPos.getZ());
            if (state == null || state.isAir() || !state.hasBlockEntity()) return false;
            if (client.level == null) return true;

            try {
                var blockEntity = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                        localPos,
                        state,
                        nbt,
                        client.level.registryAccess()
                );
                return blockEntity instanceof net.minecraft.world.Container || nbt.contains("Items");
            } catch (Exception ignored) {
                return nbt.contains("Items");
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private record Snapshot(Set<BlockPos> positions, Map<BlockPos, CompoundTag> nbtByWorldPos, Map<BlockPos, String> schematicKeyByWorldPos, boolean initialized) {
        static Snapshot empty() {
            return new Snapshot(Collections.emptySet(), Collections.emptyMap(), Collections.emptyMap(), false);
        }
    }
}
