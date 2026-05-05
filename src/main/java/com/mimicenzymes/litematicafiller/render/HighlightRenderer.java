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

import java.util.*;

public class HighlightRenderer {
    private static final HighlightRenderer INSTANCE = new HighlightRenderer();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long EMPTY_SIGNATURE = Long.MIN_VALUE;
    private static final int RENDER_CACHE_REGION_SHIFT = 6;
    private static final int MAX_CHUNK_REBUILDS_PER_FRAME = 1;

    private final Map<ChunkKey, ChunkRenderCache> chunkCaches = new HashMap<>();
    private final Map<ChunkKey, Map<BlockPos, HighlightState>> desiredChunks = new HashMap<>();
    private final Set<ChunkKey> dirtyChunks = new LinkedHashSet<>();
    private final float[] renderOffset = new float[3];
    private int cachedHighlightVersion = -1;
    private long cachedStyleSignature = EMPTY_SIGNATURE;

    public static HighlightRenderer getInstance() { return INSTANCE; }

    public void render() {
        render(null);
    }

    public void render(Object context) {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) {
            clearRenderCache();
            return;
        }

        Map<BlockPos, HighlightState> highlights = HighlightScanner.getHighlights();
        if (highlights.isEmpty()) {
            clearRenderCache();
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || client.player == null) {
            clearRenderCache();
            return;
        }

        boolean xray = Configs.HIGHLIGHT_XRAY.getBooleanValue();
        long styleSignature = computeStyleSignature(xray);
        int highlightVersion = HighlightScanner.getHighlightVersion();
        Vec3d cameraPos = client.gameRenderer.getCamera().getPos();
        boolean stateApplied = false;

