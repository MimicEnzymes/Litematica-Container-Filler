package com.mimicenzymes.litematicafiller.render;

import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.render.RenderContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public class HighlightRenderer {
    private static final HighlightRenderer INSTANCE = new HighlightRenderer();
    public static HighlightRenderer getInstance() { return INSTANCE; }

    public void render() {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) return;

        Map<BlockPos, HighlightState> highlights = HighlightScanner.getHighlights();
        if (highlights.isEmpty()) return;

        try {
            boolean xray = Configs.HIGHLIGHT_XRAY.getBooleanValue();

            RenderContext ctx = new RenderContext(
                    () -> "litematica_filler_lines",
                    xray ? MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_NO_DEPTH_NO_CULL : MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_OFFSET_2
            );

            var buffer = ctx.getBuilder();
            if (buffer == null) return;

            Minecraft client = Minecraft.getInstance();
            float lineWidth = client != null ? Math.max(2.5F, (float)client.getWindow().getWidth() / 1920.0F * 2.5F) : 2.0f;

            for (Map.Entry<BlockPos, HighlightState> entry : highlights.entrySet()) {
                Color4f c = getColor(entry.getValue());
                RenderUtils.drawBlockBoundingBoxOutlinesBatchedLinesSimple(entry.getKey(), c, 0.015, lineWidth, buffer);
            }

            Object meshData = null;
            for (java.lang.reflect.Method m : buffer.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() != void.class) {
                    String name = m.getName();
                    String retName = m.getReturnType().getSimpleName();

                    if (name.equals("build") || name.equals("end") || name.equals("endNullable") || name.equals("buildOrThrow")
                            || name.equals("method_43428") || name.equals("method_60800")
                            || retName.contains("Mesh") || retName.contains("Built")) {

                        try {
                            m.setAccessible(true);
                            Object result = m.invoke(buffer);
                            if (result != null) {
                                meshData = result;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            if (meshData != null) {
                for (java.lang.reflect.Method m : ctx.getClass().getMethods()) {
                    if (m.getName().equals("draw") && m.getParameterCount() == 3) {
                        Class<?>[] params = m.getParameterTypes();
                        if (params[0].isInstance(meshData) && params[1] == boolean.class && params[2] == boolean.class) {
                            m.invoke(ctx, meshData, false, true);
                            break;
                        }
                    }
                }

                // 兼容生产环境的 close 混淆名
                for (java.lang.reflect.Method m : meshData.getClass().getMethods()) {
                    if ((m.getName().equals("close") || m.getName().equals("method_43429")) && m.getParameterCount() == 0) {
                        m.invoke(meshData);
                        break;
                    }
                }
            }

            ctx.reset();

        } catch (Throwable e) {
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.player != null && client.level != null) {
                if (client.level.getGameTime() % 60 == 0) {
                    client.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("§c[容器填充机] 渲染错误: " + e.getMessage()));
                }
            }
            e.printStackTrace();
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