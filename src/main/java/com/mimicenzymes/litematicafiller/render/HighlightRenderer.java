package com.mimicenzymes.litematicafiller.render;

import com.mojang.logging.LogUtils;
import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.render.RenderContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.util.Map;

public class HighlightRenderer {
    private static final HighlightRenderer INSTANCE = new HighlightRenderer();
    private static final Logger LOGGER = LogUtils.getLogger();

    public static HighlightRenderer getInstance() { return INSTANCE; }

    public void render(WorldRenderContext context) {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) return;

        Map<BlockPos, HighlightState> highlights = HighlightScanner.getHighlights();
        if (highlights.isEmpty()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || client.player == null) return;

        RenderContext ctx = null;
        BuiltBuffer meshData = null;

        try {
            boolean xray = Configs.HIGHLIGHT_XRAY.getBooleanValue();

            ctx = new RenderContext(
                    () -> "litematica_filler_lines",
                    xray ? MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_NO_DEPTH_NO_CULL : MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_OFFSET_2
            );

            var buffer = ctx.getBuilder();
            if (buffer == null) return;

            float lineWidth = Math.max(2.5F, (float)client.getWindow().getFramebufferWidth() / 1920.0F * 2.5F);

            for (Map.Entry<BlockPos, HighlightState> entry : highlights.entrySet()) {
                Color4f c = getColor(entry.getValue());
                RenderUtils.drawBlockBoundingBoxOutlinesBatchedLinesSimple(entry.getKey(), c, 0.015, buffer);
            }
            meshData = buffer.endNullable();
            if (meshData != null) {
                ctx.draw(meshData, false, true);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to render container highlights", e);
        } finally {
            if (meshData != null) {
                meshData.close();
            }
            if (ctx != null) {
                ctx.reset();
            }
        }
    }
    private Color4f getColor(HighlightState type) {
        return switch (type) {
            case UNFILLED -> Configs.HIGHLIGHT_COLOR_UNFILLED.getColor();
            case PARTIAL -> Configs.HIGHLIGHT_COLOR_PARTIAL.getColor();
            case OVERFILLED -> Configs.HIGHLIGHT_COLOR_OVERFILLED.getColor();
            case WRONG_ITEM -> Configs.HIGHLIGHT_COLOR_WRONG.getColor();
            case SATISFIED -> Configs.HIGHLIGHT_COLOR_SATISFIED.getColor();
            default -> Configs.HIGHLIGHT_COLOR_UNKNOWN.getColor();
        };
    }
}
