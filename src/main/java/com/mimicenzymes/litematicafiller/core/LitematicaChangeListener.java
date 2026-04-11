package com.mimicenzymes.litematicafiller.core;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.minecraft.client.Minecraft;

public class LitematicaChangeListener {
    private static Object lastSchematic = null;

    public static void tick(Minecraft mc) {
        Object current = SchematicWorldHandler.getSchematicWorld();

        if (current != lastSchematic) {
            lastSchematic = current;
            LitematicaContainerIndex.rebuildIndex(mc);
            RealContainerCache.clear();
            LitematicaCache.clear();
        }
    }
}