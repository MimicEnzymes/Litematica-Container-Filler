package com.mimicenzymes.litematicafiller.core;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

public class SpatialContainerIndex {
    private static final List<BlockPos> POSITIONS = new ArrayList<>();

    public static void rebuild(List<BlockPos> containers) {
        POSITIONS.clear();
        POSITIONS.addAll(containers);
    }

    public static List<BlockPos> queryRadius(double px, double py, double pz, int radius) {
        int radiusSq = radius * radius;
        List<BlockPos> result = new ArrayList<>();

        for (BlockPos pos : POSITIONS) {
            double dx = pos.getX() + 0.5 - px;
            double dy = pos.getY() + 0.5 - py;
            double dz = pos.getZ() + 0.5 - pz;

            if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                result.add(pos);
            }
        }
        return result;
    }
}