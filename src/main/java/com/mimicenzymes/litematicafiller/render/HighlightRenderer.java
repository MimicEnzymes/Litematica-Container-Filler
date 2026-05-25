package com.mimicenzymes.litematicafiller.render;

import com.mojang.logging.LogUtils;
import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.AutoFillerStateMachine;
import com.mimicenzymes.litematicafiller.core.LitematicaContainerReader;
import com.mojang.blaze3d.systems.RenderSystem;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.render.RenderContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.world.level.block.state.BlockState;
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
    private ChunkRenderCache taskOverlayCache = null;
    private long taskOverlaySignature = EMPTY_SIGNATURE;
    private long taskOverlayFrame = Long.MIN_VALUE;

    public static HighlightRenderer getInstance() { return INSTANCE; }

    public void render() {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) {
            clearRenderCache();
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.player == null) {
            clearRenderCache();
            return;
        }

        AutoFillerStateMachine filler = AutoFillerStateMachine.getInstance();
        Map<BlockPos, HighlightState> highlights = HighlightScanner.getHighlights();
        if (highlights.isEmpty() && !filler.hasRenderableTaskMarkers()) {
            clearRenderCache();
            return;
        }

        BlockPos currentTaskPos = filler.getCurrentTaskPos();
        Set<BlockPos> queuedTaskPositions = filler.getQueuedTaskPositions();
        Set<BlockPos> missingMaterialPositions = filler.getMissingMaterialPositions();
        Set<BlockPos> recentFillingPositions = filler.getRecentFillingPositions();
        if (highlights.isEmpty()) {
            clearRenderCache();
            if (hasTaskOverlay(currentTaskPos, queuedTaskPositions, missingMaterialPositions, recentFillingPositions)) {
                drawTaskOverlays(RenderUtils.camPos(), Configs.HIGHLIGHT_XRAY.getBooleanValue(), currentTaskPos, queuedTaskPositions, missingMaterialPositions, recentFillingPositions);
            }
            return;
        }

        boolean xray = Configs.HIGHLIGHT_XRAY.getBooleanValue();
        long styleSignature = computeStyleSignature(xray);
        int highlightVersion = HighlightScanner.getHighlightVersion();
        var cameraPos = RenderUtils.camPos();

        try {
            if (cachedStyleSignature != styleSignature) {
                clearRenderCache();
                cachedStyleSignature = styleSignature;
            }

            if (cachedHighlightVersion != highlightVersion) {
                updateDesiredChunks(highlights);
                cachedHighlightVersion = highlightVersion;
            }

            rebuildDirtyChunks(xray, cameraPos);
            drawChunkCaches(cameraPos);
            drawTaskOverlays(cameraPos, xray, currentTaskPos, queuedTaskPositions, missingMaterialPositions, recentFillingPositions);
        } catch (Exception e) {
            clearRenderCache();
            LOGGER.warn("Failed to render container highlights", e);
        }
    }

    private void updateDesiredChunks(Map<BlockPos, HighlightState> highlights) {
        Map<ChunkKey, Map<BlockPos, HighlightState>> nextChunks = new HashMap<>();

        for (Map.Entry<BlockPos, HighlightState> entry : highlights.entrySet()) {
            if (!shouldRenderState(entry.getValue())) continue;
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

    private void rebuildDirtyChunks(boolean xray, Vec3 cameraPos) {
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

            ChunkRenderCache cache = buildChunkCache(highlights, xray, cameraPos);
            if (cache == null) {
                iterator.remove();
                removeChunkCache(key);
                continue;
            }

            ChunkRenderCache oldCache = chunkCaches.put(key, cache);
            if (oldCache != null) {
                closeContext(oldCache.fillContext);
                closeContext(oldCache.lineContext);
            }

            iterator.remove();
            rebuilt++;
        }
    }

    private ChunkRenderCache buildChunkCache(Map<BlockPos, HighlightState> highlights, boolean xray, Vec3 cameraPos) {
        RenderContext fillCtx = null;
        MeshData fillMeshData = null;

        try {
            fillCtx = new RenderContext(
                    () -> "litematica_filler_glass",
                    xray ? MaLiLibPipelines.POSITION_COLOR_TRANSLUCENT_NO_DEPTH_NO_CULL : MaLiLibPipelines.POSITION_COLOR_TRANSLUCENT_LEQUAL_DEPTH_OFFSET_2
            );

            var fillBuffer = fillCtx.getBuilder();
            if (fillBuffer == null) return null;

            for (Map.Entry<BlockPos, HighlightState> entry : highlights.entrySet()) {
                Color4f c = getColor(entry.getValue());
                float alphaMultiplier = (float) Configs.HIGHLIGHT_GLASS_ALPHA_MULTIPLIER.getDoubleValue();
                Color4f glass = new Color4f(c.r, c.g, c.b, Math.min(0.24f, Math.max(0.04f, c.a * alphaMultiplier)));
                Color4f crown = new Color4f(c.r, c.g, c.b, Math.min(0.34f, Math.max(0.12f, c.a * 0.36f)));
                HighlightBox box = getHighlightBox(entry.getKey());

                if (Configs.RENDER_STATE_GLASS.getBooleanValue()) {
                    drawInflatedWorldBox(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(), 0.012f, glass, cameraPos, fillBuffer);
                }
                if (Configs.RENDER_STATE_TOP_PLATE.getBooleanValue()) {
                    float inset = Math.max(0.02f, (1.0f - (float) Configs.HIGHLIGHT_TOP_PLATE_SIZE.getDoubleValue()) * 0.5f);
                    drawWorldBox(
                            box.minX() + inset, box.maxY() + 0.035f, box.minZ() + inset,
                            box.maxX() - inset, box.maxY() + 0.095f, box.maxZ() - inset,
                            crown, cameraPos, fillBuffer
                    );
                }
            }

            fillMeshData = fillBuffer.build();
            if (fillMeshData == null) return null;

            fillCtx.upload(fillMeshData, false);
            ChunkRenderCache cache = new ChunkRenderCache(null, fillCtx, cameraPos.x, cameraPos.y, cameraPos.z);
            fillCtx = null;
            return cache;
        } finally {
            if (fillMeshData != null) fillMeshData.close();
            closeContext(fillCtx);
        }
    }

    private void drawChunkCaches(Vec3 cameraPos) {
        for (ChunkRenderCache cache : chunkCaches.values()) {
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
            if (cache.fillContext != null && cache.fillContext.isUploaded()) {
                cache.fillContext.drawPost(false);
            }
            if (cache.lineContext != null && cache.lineContext.isUploaded()) {
                cache.lineContext.drawPost(false);
            }
        } finally {
            modelViewStack.popMatrix();
        }
    }

    private boolean hasTaskOverlay(BlockPos currentTaskPos, Set<BlockPos> queuedTaskPositions, Set<BlockPos> missingMaterialPositions, Set<BlockPos> recentFillingPositions) {
        return (Configs.RENDER_FILLING_ARROW.getBooleanValue() && currentTaskPos != null)
                || (Configs.RENDER_QUEUED_SPINNER.getBooleanValue() && !queuedTaskPositions.isEmpty())
                || (Configs.RENDER_MISSING_MATERIAL_MARKER.getBooleanValue() && !missingMaterialPositions.isEmpty());
    }

    private void drawTaskOverlays(Vec3 cameraPos, boolean xray, BlockPos currentTaskPos, Set<BlockPos> queuedTaskPositions, Set<BlockPos> missingMaterialPositions, Set<BlockPos> recentFillingPositions) {
        if (!hasTaskOverlay(currentTaskPos, queuedTaskPositions, missingMaterialPositions, recentFillingPositions)) {
            clearTaskOverlayCache();
            return;
        }

        RenderContext ctx = null;
        MeshData meshData = null;
        try {
            int fpsLimit = Configs.TASK_MARKER_ANIMATION_FPS.getIntegerValue();
            double rawTime = System.nanoTime() / 1_000_000_000.0D;
            long frame = fpsLimit <= 0 ? System.nanoTime() : (long)Math.floor(rawTime * fpsLimit);
            double time = fpsLimit <= 0 ? rawTime : frame / (double)fpsLimit;
            long signature = computeTaskOverlaySignature(xray, currentTaskPos, queuedTaskPositions, missingMaterialPositions);
            if (taskOverlayCache != null && taskOverlaySignature == signature && taskOverlayFrame == frame) {
                renderOffset[0] = (float)(taskOverlayCache.cameraX - cameraPos.x);
                renderOffset[1] = (float)(taskOverlayCache.cameraY - cameraPos.y);
                renderOffset[2] = (float)(taskOverlayCache.cameraZ - cameraPos.z);
                drawChunkCache(taskOverlayCache);
                return;
            }

            ctx = new RenderContext(
                    () -> "litematica_filler_task_overlays",
                    xray ? MaLiLibPipelines.POSITION_COLOR_TRANSLUCENT_NO_DEPTH_NO_CULL : MaLiLibPipelines.POSITION_COLOR_TRANSLUCENT_LEQUAL_DEPTH_OFFSET_2
            );

            var buffer = ctx.getBuilder();
            if (buffer == null) return;

            if (Configs.RENDER_FILLING_ARROW.getBooleanValue()) {
                if (currentTaskPos != null) {
                    drawFillingArrow(getHighlightBox(currentTaskPos), cameraPos, time, buffer);
                }
            }

            if (Configs.RENDER_QUEUED_SPINNER.getBooleanValue()) {
                int count = 0;
                int maxQueued = Configs.MAX_QUEUED_RENDER_OVERLAYS.getIntegerValue();
                for (BlockPos pos : queuedTaskPositions) {
                    if (pos == null || pos.equals(currentTaskPos)) continue;
                    if (count++ >= maxQueued) break;
                    drawQueuedSpinner(getHighlightBox(pos), cameraPos, time + count * 0.17D, buffer);
                }
            }

            if (Configs.RENDER_MISSING_MATERIAL_MARKER.getBooleanValue()) {
                int count = 0;
                int maxMissing = Configs.MAX_QUEUED_RENDER_OVERLAYS.getIntegerValue();
                for (BlockPos pos : missingMaterialPositions) {
                    if (count++ >= maxMissing) break;
                    if (pos != null) drawMissingMaterialMarker(getHighlightBox(pos), cameraPos, time, buffer);
                }
            }

            meshData = buffer.build();
            if (meshData == null) return;

            ctx.upload(meshData, false);
            ChunkRenderCache cache = new ChunkRenderCache(null, ctx, cameraPos.x, cameraPos.y, cameraPos.z);
            ctx = null;
            clearTaskOverlayCache();
            taskOverlayCache = cache;
            taskOverlaySignature = signature;
            taskOverlayFrame = frame;

            renderOffset[0] = 0.0f;
            renderOffset[1] = 0.0f;
            renderOffset[2] = 0.0f;
            drawChunkCache(cache);
        } catch (Exception e) {
            clearTaskOverlayCache();
            LOGGER.warn("Failed to render container task overlays", e);
        } finally {
            if (meshData != null) meshData.close();
            closeContext(ctx);
        }
    }

    private void drawFillingArrow(HighlightBox box, Vec3 cameraPos, double time, com.mojang.blaze3d.vertex.BufferBuilder buffer) {
        float cx = box.centerX();
        float cz = box.centerZ();
        float scale = (float) Configs.TASK_OVERLAY_SCALE.getDoubleValue();
        float y = box.maxY() + 0.64f + (float)Math.sin(time * 5.0D) * 0.045f;
        float pulse = 0.5f + 0.5f * (float)Math.sin(time * 7.0D);
        Color4f base = Configs.HIGHLIGHT_COLOR_FILLING.getColor();
        Color4f body = new Color4f(base.r, base.g, base.b, Math.min(0.82f, base.a * (0.50f + pulse * 0.12f)));
        Color4f core = new Color4f(0.82f, 1.0f, 0.96f, 0.36f);
        Color4f glow = new Color4f(base.r, base.g, base.b, 0.12f);

        drawVerticalDownArrow(cx, y, cz, 0.24f * scale, 0.62f * scale, 0.080f * scale, body, cameraPos, buffer);
        drawVerticalDownArrow(cx, y + 0.010f * scale, cz, 0.135f * scale, 0.39f * scale, 0.046f * scale, core, cameraPos, buffer);
        drawCenteredWorldBox(cx, box.maxY() + 0.045f, cz, (0.24f + pulse * 0.05f) * scale, 0.020f * scale, glow, cameraPos, buffer);
    }

    private void drawQueuedSpinner(HighlightBox box, Vec3 cameraPos, double time, com.mojang.blaze3d.vertex.BufferBuilder buffer) {
        float cx = box.centerX();
        float cz = box.centerZ();
        float scale = (float) Configs.TASK_OVERLAY_SCALE.getDoubleValue();
        float cy = box.maxY() + 0.34f + (float)Math.sin(time * 2.4D) * 0.028f;
        float radius = 0.32f * scale;
        Color4f base = Configs.HIGHLIGHT_COLOR_QUEUED.getColor();

        for (int i = 0; i < 8; i++) {
            double angle = time * 3.2D + i * Math.PI / 4.0D;
            float x = cx + (float)Math.cos(angle) * radius;
            float z = cz + (float)Math.sin(angle) * radius;
            float alpha = Math.min(0.70f, base.a * (0.16f + i * 0.055f));
            Color4f color = new Color4f(base.r, base.g, base.b, alpha);
            drawCenteredWorldBox(x, cy, z, 0.058f * scale, 0.035f * scale, color, cameraPos, buffer);
        }

        Color4f hub = new Color4f(base.r, base.g, base.b, 0.12f);
        drawCenteredWorldBox(cx, cy, cz, 0.15f * scale, 0.025f * scale, hub, cameraPos, buffer);
    }

    private void drawMissingMaterialMarker(HighlightBox box, Vec3 cameraPos, double time, com.mojang.blaze3d.vertex.BufferBuilder buffer) {
        float scale = (float) Configs.TASK_OVERLAY_SCALE.getDoubleValue();
        float cx = box.centerX();
        float cz = box.centerZ();
        float cy = box.maxY() + 0.34f + (float)Math.sin(time * 4.4D) * 0.035f;
        Color4f base = Configs.HIGHLIGHT_COLOR_MISSING_MATERIAL.getColor();
        float pulse = 0.5f + 0.5f * (float)Math.sin(time * 8.0D);
        Color4f color = new Color4f(base.r, base.g, base.b, Math.min(0.82f, base.a * (0.42f + pulse * 0.22f)));

        drawCenteredWorldBox(cx, cy + 0.24f * scale, cz, 0.070f * scale, 0.045f * scale, color, cameraPos, buffer);
        drawCenteredWorldBox(cx, cy, cz, 0.060f * scale, 0.19f * scale, color, cameraPos, buffer);
        drawCenteredWorldBox(cx, cy - 0.30f * scale, cz, 0.070f * scale, 0.050f * scale, color, cameraPos, buffer);
    }

    private void drawVerticalDownArrow(float cx, float cy, float cz, float halfWidth, float height, float halfDepth, Color4f color, Vec3 cameraPos, com.mojang.blaze3d.vertex.BufferBuilder buffer) {
        float shaftHalf = halfWidth * 0.26f;
        float shaftTop = cy + height * 0.44f;
        float shaftBottom = cy - height * 0.04f;
        float headTop = cy - height * 0.02f;
        float tipY = cy - height * 0.48f;
        float[] headXs = {cx - halfWidth, cx + halfWidth, cx};
        float[] headYs = {headTop, headTop, tipY};

        drawWorldBox(cx - shaftHalf, shaftBottom, cz - halfDepth, cx + shaftHalf, shaftTop, cz + halfDepth, color, cameraPos, buffer);
        drawWorldPrism(headXs, headYs, cz, halfDepth, color, cameraPos, buffer);
    }

    private void drawWorldPrism(float[] xs, float[] ys, float cz, float halfDepth, Color4f color, Vec3 cameraPos, com.mojang.blaze3d.vertex.BufferBuilder buffer) {
        if (xs.length < 3 || xs.length != ys.length) return;

        float frontZ = (float)(cz - halfDepth - cameraPos.z);
        float backZ = (float)(cz + halfDepth - cameraPos.z);

        for (int i = 1; i + 1 < xs.length; i++) {
            vertex(xs[0], ys[0], frontZ, color, cameraPos, buffer);
            vertex(xs[i], ys[i], frontZ, color, cameraPos, buffer);
            vertex(xs[i + 1], ys[i + 1], frontZ, color, cameraPos, buffer);
            vertex(xs[i + 1], ys[i + 1], frontZ, color, cameraPos, buffer);

            vertex(xs[0], ys[0], backZ, color, cameraPos, buffer);
            vertex(xs[i + 1], ys[i + 1], backZ, color, cameraPos, buffer);
            vertex(xs[i], ys[i], backZ, color, cameraPos, buffer);
            vertex(xs[i], ys[i], backZ, color, cameraPos, buffer);
        }

        for (int i = 0; i < xs.length; i++) {
            int next = (i + 1) % xs.length;
            vertex(xs[i], ys[i], frontZ, color, cameraPos, buffer);
            vertex(xs[next], ys[next], frontZ, color, cameraPos, buffer);
            vertex(xs[next], ys[next], backZ, color, cameraPos, buffer);
            vertex(xs[i], ys[i], backZ, color, cameraPos, buffer);
        }
    }

    private void vertex(float worldX, float worldY, float cameraRelativeZ, Color4f color, Vec3 cameraPos, com.mojang.blaze3d.vertex.BufferBuilder buffer) {
        buffer.addVertex((float)(worldX - cameraPos.x), (float)(worldY - cameraPos.y), cameraRelativeZ).setColor(color.r, color.g, color.b, color.a);
    }

    private void drawCenteredWorldBox(float cx, float cy, float cz, float halfSize, float halfHeight, Color4f color, Vec3 cameraPos, com.mojang.blaze3d.vertex.BufferBuilder buffer) {
        drawWorldBox(
                cx - halfSize, cy - halfHeight, cz - halfSize,
                cx + halfSize, cy + halfHeight, cz + halfSize,
                color, cameraPos, buffer
        );
    }

    private void drawInflatedWorldBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, float inflate, Color4f color, Vec3 cameraPos, com.mojang.blaze3d.vertex.BufferBuilder buffer) {
        drawWorldBox(
                minX - inflate, minY - inflate, minZ - inflate,
                maxX + inflate, maxY + inflate, maxZ + inflate,
                color, cameraPos, buffer
        );
    }

    private void drawWorldBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, Color4f color, Vec3 cameraPos, com.mojang.blaze3d.vertex.BufferBuilder buffer) {
        RenderUtils.drawBoxAllSidesBatchedQuads(
                (float)(minX - cameraPos.x), (float)(minY - cameraPos.y), (float)(minZ - cameraPos.z),
                (float)(maxX - cameraPos.x), (float)(maxY - cameraPos.y), (float)(maxZ - cameraPos.z),
                color, buffer
        );
    }

    private long computeStyleSignature(boolean xray) {
        long sum = xray ? 0x4f1bbcdc7c3a4f31L : 0x9e3779b97f4a7c15L;
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_UNFILLED.getColor().getIntValue());
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_PARTIAL.getColor().getIntValue());
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_OVERFILLED.getColor().getIntValue());
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_WRONG.getColor().getIntValue());
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_SATISFIED.getColor().getIntValue());
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_UNKNOWN.getColor().getIntValue());
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_UNPLACED.getColor().getIntValue());
        sum = mix64(sum ^ (Configs.RENDER_STATE_GLASS.getBooleanValue() ? 1L : 0L));
        sum = mix64(sum ^ (Configs.RENDER_STATE_TOP_PLATE.getBooleanValue() ? 2L : 0L));
        sum = mix64(sum ^ Double.doubleToLongBits(Configs.HIGHLIGHT_GLASS_ALPHA_MULTIPLIER.getDoubleValue()));
        return mix64(sum ^ Double.doubleToLongBits(Configs.HIGHLIGHT_TOP_PLATE_SIZE.getDoubleValue()));
    }

    private long computeTaskOverlaySignature(boolean xray, BlockPos currentTaskPos, Set<BlockPos> queuedTaskPositions, Set<BlockPos> missingMaterialPositions) {
        long sum = xray ? 0x31cb2ad18e5c3f01L : 0x59232765f0aa67bdL;
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_FILLING.getColor().getIntValue());
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_QUEUED.getColor().getIntValue());
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_MISSING_MATERIAL.getColor().getIntValue());
        sum = mix64(sum ^ Double.doubleToLongBits(Configs.TASK_OVERLAY_SCALE.getDoubleValue()));
        sum = mix64(sum ^ (Configs.RENDER_FILLING_ARROW.getBooleanValue() ? 0x101L : 0L));
        sum = mix64(sum ^ (Configs.RENDER_QUEUED_SPINNER.getBooleanValue() ? 0x202L : 0L));
        sum = mix64(sum ^ (Configs.RENDER_MISSING_MATERIAL_MARKER.getBooleanValue() ? 0x404L : 0L));
        sum = mix64(sum ^ Configs.MAX_QUEUED_RENDER_OVERLAYS.getIntegerValue());
        if (currentTaskPos != null) {
            sum = mix64(sum ^ currentTaskPos.asLong());
        }
        int count = 0;
        int maxQueued = Configs.MAX_QUEUED_RENDER_OVERLAYS.getIntegerValue();
        for (BlockPos pos : queuedTaskPositions) {
            if (pos == null || pos.equals(currentTaskPos)) continue;
            if (count++ >= maxQueued) break;
            sum = mix64(sum ^ pos.asLong());
        }
        count = 0;
        int maxMissing = Configs.MAX_QUEUED_RENDER_OVERLAYS.getIntegerValue();
        for (BlockPos pos : missingMaterialPositions) {
            if (pos == null) continue;
            if (count++ >= maxMissing) break;
            sum = mix64(sum ^ Long.rotateLeft(pos.asLong(), 17));
        }
        return sum;
    }

    private long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private void clearRenderCache() {
        for (ChunkRenderCache cache : chunkCaches.values()) {
            closeContext(cache.fillContext);
            closeContext(cache.lineContext);
        }
        chunkCaches.clear();
        desiredChunks.clear();
        dirtyChunks.clear();
        clearTaskOverlayCache();
        cachedHighlightVersion = -1;
        cachedStyleSignature = EMPTY_SIGNATURE;
    }

    private void clearTaskOverlayCache() {
        if (taskOverlayCache != null) {
            closeContext(taskOverlayCache.fillContext);
            closeContext(taskOverlayCache.lineContext);
            taskOverlayCache = null;
        }
        taskOverlaySignature = EMPTY_SIGNATURE;
        taskOverlayFrame = Long.MIN_VALUE;
    }

    private void removeChunkCache(ChunkKey key) {
        ChunkRenderCache cache = chunkCaches.remove(key);
        if (cache != null) {
            closeContext(cache.fillContext);
            closeContext(cache.lineContext);
        }
    }

    private void closeContext(RenderContext ctx) {
        if (ctx == null) return;
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
            case UNPLACED -> Configs.HIGHLIGHT_COLOR_UNPLACED.getColor();
            default -> Configs.HIGHLIGHT_COLOR_UNKNOWN.getColor();
        };
    }

    private boolean shouldRenderState(HighlightState state) {
        return switch (state) {
            case UNFILLED -> Configs.RENDER_STATE_UNFILLED.getBooleanValue();
            case PARTIAL -> Configs.RENDER_STATE_PARTIAL.getBooleanValue();
            case OVERFILLED -> Configs.RENDER_STATE_OVERFILLED.getBooleanValue();
            case WRONG_ITEM -> Configs.RENDER_STATE_WRONG.getBooleanValue();
            case SATISFIED -> Configs.RENDER_STATE_SATISFIED.getBooleanValue();
            case UNPLACED -> Configs.HIGHLIGHT_UNPLACED_CONTAINERS.getBooleanValue();
            default -> Configs.RENDER_STATE_UNKNOWN.getBooleanValue();
        };
    }

    private HighlightBox getHighlightBox(BlockPos pos) {
        var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null || pos == null) {
            return HighlightBox.single(pos == null ? BlockPos.ZERO : pos);
        }

        BlockState state = schematicWorld.getBlockState(pos);
        BlockPos[] halves = LitematicaContainerReader.getRenderContainerHalves(schematicWorld, pos, state);
        if (halves == null) {
            return HighlightBox.single(pos);
        }

        return HighlightBox.of(halves[0], halves[1]);
    }

    private record ChunkKey(int x, int z) {
        static ChunkKey from(BlockPos pos) {
            return new ChunkKey(pos.getX() >> RENDER_CACHE_REGION_SHIFT, pos.getZ() >> RENDER_CACHE_REGION_SHIFT);
        }
    }

    private record ChunkRenderCache(RenderContext lineContext, RenderContext fillContext, double cameraX, double cameraY, double cameraZ) {}

    private record HighlightBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        static HighlightBox single(BlockPos pos) {
            return new HighlightBox(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0f, pos.getY() + 1.0f, pos.getZ() + 1.0f);
        }

        static HighlightBox of(BlockPos first, BlockPos second) {
            return new HighlightBox(
                    Math.min(first.getX(), second.getX()),
                    Math.min(first.getY(), second.getY()),
                    Math.min(first.getZ(), second.getZ()),
                    Math.max(first.getX(), second.getX()) + 1.0f,
                    Math.max(first.getY(), second.getY()) + 1.0f,
                    Math.max(first.getZ(), second.getZ()) + 1.0f
            );
        }

        float centerX() {
            return (minX + maxX) * 0.5f;
        }

        float centerZ() {
            return (minZ + maxZ) * 0.5f;
        }
    }
}
