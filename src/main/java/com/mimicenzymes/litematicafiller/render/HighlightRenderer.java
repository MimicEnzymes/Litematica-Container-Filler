package com.mimicenzymes.litematicafiller.render;

import com.mojang.logging.LogUtils;
import com.mimicenzymes.litematicafiller.config.Configs;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.render.RenderContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
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
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) {
            clearRenderCache();
            return;
        }

        Map<BlockPos, HighlightState> highlights = HighlightScanner.getHighlights();
        if (highlights.isEmpty()) {
            clearRenderCache();
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) {
            clearRenderCache();
            return;
        }

        boolean xray = Configs.HIGHLIGHT_XRAY.getBooleanValue();
        long styleSignature = computeStyleSignature(xray);
        int highlightVersion = HighlightScanner.getHighlightVersion();
        Vec3 cameraPos = RenderUtils.camPos();

        try {
            if (cachedStyleSignature != styleSignature) {
                clearRenderCache();
                cachedStyleSignature = styleSignature;
            }

            if (cachedHighlightVersion != highlightVersion) {
                updateDesiredChunks(highlights);
                cachedHighlightVersion = highlightVersion;
            }

            rebuildDirtyChunks(xray, cameraPos, client);
            drawChunkCaches(cameraPos);
        } catch (Exception e) {
            clearRenderCache();
            LOGGER.warn("Failed to render container highlights", e);
        }
    }

    private void updateDesiredChunks(Map<BlockPos, HighlightState> highlights) {
        Map<ChunkKey, Map<BlockPos, HighlightState>> nextChunks = new HashMap<>();

        for (Map.Entry<BlockPos, HighlightState> entry : highlights.entrySet()) {
            ChunkKey key = ChunkKey.from(entry.getKey());
            nextChunks.computeIfAbsent(key, ignored -> new HashMap<>()).put(entry.getKey().immutable(), entry.getValue());
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

    private void rebuildDirtyChunks(boolean xray, Vec3 cameraPos, Minecraft client) {
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

            ChunkRenderCache cache = buildChunkCache(highlights, xray, cameraPos, client);
            if (cache == null) {
                iterator.remove();
                removeChunkCache(key);
                continue;
            }

            ChunkRenderCache oldCache = chunkCaches.put(key, cache);
            if (oldCache != null) {
                closeContext(oldCache.context);
            }

            iterator.remove();
            rebuilt++;
        }
    }

    private ChunkRenderCache buildChunkCache(Map<BlockPos, HighlightState> highlights, boolean xray, Vec3 cameraPos, Minecraft client) {
        RenderContext ctx = null;
        MeshData meshData = null;

        try {
            ctx = new RenderContext(
                    () -> "litematica_filler_lines",
                    xray ? MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_NO_DEPTH_NO_CULL : MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_OFFSET_2
            );

            var buffer = ctx.getBuilder();
            if (buffer == null) return null;

            float lineWidth = Math.max(2.5F, (float)client.getWindow().getWidth() / 1920.0F * 2.5F);
            for (Map.Entry<BlockPos, HighlightState> entry : highlights.entrySet()) {
                Color4f c = getColor(entry.getValue());
                RenderUtils.drawBlockBoundingBoxOutlinesBatchedLinesSimple(entry.getKey(), c, 0.015, lineWidth, buffer);
            }

            meshData = buffer.build();
            if (meshData == null) return null;

            ctx.upload(meshData, false);
            ChunkRenderCache cache = new ChunkRenderCache(ctx, cameraPos.x, cameraPos.y, cameraPos.z);
            ctx = null;
            return cache;
        } finally {
            if (meshData != null) {
                meshData.close();
            }
            if (ctx != null) {
                closeContext(ctx);
            }
        }
    }

    private void drawChunkCaches(Vec3 cameraPos) {
        for (ChunkRenderCache cache : chunkCaches.values()) {
            if (cache.context == null || !cache.context.isUploaded()) continue;

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
            cache.context.drawPost(false);
        } finally {
            modelViewStack.popMatrix();
        }
    }

    private long computeStyleSignature(boolean xray) {
        long sum = xray ? 0x4f1bbcdc7c3a4f31L : 0x9e3779b97f4a7c15L;
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_UNFILLED.getColor().getIntValue());
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_PARTIAL.getColor().getIntValue());
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_OVERFILLED.getColor().getIntValue());
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_WRONG.getColor().getIntValue());
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_SATISFIED.getColor().getIntValue());
        return mix64(sum ^ Configs.HIGHLIGHT_COLOR_UNKNOWN.getColor().getIntValue());
    }

    private long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private void clearRenderCache() {
        for (ChunkRenderCache cache : chunkCaches.values()) {
            closeContext(cache.context);
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
            closeContext(cache.context);
        }
    }

    private void closeContext(RenderContext ctx) {
        try {
            ctx.close();
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

    private record ChunkRenderCache(RenderContext context, double cameraX, double cameraY, double cameraZ) {}
}
