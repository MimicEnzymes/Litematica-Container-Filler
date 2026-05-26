package com.mimicenzymes.litematicafiller.render;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.AutoFillerStateMachine;
import com.mimicenzymes.litematicafiller.core.LitematicaContainerReader;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import fi.dy.masa.malilib.util.Color4f;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.GameRenderer;
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
        render(null);
    }

    public void render(Object context) {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) {
            clearRenderCache();
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || client.player == null) {
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
        boolean xray = Configs.HIGHLIGHT_XRAY.getBooleanValue();
        Vec3d cameraPos = client.gameRenderer.getCamera().getPos();

        boolean stateApplied = false;
        try {
            setupRenderState(xray);
            stateApplied = true;

            if (!highlights.isEmpty()) {
                long styleSignature = computeStyleSignature(xray);
                int highlightVersion = HighlightScanner.getHighlightVersion();

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
            } else {
                clearChunkRenderCache();
            }

            drawTaskOverlays(cameraPos, xray, currentTaskPos, queuedTaskPositions, missingMaterialPositions, recentFillingPositions);
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
            if (!shouldRenderState(entry.getValue())) continue;
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
                closeChunkCache(oldCache);
            }

            iterator.remove();
            rebuilt++;
        }
    }

    private ChunkRenderCache buildChunkCache(Map<BlockPos, HighlightState> highlights, Vec3d cameraPos) {
        Tessellator tessellator = Tessellator.getInstance();
        BuiltBuffer fillMeshData = null;
        BuiltBuffer lineMeshData = null;
        VertexBuffer fillVertexBuffer = null;
        VertexBuffer lineVertexBuffer = null;
        boolean keepBuffer = false;
        boolean renderShape = Configs.RENDER_STATE_GLASS.getBooleanValue() || Configs.RENDER_STATE_TOP_PLATE.getBooleanValue();

        try {
            if (renderShape) {
                BufferBuilder fillBuffer = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
                for (Map.Entry<BlockPos, HighlightState> entry : highlights.entrySet()) {
                    Color4f base = getColor(entry.getValue());
                    HighlightBox box = getHighlightBox(entry.getKey());

                    if (Configs.RENDER_STATE_GLASS.getBooleanValue()) {
                        float alphaMultiplier = (float) Configs.HIGHLIGHT_GLASS_ALPHA_MULTIPLIER.getDoubleValue();
                        Color4f glass = new Color4f(base.r, base.g, base.b, Math.min(0.24f, Math.max(0.04f, base.a * alphaMultiplier)));
                        drawInflatedWorldBox(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(), 0.012f, glass, cameraPos, fillBuffer);
                    }

                    if (Configs.RENDER_STATE_TOP_PLATE.getBooleanValue()) {
                        Color4f crown = new Color4f(base.r, base.g, base.b, Math.min(0.34f, Math.max(0.12f, base.a * 0.36f)));
                        float inset = Math.max(0.02f, (1.0f - (float) Configs.HIGHLIGHT_TOP_PLATE_SIZE.getDoubleValue()) * 0.5f);
                        drawWorldBox(
                                box.minX() + inset, box.maxY() + 0.035f, box.minZ() + inset,
                                box.maxX() - inset, box.maxY() + 0.095f, box.maxZ() - inset,
                                crown, cameraPos, fillBuffer
                        );
                    }
                }

                fillMeshData = fillBuffer.endNullable();
                if (fillMeshData != null) {
                    fillVertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    fillVertexBuffer.bind();
                    fillVertexBuffer.upload(fillMeshData);
                    fillMeshData = null;
                }
            }

            if (renderShape) {
                BufferBuilder lineBuffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
                for (Map.Entry<BlockPos, HighlightState> entry : highlights.entrySet()) {
                    drawOutlineBox(getHighlightBox(entry.getKey()), getOutlineColor(getColor(entry.getValue())), cameraPos, lineBuffer);
                }

                lineMeshData = lineBuffer.endNullable();
                if (lineMeshData != null) {
                    lineVertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    lineVertexBuffer.bind();
                    lineVertexBuffer.upload(lineMeshData);
                    lineMeshData = null;
                }
            }

            if (fillVertexBuffer == null && lineVertexBuffer == null) return null;

            ChunkRenderCache cache = new ChunkRenderCache(fillVertexBuffer, lineVertexBuffer, cameraPos.x, cameraPos.y, cameraPos.z);
            keepBuffer = true;
            return cache;
        } finally {
            VertexBuffer.unbind();
            if (fillMeshData != null) {
                fillMeshData.close();
            }
            if (lineMeshData != null) {
                lineMeshData.close();
            }
            if (!keepBuffer) {
                closeVertexBuffer(fillVertexBuffer);
                closeVertexBuffer(lineVertexBuffer);
            }
        }
    }

    private void drawChunkCaches(Vec3d cameraPos) {
        for (ChunkRenderCache cache : chunkCaches.values()) {
            if (cache.isEmpty()) continue;

            renderOffset[0] = (float) (cache.cameraX - cameraPos.x);
            renderOffset[1] = (float) (cache.cameraY - cameraPos.y);
            renderOffset[2] = (float) (cache.cameraZ - cameraPos.z);
            drawChunkCache(cache);
        }
    }

    private void drawChunkCache(ChunkRenderCache cache) {
        if (cache == null || cache.isEmpty()) return;

        var modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();

        try {
            modelViewStack.translate(renderOffset[0], renderOffset[1], renderOffset[2]);
            RenderSystem.applyModelViewMatrix();
            drawVertexBuffer(cache.fillVertexBuffer);
            drawVertexBuffer(cache.lineVertexBuffer);
        } finally {
            VertexBuffer.unbind();
            modelViewStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private void drawVertexBuffer(VertexBuffer vertexBuffer) {
        if (vertexBuffer == null || vertexBuffer.isClosed()) return;

        vertexBuffer.bind();
        vertexBuffer.draw(RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
    }

    private boolean hasTaskOverlay(BlockPos currentTaskPos, Set<BlockPos> queuedTaskPositions, Set<BlockPos> missingMaterialPositions) {
        return (Configs.RENDER_FILLING_ARROW.getBooleanValue() && currentTaskPos != null)
                || (Configs.RENDER_QUEUED_SPINNER.getBooleanValue() && !queuedTaskPositions.isEmpty())
                || (Configs.RENDER_MISSING_MATERIAL_MARKER.getBooleanValue() && !missingMaterialPositions.isEmpty());
    }

    private void drawTaskOverlays(Vec3d cameraPos, boolean xray, BlockPos currentTaskPos, Set<BlockPos> queuedTaskPositions, Set<BlockPos> missingMaterialPositions, Set<BlockPos> recentFillingPositions) {
        if (!hasTaskOverlay(currentTaskPos, queuedTaskPositions, missingMaterialPositions)) {
            clearTaskOverlayCache();
            return;
        }

        int fpsLimit = Configs.TASK_MARKER_ANIMATION_FPS.getIntegerValue();
        double rawTime = System.nanoTime() / 1_000_000_000.0D;
        long frame = fpsLimit <= 0 ? System.nanoTime() : (long) Math.floor(rawTime * fpsLimit);
        double time = fpsLimit <= 0 ? rawTime : frame / (double) fpsLimit;
        long signature = computeTaskOverlaySignature(xray, currentTaskPos, queuedTaskPositions, missingMaterialPositions);

        if (taskOverlayCache != null && taskOverlaySignature == signature && taskOverlayFrame == frame) {
            renderOffset[0] = (float) (taskOverlayCache.cameraX - cameraPos.x);
            renderOffset[1] = (float) (taskOverlayCache.cameraY - cameraPos.y);
            renderOffset[2] = (float) (taskOverlayCache.cameraZ - cameraPos.z);
            drawChunkCache(taskOverlayCache);
            return;
        }

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        BuiltBuffer meshData = null;
        VertexBuffer vertexBuffer = null;
        boolean keepBuffer = false;

        try {
            if (Configs.RENDER_FILLING_ARROW.getBooleanValue() && currentTaskPos != null) {
                drawFillingArrow(getHighlightBox(currentTaskPos), cameraPos, time, buffer);
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

            meshData = buffer.endNullable();
            if (meshData == null) return;

            vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            vertexBuffer.bind();
            vertexBuffer.upload(meshData);
            meshData = null;

            ChunkRenderCache cache = new ChunkRenderCache(vertexBuffer, null, cameraPos.x, cameraPos.y, cameraPos.z);
            keepBuffer = true;
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
            VertexBuffer.unbind();
            if (meshData != null) {
                meshData.close();
            }
            if (!keepBuffer && vertexBuffer != null) {
                closeVertexBuffer(vertexBuffer);
            }
        }
    }

    private void setupRenderState(boolean xray) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();

        if (xray) {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            GL11.glDepthRange(0.0, 0.0);
        } else {
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
        }

        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(-1.2f, -0.2f);
        RenderSystem.lineWidth(getHighlightLineWidth());
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.applyModelViewMatrix();
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
        RenderSystem.applyModelViewMatrix();
    }

    private Color4f getOutlineColor(Color4f base) {
        return new Color4f(base.r, base.g, base.b, Math.min(0.62f, Math.max(0.24f, base.a * 0.55f)));
    }

    private float getHighlightLineWidth() {
        MinecraftClient client = MinecraftClient.getInstance();
        int width = client == null ? 1920 : client.getWindow().getFramebufferWidth();
        return Math.max(1.2F, width / 1920.0F * 1.45F);
    }

    private void drawOutlineBox(HighlightBox box, Color4f color, Vec3d cameraPos, BufferBuilder buffer) {
        float inflate = 0.018f;
        fi.dy.masa.malilib.render.RenderUtils.drawBoxAllEdgesBatchedLines(
                (float) (box.minX() - cameraPos.x - inflate),
                (float) (box.minY() - cameraPos.y - inflate),
                (float) (box.minZ() - cameraPos.z - inflate),
                (float) (box.maxX() - cameraPos.x + inflate),
                (float) (box.maxY() - cameraPos.y + inflate),
                (float) (box.maxZ() - cameraPos.z + inflate),
                color,
                buffer
        );
    }

    private void drawFillingArrow(HighlightBox box, Vec3d cameraPos, double time, BufferBuilder buffer) {
        float cx = box.centerX();
        float cz = box.centerZ();
        float scale = (float) Configs.TASK_OVERLAY_SCALE.getDoubleValue();
        float y = box.maxY() + 0.64f + (float) Math.sin(time * 5.0D) * 0.045f;
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 7.0D);
        Color4f base = Configs.HIGHLIGHT_COLOR_FILLING.getColor();
        Color4f body = new Color4f(base.r, base.g, base.b, Math.min(0.82f, base.a * (0.50f + pulse * 0.12f)));
        Color4f core = new Color4f(0.82f, 1.0f, 0.96f, 0.36f);
        Color4f glow = new Color4f(base.r, base.g, base.b, 0.12f);

        drawVerticalDownArrow(cx, y, cz, 0.24f * scale, 0.62f * scale, 0.080f * scale, body, cameraPos, buffer);
        drawVerticalDownArrow(cx, y + 0.010f * scale, cz, 0.135f * scale, 0.39f * scale, 0.046f * scale, core, cameraPos, buffer);
        drawCenteredWorldBox(cx, box.maxY() + 0.045f, cz, (0.24f + pulse * 0.05f) * scale, 0.020f * scale, glow, cameraPos, buffer);
    }

    private void drawQueuedSpinner(HighlightBox box, Vec3d cameraPos, double time, BufferBuilder buffer) {
        float cx = box.centerX();
        float cz = box.centerZ();
        float scale = (float) Configs.TASK_OVERLAY_SCALE.getDoubleValue();
        float cy = box.maxY() + 0.34f + (float) Math.sin(time * 2.4D) * 0.028f;
        float radius = 0.32f * scale;
        Color4f base = Configs.HIGHLIGHT_COLOR_QUEUED.getColor();

        for (int i = 0; i < 8; i++) {
            double angle = time * 3.2D + i * Math.PI / 4.0D;
            float x = cx + (float) Math.cos(angle) * radius;
            float z = cz + (float) Math.sin(angle) * radius;
            float alpha = Math.min(0.70f, base.a * (0.16f + i * 0.055f));
            drawCenteredWorldBox(x, cy, z, 0.058f * scale, 0.035f * scale, new Color4f(base.r, base.g, base.b, alpha), cameraPos, buffer);
        }

        drawCenteredWorldBox(cx, cy, cz, 0.15f * scale, 0.025f * scale, new Color4f(base.r, base.g, base.b, 0.12f), cameraPos, buffer);
    }

    private void drawMissingMaterialMarker(HighlightBox box, Vec3d cameraPos, double time, BufferBuilder buffer) {
        float scale = (float) Configs.TASK_OVERLAY_SCALE.getDoubleValue();
        float cx = box.centerX();
        float cz = box.centerZ();
        float cy = box.maxY() + 0.34f + (float) Math.sin(time * 4.4D) * 0.035f;
        Color4f base = Configs.HIGHLIGHT_COLOR_MISSING_MATERIAL.getColor();
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 8.0D);
        Color4f color = new Color4f(base.r, base.g, base.b, Math.min(0.82f, base.a * (0.42f + pulse * 0.22f)));

        drawCenteredWorldBox(cx, cy + 0.24f * scale, cz, 0.070f * scale, 0.045f * scale, color, cameraPos, buffer);
        drawCenteredWorldBox(cx, cy, cz, 0.060f * scale, 0.19f * scale, color, cameraPos, buffer);
        drawCenteredWorldBox(cx, cy - 0.30f * scale, cz, 0.070f * scale, 0.050f * scale, color, cameraPos, buffer);
    }

    private void drawVerticalDownArrow(float cx, float cy, float cz, float halfWidth, float height, float halfDepth, Color4f color, Vec3d cameraPos, BufferBuilder buffer) {
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

    private void drawWorldPrism(float[] xs, float[] ys, float cz, float halfDepth, Color4f color, Vec3d cameraPos, BufferBuilder buffer) {
        if (xs.length < 3 || xs.length != ys.length) return;

        float frontZ = (float) (cz - halfDepth - cameraPos.z);
        float backZ = (float) (cz + halfDepth - cameraPos.z);

        for (int i = 1; i + 1 < xs.length; i++) {
            vertex((float) (xs[0] - cameraPos.x), (float) (ys[0] - cameraPos.y), frontZ, color, buffer);
            vertex((float) (xs[i] - cameraPos.x), (float) (ys[i] - cameraPos.y), frontZ, color, buffer);
            vertex((float) (xs[i + 1] - cameraPos.x), (float) (ys[i + 1] - cameraPos.y), frontZ, color, buffer);
            vertex((float) (xs[i + 1] - cameraPos.x), (float) (ys[i + 1] - cameraPos.y), frontZ, color, buffer);

            vertex((float) (xs[0] - cameraPos.x), (float) (ys[0] - cameraPos.y), backZ, color, buffer);
            vertex((float) (xs[i + 1] - cameraPos.x), (float) (ys[i + 1] - cameraPos.y), backZ, color, buffer);
            vertex((float) (xs[i] - cameraPos.x), (float) (ys[i] - cameraPos.y), backZ, color, buffer);
            vertex((float) (xs[i] - cameraPos.x), (float) (ys[i] - cameraPos.y), backZ, color, buffer);
        }

        for (int i = 0; i < xs.length; i++) {
            int next = (i + 1) % xs.length;
            vertex((float) (xs[i] - cameraPos.x), (float) (ys[i] - cameraPos.y), frontZ, color, buffer);
            vertex((float) (xs[next] - cameraPos.x), (float) (ys[next] - cameraPos.y), frontZ, color, buffer);
            vertex((float) (xs[next] - cameraPos.x), (float) (ys[next] - cameraPos.y), backZ, color, buffer);
            vertex((float) (xs[i] - cameraPos.x), (float) (ys[i] - cameraPos.y), backZ, color, buffer);
        }
    }

    private void drawCenteredWorldBox(float cx, float cy, float cz, float halfSize, float halfHeight, Color4f color, Vec3d cameraPos, BufferBuilder buffer) {
        drawWorldBox(
                cx - halfSize, cy - halfHeight, cz - halfSize,
                cx + halfSize, cy + halfHeight, cz + halfSize,
                color, cameraPos, buffer
        );
    }

    private void drawInflatedWorldBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, float inflate, Color4f color, Vec3d cameraPos, BufferBuilder buffer) {
        drawWorldBox(
                minX - inflate, minY - inflate, minZ - inflate,
                maxX + inflate, maxY + inflate, maxZ + inflate,
                color, cameraPos, buffer
        );
    }

    private void drawWorldBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, Color4f color, Vec3d cameraPos, BufferBuilder buffer) {
        float x1 = (float) (minX - cameraPos.x);
        float y1 = (float) (minY - cameraPos.y);
        float z1 = (float) (minZ - cameraPos.z);
        float x2 = (float) (maxX - cameraPos.x);
        float y2 = (float) (maxY - cameraPos.y);
        float z2 = (float) (maxZ - cameraPos.z);

        vertex(x1, y1, z1, color, buffer); vertex(x2, y1, z1, color, buffer); vertex(x2, y2, z1, color, buffer); vertex(x1, y2, z1, color, buffer);
        vertex(x2, y1, z2, color, buffer); vertex(x1, y1, z2, color, buffer); vertex(x1, y2, z2, color, buffer); vertex(x2, y2, z2, color, buffer);
        vertex(x1, y1, z2, color, buffer); vertex(x1, y1, z1, color, buffer); vertex(x1, y2, z1, color, buffer); vertex(x1, y2, z2, color, buffer);
        vertex(x2, y1, z1, color, buffer); vertex(x2, y1, z2, color, buffer); vertex(x2, y2, z2, color, buffer); vertex(x2, y2, z1, color, buffer);
        vertex(x1, y2, z1, color, buffer); vertex(x2, y2, z1, color, buffer); vertex(x2, y2, z2, color, buffer); vertex(x1, y2, z2, color, buffer);
        vertex(x1, y1, z2, color, buffer); vertex(x2, y1, z2, color, buffer); vertex(x2, y1, z1, color, buffer); vertex(x1, y1, z1, color, buffer);
    }

    private void vertex(float x, float y, float z, Color4f color, BufferBuilder buffer) {
        buffer.vertex(x, y, z).color(toChannel(color.r), toChannel(color.g), toChannel(color.b), toChannel(color.a));
    }

    private int toChannel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255.0f)));
    }

    private long computeStyleSignature(boolean xray) {
        long sum = xray ? 0x4f1bbcdc7c3a4f31L : 0x9e3779b97f4a7c15L;
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_UNFILLED.getColor().intValue);
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_PARTIAL.getColor().intValue);
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_OVERFILLED.getColor().intValue);
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_WRONG.getColor().intValue);
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_SATISFIED.getColor().intValue);
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_UNKNOWN.getColor().intValue);
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_UNPLACED.getColor().intValue);
        sum = mix64(sum ^ (Configs.RENDER_STATE_GLASS.getBooleanValue() ? 1L : 0L));
        sum = mix64(sum ^ (Configs.RENDER_STATE_TOP_PLATE.getBooleanValue() ? 2L : 0L));
        sum = mix64(sum ^ Double.doubleToLongBits(Configs.HIGHLIGHT_GLASS_ALPHA_MULTIPLIER.getDoubleValue()));
        return mix64(sum ^ Double.doubleToLongBits(Configs.HIGHLIGHT_TOP_PLATE_SIZE.getDoubleValue()));
    }

    private long computeTaskOverlaySignature(boolean xray, BlockPos currentTaskPos, Set<BlockPos> queuedTaskPositions, Set<BlockPos> missingMaterialPositions) {
        long sum = xray ? 0x31cb2ad18e5c3f01L : 0x59232765f0aa67bdL;
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_FILLING.getColor().intValue);
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_QUEUED.getColor().intValue);
        sum = mix64(sum ^ Configs.HIGHLIGHT_COLOR_MISSING_MATERIAL.getColor().intValue);
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
        clearChunkRenderCache();
        clearTaskOverlayCache();
        cachedHighlightVersion = -1;
        cachedStyleSignature = EMPTY_SIGNATURE;
    }

    private void clearChunkRenderCache() {
        for (ChunkRenderCache cache : chunkCaches.values()) {
            closeChunkCache(cache);
        }
        chunkCaches.clear();
        desiredChunks.clear();
        dirtyChunks.clear();
    }

    private void clearTaskOverlayCache() {
        if (taskOverlayCache != null) {
            closeChunkCache(taskOverlayCache);
            taskOverlayCache = null;
        }
        taskOverlaySignature = EMPTY_SIGNATURE;
        taskOverlayFrame = Long.MIN_VALUE;
    }

    private void removeChunkCache(ChunkKey key) {
        ChunkRenderCache cache = chunkCaches.remove(key);
        if (cache != null) {
            closeChunkCache(cache);
        }
    }

    private void closeChunkCache(ChunkRenderCache cache) {
        if (cache == null) return;

        closeVertexBuffer(cache.fillVertexBuffer);
        closeVertexBuffer(cache.lineVertexBuffer);
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
            return HighlightBox.single(pos == null ? BlockPos.ORIGIN : pos);
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

    private record ChunkRenderCache(VertexBuffer fillVertexBuffer, VertexBuffer lineVertexBuffer, double cameraX, double cameraY, double cameraZ) {
        boolean isEmpty() {
            return (fillVertexBuffer == null || fillVertexBuffer.isClosed()) &&
                    (lineVertexBuffer == null || lineVertexBuffer.isClosed());
        }
    }

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