        try {
            setupRenderState(client, xray);
            stateApplied = true;

            if (cachedStyleSignature != styleSignature) {
                clearRenderCache();
                cachedStyleSignature = styleSignature;
            }

            if (cachedHighlightVersion != highlightVersion) {
                updateDesiredChunks(highlights);
                cachedHighlightVersion = highlightVersion;
            }

            rebuildDirtyChunks(cameraPos);
            drawChunkCaches(cameraPos);
        } catch (Exception e) {
            clearRenderCache();
            LOGGER.warn("Failed to render container highlights", e);
        } finally {
            if (stateApplied) {
                restoreRenderState(xray);
            }
        }
    }

    private void updateDesiredChunks(Map<BlockPos, HighlightState> highlights) {
        Map<ChunkKey, Map<BlockPos, HighlightState>> nextChunks = new HashMap<>();

        for (Map.Entry<BlockPos, HighlightState> entry : highlights.entrySet()) {
            ChunkKey key = ChunkKey.from(entry.getKey());
            nextChunks.computeIfAbsent(key, ignored -> new HashMap<>()).put(entry.getKey().toImmutable(), entry.getValue());
        }

        Iterator<ChunkKey> existing = desiredChunks.keySet().iterator();
        while (existing.hasNext()) {
            ChunkKey key = existing.next();
            if (!nextChunks.containsKey(key)) {
                existing.remove();
                dirtyChunks.remove(key);
                removeChunkCache(key);
            }
        }

        for (Map.Entry<ChunkKey, Map<BlockPos, HighlightState>> entry : nextChunks.entrySet()) {
            Map<BlockPos, HighlightState> previous = desiredChunks.get(entry.getKey());
            if (!entry.getValue().equals(previous)) {
                desiredChunks.put(entry.getKey(), entry.getValue());
                dirtyChunks.add(entry.getKey());
            }
        }
    }

    private void rebuildDirtyChunks(Vec3d cameraPos) {
        int rebuilt = 0;
        Iterator<ChunkKey> iterator = dirtyChunks.iterator();

        while (iterator.hasNext() && rebuilt < MAX_CHUNK_REBUILDS_PER_FRAME) {
            ChunkKey key = iterator.next();
            Map<BlockPos, HighlightState> highlights = desiredChunks.get(key);

            if (highlights == null || highlights.isEmpty()) {
                iterator.remove();
                removeChunkCache(key);
                continue;
            }

            ChunkRenderCache cache = buildChunkCache(highlights, cameraPos);
            if (cache == null) {
                iterator.remove();
                removeChunkCache(key);
                continue;
            }

            ChunkRenderCache oldCache = chunkCaches.put(key, cache);
            if (oldCache != null) {
                closeMesh(oldCache.meshData);
            }

            iterator.remove();
            rebuilt++;
        }
    }

    private ChunkRenderCache buildChunkCache(Map<BlockPos, HighlightState> highlights, Vec3d cameraPos) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        Object meshData = null;
        boolean keepMesh = false;

        try {
            for (Map.Entry<BlockPos, HighlightState> entry : highlights.entrySet()) {
                Color4f color = getColor(entry.getValue());
                drawBoxBatched(entry.getKey(), color, 0.015, buffer, cameraPos);
            }

            meshData = endBuffer(buffer);
            if (meshData == null) return null;

            ChunkRenderCache cache = new ChunkRenderCache(meshData, cameraPos.x, cameraPos.y, cameraPos.z);
            keepMesh = true;
            return cache;
        } finally {
            if (!keepMesh && meshData != null) {
                closeMesh(meshData);
            }
        }
    }

    private void drawChunkCaches(Vec3d cameraPos) {
        for (ChunkRenderCache cache : chunkCaches.values()) {
            if (cache.meshData == null) continue;

            renderOffset[0] = (float)(cache.cameraX - cameraPos.x);
            renderOffset[1] = (float)(cache.cameraY - cameraPos.y);
            renderOffset[2] = (float)(cache.cameraZ - cameraPos.z);
            drawChunkCache(cache);
        }
    }

    private void drawChunkCache(ChunkRenderCache cache) {
        var modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();

        try {
            modelViewStack.translate(renderOffset[0], renderOffset[1], renderOffset[2]);
            applyModelViewMatrix();
            drawMesh(cache.meshData);
        } finally {
            modelViewStack.popMatrix();
            applyModelViewMatrix();
        }
    }

    private void setupRenderState(MinecraftClient client, boolean xray) {
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

        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(-1.2f, -0.2f);
        float lineWidth = Math.max(2.5F, (float) client.getWindow().getFramebufferWidth() / 1920.0F * 2.5F);
        RenderSystem.lineWidth(lineWidth);
        setPositionColorShader();
        applyModelViewMatrix();
    }

    private void restoreRenderState(boolean xray) {
        RenderSystem.polygonOffset(0f, 0f);
        RenderSystem.disablePolygonOffset();
        RenderSystem.lineWidth(1.0F);
        RenderSystem.depthMask(true);
        if (xray) {
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            GL11.glDepthRange(0.0, 1.0);
        }
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private void setPositionColorShader() {
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

        try {
            RenderSystem.class.getMethod("setShader", java.util.function.Supplier.class).invoke(null, shaderSupplier);
        } catch (Exception ignored) {}
    }

    private void applyModelViewMatrix() {
        try {
            RenderSystem.class.getMethod("applyModelViewMatrix").invoke(null);
        } catch (Exception ignored) {
            try {
                RenderSystem.class.getMethod("method_31988").invoke(null);
            } catch (Exception ignoredAgain) {}
        }
    }

    private Object endBuffer(BufferBuilder buffer) {
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
                        if (result != null) return result;
                    } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    private void drawMesh(Object meshData) {
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
    }

    private void closeMesh(Object meshData) {
        if (meshData == null) return;

        for (java.lang.reflect.Method method : meshData.getClass().getMethods()) {
            if ((method.getName().equals("close") || method.getName().equals("method_43429")) && method.getParameterCount() == 0) {
                try {
                    method.invoke(meshData);
                } catch (Exception ignored) {}
                break;
            }
        }
    }

    private void drawBoxBatched(BlockPos pos, Color4f color, double expand, BufferBuilder buffer, Vec3d cameraPos) {
        float minX = (float) (pos.getX() - cameraPos.x - expand);
        float minY = (float) (pos.getY() - cameraPos.y - expand);
        float minZ = (float) (pos.getZ() - cameraPos.z - expand);
        float maxX = (float) (pos.getX() - cameraPos.x + expand + 1);
        float maxY = (float) (pos.getY() - cameraPos.y + expand + 1);
        float maxZ = (float) (pos.getZ() - cameraPos.z + expand + 1);

        fi.dy.masa.malilib.render.RenderUtils.drawBoxAllEdgesBatchedLines(minX, minY, minZ, maxX, maxY, maxZ, color, buffer);
    }

    private long computeStyleSignature(boolean xray) {
        long sum = xray ? 0x4f1bbcdc7c3a4f31L : 0x9e3779b97f4a7c15L;
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_UNFILLED.getColor().intValue);
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_PARTIAL.getColor().intValue);
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_OVERFILLED.getColor().intValue);
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_WRONG.getColor().intValue);
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_SATISFIED.getColor().intValue);
        return mix64(sum ^ Configs.HIGHLIGHT_COLOR_UNKNOWN.getColor().intValue);
    }

    private long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private void clearRenderCache() {
        for (ChunkRenderCache cache : chunkCaches.values()) {
            closeMesh(cache.meshData);
        }
        chunkCaches.clear();
        desiredChunks.clear();
        dirtyChunks.clear();
        cachedHighlightVersion = -1;
        cachedStyleSignature = EMPTY_SIGNATURE;
    }

    private void removeChunkCache(ChunkKey key) {
        ChunkRenderCache cache = chunkCaches.remove(key);
        if (cache != null) {
            closeMesh(cache.meshData);
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

    private record ChunkKey(int x, int z) {
        static ChunkKey from(BlockPos pos) {
            return new ChunkKey(pos.getX() >> RENDER_CACHE_REGION_SHIFT, pos.getZ() >> RENDER_CACHE_REGION_SHIFT);
        }
    }

    private record ChunkRenderCache(Object meshData, double cameraX, double cameraY, double cameraZ) {}
}
