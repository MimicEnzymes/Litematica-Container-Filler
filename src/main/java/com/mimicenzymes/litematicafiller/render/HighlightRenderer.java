package com.mimicenzymes.litematicafiller.render;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import fi.dy.masa.malilib.util.Color4f;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUsage;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class HighlightRenderer {
    private static final HighlightRenderer INSTANCE = new HighlightRenderer();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long EMPTY_SIGNATURE = Long.MIN_VALUE;
    private static final int RENDER_CACHE_REGION_SHIFT = 6;
    private static final int MAX_CHUNK_REBUILDS_PER_FRAME = 2;

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
        long styleSignature = computeStyleSignature();
        int highlightVersion = HighlightScanner.getHighlightVersion();
        boolean stateApplied = false;

        try {
            Vec3d cameraPos = client.gameRenderer.getCamera().getPos();

            if (cachedStyleSignature != styleSignature) {
                clearRenderCache();
                cachedStyleSignature = styleSignature;
            }

            if (cachedHighlightVersion != highlightVersion) {
                updateDesiredChunks(highlights);
                cachedHighlightVersion = highlightVersion;
            }

            setupRenderState(client, xray);
            stateApplied = true;

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
            ChunkRenderCache oldCache = chunkCaches.put(key, cache);
            if (oldCache != null) {
                closeVertexBuffer(oldCache.vertexBuffer);
            }

            iterator.remove();
            rebuilt++;
        }
    }

    private ChunkRenderCache buildChunkCache(Map<BlockPos, HighlightState> highlights, Vec3d cameraPos) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        BuiltBuffer meshData = null;
        VertexBuffer vertexBuffer = null;
        boolean keepBuffer = false;

        try {
            for (Map.Entry<BlockPos, HighlightState> entry : highlights.entrySet()) {
                drawBoxBatched(entry.getKey(), getColor(entry.getValue()), 0.015, buffer, cameraPos);
            }

            meshData = buffer.endNullable();
            if (meshData == null) {
                throw new IllegalStateException("No vertices were generated for container highlight cache");
            }

            vertexBuffer = new VertexBuffer(GlUsage.STATIC_WRITE);
            vertexBuffer.bind();
            vertexBuffer.upload(meshData);
            meshData = null;

            ChunkRenderCache cache = new ChunkRenderCache(vertexBuffer, cameraPos.x, cameraPos.y, cameraPos.z);
            keepBuffer = true;
            return cache;
        } finally {
            VertexBuffer.unbind();
            if (meshData != null) {
                meshData.close();
            }
            if (!keepBuffer && vertexBuffer != null) {
                closeVertexBuffer(vertexBuffer);
            }
        }
    }

    private void drawChunkCaches(Vec3d cameraPos) {
        for (ChunkRenderCache cache : chunkCaches.values()) {
            if (cache.vertexBuffer == null || cache.vertexBuffer.isClosed()) continue;

            renderOffset[0] = (float) (cache.cameraX - cameraPos.x);
            renderOffset[1] = (float) (cache.cameraY - cameraPos.y);
            renderOffset[2] = (float) (cache.cameraZ - cameraPos.z);
            drawChunkCache(cache);
        }
    }

    private void drawChunkCache(ChunkRenderCache cache) {
        var modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();

        try {
            modelViewStack.translate(renderOffset[0], renderOffset[1], renderOffset[2]);
            cache.vertexBuffer.bind();
            cache.vertexBuffer.draw(RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
        } finally {
            VertexBuffer.unbind();
            modelViewStack.popMatrix();
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
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
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

    private void drawBoxBatched(BlockPos pos, Color4f color, double expand, BufferBuilder buffer, Vec3d cameraPos) {
        float minX = (float) (pos.getX() - cameraPos.x - expand);
        float minY = (float) (pos.getY() - cameraPos.y - expand);
        float minZ = (float) (pos.getZ() - cameraPos.z - expand);
        float maxX = (float) (pos.getX() - cameraPos.x + expand + 1);
        float maxY = (float) (pos.getY() - cameraPos.y + expand + 1);
        float maxZ = (float) (pos.getZ() - cameraPos.z + expand + 1);

        fi.dy.masa.malilib.render.RenderUtils.drawBoxAllEdgesBatchedLines(minX, minY, minZ, maxX, maxY, maxZ, color, buffer);
    }

    private long computeStyleSignature() {
        long sum = 0x9e3779b97f4a7c15L;
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
            closeVertexBuffer(cache.vertexBuffer);
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
            closeVertexBuffer(cache.vertexBuffer);
        }
    }

    private void closeVertexBuffer(VertexBuffer vertexBuffer) {
        if (vertexBuffer == null || vertexBuffer.isClosed()) return;

        try {
            vertexBuffer.close();
        } catch (Exception e) {
            LOGGER.warn("Failed to close container highlight render cache", e);
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

    private record ChunkRenderCache(VertexBuffer vertexBuffer, double cameraX, double cameraY, double cameraZ) {}
}
