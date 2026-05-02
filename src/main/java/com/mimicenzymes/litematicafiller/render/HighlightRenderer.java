package com.mimicenzymes.litematicafiller.render;

import com.mojang.logging.LogUtils;
import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.malilib.util.Color4f;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.util.math.BlockPos;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import java.util.Map;

public class HighlightRenderer {
    private static final HighlightRenderer INSTANCE = new HighlightRenderer();
    private static final Logger LOGGER = LogUtils.getLogger();

    public static HighlightRenderer getInstance() { return INSTANCE; }

    public void render() {
        render(null);
    }

    public void render(Object context) {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) return;

        Map<BlockPos, HighlightState> highlights = HighlightScanner.getHighlights();
        if (highlights.isEmpty()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        boolean xray = Configs.HIGHLIGHT_XRAY.getBooleanValue();

        fi.dy.masa.malilib.render.RenderUtils.setupBlend();
        RenderSystem.disableCull();

        if (xray) {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            GL11.glDepthRange(0.0, 0.0);
        } else {
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
        }

        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(-1.2f, -0.2f);

        float lineWidth = Math.max(2.5F, (float)client.getWindow().getFramebufferWidth() / 1920.0F * 2.5F);
        RenderSystem.lineWidth(lineWidth);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.applyModelViewMatrix();

        for (Map.Entry<BlockPos, HighlightState> entry : highlights.entrySet()) {
            Color4f c = getColor(entry.getValue());
            fi.dy.masa.litematica.render.RenderUtils.drawBlockBoundingBoxOutlinesBatchedLines(
                    entry.getKey(), c, 0.015, buffer, client
            );
        }

        BuiltBuffer meshData = null;

        try {
            meshData = buffer.end();
            BufferRenderer.drawWithGlobalProgram(meshData);
            meshData.close();
        } catch (Exception e) {
            LOGGER.warn("[容器填充] 渲染致命错误: " + e.getLocalizedMessage());
        } finally {
            if (meshData != null) {
                meshData.close();
            }
        }

        RenderSystem.polygonOffset(0f, 0f);
        RenderSystem.disablePolygonOffset();

        RenderSystem.lineWidth(1.0f);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        if (xray) {
            GL11.glDepthRange(0.0, 1.0);
        }
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
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