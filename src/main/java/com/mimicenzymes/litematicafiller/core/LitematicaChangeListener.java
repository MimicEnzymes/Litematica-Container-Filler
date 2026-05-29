package com.mimicenzymes.litematicafiller.core;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.minecraft.client.Minecraft;

public class LitematicaChangeListener {
    private static Object lastSchematic = null;
    private static int cleanupTicker = 0;

    public static void tick(Minecraft mc) {
        if (!RealContainerCache.hasActiveConsumers()) {
            return;
        }

        Object current = SchematicWorldHandler.getSchematicWorld();

        if (++cleanupTicker >= 100) {
            cleanupTicker = 0;
            LitematicaCache.cleanupExpired();
        }

        if (current != lastSchematic) {
            lastSchematic = current;
            LitematicaContainerIndex.rebuildIndex(mc);
            RealContainerCache.clear();
            LitematicaCache.clear();
            ManualContainerOverrideManager.clearForCurrentContext();
        }
    }
}
