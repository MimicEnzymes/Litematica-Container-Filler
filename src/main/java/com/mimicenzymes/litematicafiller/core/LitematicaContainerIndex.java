package com.mimicenzymes.litematicafiller.core;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class LitematicaContainerIndex {
    private static final List<BlockPos> CONTAINERS = new ArrayList<>();
    private static final List<BlockPos> VIEW = Collections.unmodifiableList(CONTAINERS);

    public static void rebuildIndex(Minecraft mc) {
        CONTAINERS.clear();
        LitematicaCache.clear();

        CONTAINERS.addAll(LitematicaPlacementContainerData.rebuildIndex());
        CONTAINERS.sort(Comparator.comparingLong(BlockPos::asLong));
        SpatialContainerIndex.rebuild(CONTAINERS);
    }

    public static List<BlockPos> getContainers() { return VIEW; }
}
