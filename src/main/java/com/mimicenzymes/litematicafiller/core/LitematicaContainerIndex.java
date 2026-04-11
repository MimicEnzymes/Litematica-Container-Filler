package com.mimicenzymes.litematicafiller.core;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.selection.Box;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;

public class LitematicaContainerIndex {
    private static final List<BlockPos> CONTAINERS = new ArrayList<>();

    public static void rebuildIndex(Minecraft mc) {
        CONTAINERS.clear();
        LitematicaCache.clear();
        var schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) return;

        List<SchematicPlacement> placements = DataManager.getSchematicPlacementManager().getAllSchematicsPlacements();

        for (SchematicPlacement placement : placements) {
            if (!placement.isEnabled()) continue;
            Box box = placement.getEclosingBox();
            if (box == null) continue;

            BlockPos p1 = BlockPos.containing((Position) box.getPos1());
            BlockPos p2 = BlockPos.containing((Position) box.getPos2());

            int minX = Math.min(p1.getX(), p2.getX());
            int maxX = Math.max(p1.getX(), p2.getX());
            int minY = Math.min(p1.getY(), p2.getY());
            int maxY = Math.max(p1.getY(), p2.getY());
            int minZ = Math.min(p1.getZ(), p2.getZ());
            int maxZ = Math.max(p1.getZ(), p2.getZ());

            BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        mPos.set(x, y, z);
                        if (schematicWorld.getBlockState(mPos).hasBlockEntity()) {
                            CONTAINERS.add(mPos.immutable());
                        }
                    }
                }
            }
        }
        SpatialContainerIndex.rebuild(CONTAINERS);
    }

    public static List<BlockPos> getContainers() { return CONTAINERS; }
}