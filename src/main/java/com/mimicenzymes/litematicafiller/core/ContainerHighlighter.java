package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.render.HighlightRenderer;
import com.mimicenzymes.litematicafiller.render.HighlightScanner;
import net.minecraft.client.Minecraft;

public class ContainerHighlighter {

    public static void tick(Minecraft client) {
        HighlightScanner.tick(client);
    }

    public static void onRender(Object context) {
        HighlightRenderer.getInstance().render();
    }
}