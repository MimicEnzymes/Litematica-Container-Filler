package com.mimicenzymes.litematicafiller.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.malilib.util.Color4f;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
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

        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;

            boolean xray = Configs.HIGHLIGHT_XRAY.getBooleanValue();

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();

            if (xray) {
                RenderSystem.disableDepthTest();
                RenderSystem.depthMask(false);
                GL11.glDepthRange(0.0, 0.0);
            } else {
                RenderSystem.enableDepthTest();
                RenderSystem.depthMask(true);
            }

            float lineWidth = Math.max(2.5F, (float) client.getWindow().getFramebufferWidth() / 1920.0F * 2.5F);
            RenderSystem.lineWidth(lineWidth);

            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

            try {
                java.util.function.Supplier<Object> shaderSupplier = () -> {
                    try {
                        return GameRenderer.class.getMethod("getPositionColorProgram").invoke(null);
                    } catch (Exception ignored) {
                        try {
                            return GameRenderer.class.getMethod("getPositionColorShader").invoke(null);
                        } catch (Exception ignoredAgain) {
                            return null;
                        }
                    }
                };
                RenderSystem.class.getMethod("setShader", java.util.function.Supplier.class).invoke(null, shaderSupplier);
            } catch (Exception ignored) {}

            try {
                java.lang.reflect.Method applyMatrix = RenderSystem.class.getMethod("applyModelViewMatrix");
                applyMatrix.invoke(null);
            } catch (Exception e1) {
                try {
                    java.lang.reflect.Method applyMatrix = RenderSystem.class.getMethod("method_31988");
                    applyMatrix.invoke(null);
                } catch (Exception ignored) {}
            }

            for (Map.Entry<BlockPos, HighlightState> entry : highlights.entrySet()) {
                Color4f color = getColor(entry.getValue());
                drawBoxBatched(entry.getKey(), color, 0.015, buffer, client);
            }

            Object meshData = null;
            for (java.lang.reflect.Method method : buffer.getClass().getMethods()) {
                if (method.getParameterCount() == 0 && method.getReturnType() != void.class) {
                    String name = method.getName();
                    String retName = method.getReturnType().getSimpleName();
                    if (name.equals("end") || name.equals("endNullable") || name.equals("build") || name.equals("buildOrThrow")
                            || name.equals("method_43428") || name.equals("method_60800")
                            || retName.contains("Mesh") || retName.contains("Built")) {
                        try {
                            method.setAccessible(true);
                            Object result = method.invoke(buffer);
                            if (result != null) {
                                meshData = result;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            if (meshData != null) {
                java.lang.reflect.Method drawMethod = null;
                try {
                    drawMethod = net.minecraft.client.render.BufferRenderer.class.getMethod("drawWithGlobalProgram", meshData.getClass());
                } catch (Exception ignored) {}
                if (drawMethod == null) {
                    try {
                        drawMethod = net.minecraft.client.render.BufferRenderer.class.getMethod("method_43433", meshData.getClass());
                    } catch (Exception ignored) {}
                }
                if (drawMethod == null) {
                    for (java.lang.reflect.Method method : net.minecraft.client.render.BufferRenderer.class.getDeclaredMethods()) {
                        if (java.lang.reflect.Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 1
                                && method.getParameterTypes()[0].isAssignableFrom(meshData.getClass())
                                && !method.getName().equals("draw") && !method.getName().equals("method_43438")) {
                            drawMethod = method;
                            break;
                        }
                    }
                }
                if (drawMethod != null) {
                    try {
                        drawMethod.setAccessible(true);
                        drawMethod.invoke(null, meshData);
                    } catch (Exception ignored) {}
                }

                for (java.lang.reflect.Method method : meshData.getClass().getMethods()) {
                    if ((method.getName().equals("close") || method.getName().equals("method_43429")) && method.getParameterCount() == 0) {
                        try {
                            method.invoke(meshData);
                        } catch (Exception ignored) {}
                        break;
                    }
                }
            }

            RenderSystem.lineWidth(1.0F);
            RenderSystem.depthMask(true);
            if (xray) {
                RenderSystem.enableDepthTest();
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
                GL11.glDepthRange(0.0, 1.0);
            }
            RenderSystem.enableCull();
            RenderSystem.disableBlend();

        } catch (Exception e) {
            LOGGER.warn("Failed to render container highlights", e);
        }
    }

    private void drawBoxBatched(BlockPos pos, Color4f color, double expand, BufferBuilder buffer, MinecraftClient mc) {
        Vec3d cameraPos = mc.gameRenderer.getCamera().getPos();
        double dx = cameraPos.x;
        double dy = cameraPos.y;
        double dz = cameraPos.z;

        float minX = (float) (pos.getX() - dx - expand);
        float minY = (float) (pos.getY() - dy - expand);
        float minZ = (float) (pos.getZ() - dz - expand);
        float maxX = (float) (pos.getX() - dx + expand + 1);
        float maxY = (float) (pos.getY() - dy + expand + 1);
        float maxZ = (float) (pos.getZ() - dz + expand + 1);

        fi.dy.masa.malilib.render.RenderUtils.drawBoxAllEdgesBatchedLines(minX, minY, minZ, maxX, maxY, maxZ, color, buffer);
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
