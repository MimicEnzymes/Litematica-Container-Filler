package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.tool.ContainerToolStateMachine;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.minecraft.client.MinecraftClient;

public class LitematicaChangeListener {
    private static Object lastSchematic = null;
    private static int cleanupTicker = 0;

    public static void tick(MinecraftClient mc) {
        boolean hasConsumer = Configs.HIGHLIGHT_CONTAINERS.getBooleanValue() ||
                Configs.WORKING_STATE.getBooleanValue() ||
                AutoFillerStateMachine.getInstance().isWorking() ||
                Configs.TOOL_ENABLED.getBooleanValue() ||
                ContainerToolStateMachine.getInstance().isWorking();
        if (!Configs.ENABLE_MOD.getBooleanValue() || !hasConsumer) {
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
