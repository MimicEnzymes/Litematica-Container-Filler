package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.render.HighlightRenderer;
import com.mimicenzymes.litematicafiller.render.HighlightScanner;
import net.minecraft.client.Minecraft;

public class ContainerHighlighter {

    public static void tick(Minecraft client) {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) {
            return;
        }
        HighlightScanner.tick(client);
    }

    public static void onRender(Object context) {
        if (!Configs.ENABLE_MOD.getBooleanValue() ||
                (!Configs.HIGHLIGHT_CONTAINERS.getBooleanValue() && !HighlightScanner.hasMaterialFocus())) {
            return;
        }
        HighlightRenderer.getInstance().render();
    }
}
