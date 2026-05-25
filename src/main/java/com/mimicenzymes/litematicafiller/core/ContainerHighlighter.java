package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.render.HighlightRenderer;
import com.mimicenzymes.litematicafiller.render.HighlightScanner;
import net.minecraft.client.MinecraftClient;

public class ContainerHighlighter {

    public static void tick(MinecraftClient client) {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) {
            return;
        }
        HighlightScanner.tick(client);
    }

    public static void onRender(Object context) {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) {
            return;
        }
        HighlightRenderer.getInstance().render();
    }
}
