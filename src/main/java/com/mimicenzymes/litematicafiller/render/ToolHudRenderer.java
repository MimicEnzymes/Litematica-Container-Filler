package com.mimicenzymes.litematicafiller.render;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.config.Hotkeys;
import com.mimicenzymes.litematicafiller.tool.ContainerToolMode;
import com.mimicenzymes.litematicafiller.tool.ContainerToolStateMachine;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class ToolHudRenderer {
    private static final int ACCENT = 0xFF18F6E8;
    private static final int PANEL = 0xFF071014;
    private static final int PANEL_EDGE = 0xFF1E5D64;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED_TEXT = 0xFFC5D7DA;
    private static final int MIN_PANEL_WIDTH = 128;
    private static final float EDGE_MARGIN = 8.0f;
    private static final float PANEL_EDGE_TRIGGER_MARGIN = 22.0f;
    private static final float PANEL_FOLLOW_DEADBAND = 0.72f;
    private static final float PANEL_MOVE_DEADBAND = 1.5f;
    private static final float CENTER_SNAP_EPSILON = 0.45f;
    private static final float ANCHOR_SNAP_EPSILON = 0.45f;
    private static final float PANEL_SNAP_EPSILON = 0.5f;
    private static final float ANGLE_SNAP_EPSILON = 0.006f;
    private static final int PANEL_ANGLE_SEARCH_STEPS = 96;

    private static float visibility = 0.0f;
    private static float centerX = -1.0f;
    private static float centerY = -1.0f;
    private static float anchorX = -1.0f;
    private static float anchorY = -1.0f;
    private static float panelX = -1.0f;
    private static float panelY = -1.0f;
    private static float directionX = 0.0f;
    private static float directionY = -1.0f;
    private static float directionAngle = (float) -Math.PI * 0.5f;
    private static float panelAngle = (float) -Math.PI * 0.5f;
    private static boolean hasDirection = false;
    private static boolean hasPanelAngle = false;
    private static ContainerToolMode cachedMode = null;
    private static String cachedHotkey = "";
    private static String cachedSwitchHotkey = "";
    private static String cachedCloseHotkey = "";
    private static String cachedLabel = "";
    private static String cachedHint = "";
    private static String cachedSecondaryHint = "";
    private static int cachedPanelWidth = MIN_PANEL_WIDTH;
    private static long lastHudUpdateNanos = 0L;

    private ToolHudRenderer() {
    }

    public static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null ||
                !Configs.ENABLE_MOD.getBooleanValue() || !Configs.ENABLE_TOOL_HUD.getBooleanValue()) {
            visibility = 0.0f;
            return;
        }

        ContainerToolStateMachine tools = ContainerToolStateMachine.getInstance();
        BlockPos target = tools.isToolEnabled() ? tools.getLookedContainerForHud(client) : null;
        boolean visible = target != null && client.currentScreen == null;
        long now = System.nanoTime();
        boolean updateFrame = shouldUpdateFrame(now);
        float smoothing = clamp((float) Configs.TOOL_HUD_SMOOTHING.getDoubleValue(), 0.05f, 0.8f);
        if (updateFrame) {
            visibility += ((visible ? 1.0f : 0.0f) - visibility) * smoothing;
        }
        if (!visible && visibility <= 0.02f) {
            visibility = 0.0f;
            return;
        }

        int width = context.getScaledWindowWidth();
        int height = context.getScaledWindowHeight();
        ContainerToolMode mode = tools.getActiveMode();
        updateTextCache(client, mode);

        float scale = clamp(Configs.TOOL_HUD_SCALE.getIntegerValue() / 100.0f, 0.7f, 1.5f);
        int panelW = Math.round(cachedPanelWidth * scale);
        int panelH = Math.round((cachedSecondaryHint.isEmpty() ? 34 : 47) * scale);
        int offset = Math.round(Configs.TOOL_HUD_OFFSET.getIntegerValue() * scale);

        if (visible && updateFrame) {
            updateLayout(client, target, width, height, panelW, panelH, offset, scale, smoothing);
        } else if (!hasLayout()) {
            return;
        }

        float eased = easeOutCubic(visibility);
        float opacity = clamp((float) Configs.TOOL_HUD_OPACITY.getDoubleValue(), 0.1f, 1.0f);
        int alpha = Math.round(255.0f * opacity * eased);

        int startX = Math.round(anchorX);
        int startY = Math.round(anchorY);
        int panelLeft = Math.round(panelX);
        int panelTop = Math.round(panelY);
        float[] edge = getPanelEdgePoint(anchorX, anchorY, panelX, panelY, panelW, panelH);
        int endX = Math.round(edge[0]);
        int endY = Math.round(edge[1]);

        drawLeader(context, startX, startY, endX, endY, alpha);
        drawAnchor(context, startX, startY, alpha);
        drawPanel(context, client, panelLeft, panelTop, panelW, panelH, scale, alpha);
    }

    private static boolean shouldUpdateFrame(long nowNanos) {
        int fpsLimit = Configs.TOOL_HUD_FRAME_RATE.getIntegerValue();
        if (fpsLimit <= 0) {
            lastHudUpdateNanos = nowNanos;
            return true;
        }

        long interval = 1_000_000_000L / Math.max(1, fpsLimit);
        if (lastHudUpdateNanos == 0L || nowNanos - lastHudUpdateNanos >= interval) {
            lastHudUpdateNanos = nowNanos;
            return true;
        }
        return false;
    }

    private static void updateLayout(MinecraftClient client, BlockPos target, int width, int height, int panelW, int panelH,
                                     int offset, float scale, float smoothing) {
        float[] projected = projectTarget(client, target, width, height);
        float targetCenterX = projected[0];
        float targetCenterY = projected[1];

        if (centerX < 0.0f || centerY < 0.0f) {
            centerX = targetCenterX;
            centerY = targetCenterY;
        } else {
            float centerSmoothing = Math.min(0.48f, smoothing * 0.92f);
            centerX = smoothTowardWithSnap(centerX, targetCenterX, centerSmoothing, CENTER_SNAP_EPSILON);
            centerY = smoothTowardWithSnap(centerY, targetCenterY, centerSmoothing, CENTER_SNAP_EPSILON);
        }

        updateDirection(centerX, centerY, width, height, smoothing);
        updatePanelAngle(width, height, panelW, panelH, offset, scale, smoothing);

        float anchorRadius = Math.max(13.0f, 16.0f * scale);
        float targetAnchorX = clamp(centerX + directionX * anchorRadius, 12.0f, width - 12.0f);
        float targetAnchorY = clamp(centerY + directionY * anchorRadius, 12.0f, height - 12.0f);
        if (anchorX < 0.0f || anchorY < 0.0f) {
            anchorX = targetAnchorX;
            anchorY = targetAnchorY;
        } else {
            float anchorSmoothing = Math.min(0.62f, smoothing * 1.18f);
            anchorX = smoothTowardWithSnap(anchorX, targetAnchorX, anchorSmoothing, ANCHOR_SNAP_EPSILON);
            anchorY = smoothTowardWithSnap(anchorY, targetAnchorY, anchorSmoothing, ANCHOR_SNAP_EPSILON);
        }

        float[] targetPanel = getPanelPosition(panelAngle, width, height, panelW, panelH, offset);
        float targetPanelX = targetPanel[0];
        float targetPanelY = targetPanel[1];
        targetPanelX = clamp(targetPanelX, EDGE_MARGIN, Math.max(EDGE_MARGIN, width - panelW - EDGE_MARGIN));
        targetPanelY = clamp(targetPanelY, EDGE_MARGIN, Math.max(EDGE_MARGIN, height - panelH - EDGE_MARGIN));

        if (panelX < 0.0f || panelY < 0.0f) {
            panelX = targetPanelX;
            panelY = targetPanelY;
        } else {
            float panelSmoothing = Math.min(0.22f, smoothing * 0.42f);
            panelX = smoothTowardWithDeadband(panelX, targetPanelX, panelSmoothing, PANEL_MOVE_DEADBAND, PANEL_SNAP_EPSILON);
            panelY = smoothTowardWithDeadband(panelY, targetPanelY, panelSmoothing, PANEL_MOVE_DEADBAND, PANEL_SNAP_EPSILON);
        }
    }

    private static void updateDirection(float x, float y, int width, int height, float smoothing) {
        float rawX = x - width * 0.5f;
        float rawY = y - height * 0.5f;
        float length = (float) Math.sqrt(rawX * rawX + rawY * rawY);
        float targetAngle = length > 10.0f ? (float) Math.atan2(rawY, rawX) : directionAngle;

        if (!hasDirection) {
            directionAngle = targetAngle;
            directionX = (float) Math.cos(directionAngle);
            directionY = (float) Math.sin(directionAngle);
            hasDirection = true;
            return;
        }

        float directionSmoothing = Math.min(0.36f, smoothing * 0.72f);
        float diff = wrapRadians(targetAngle - directionAngle);
        if (Math.abs(diff) <= ANGLE_SNAP_EPSILON) {
            directionAngle = targetAngle;
        } else {
            directionAngle += diff *
                (1.0f - (float) Math.pow(1.0f - clamp(directionSmoothing, 0.01f, 0.95f), 1.25D));
        }
        directionAngle = wrapRadians(directionAngle);
        directionX = (float) Math.cos(directionAngle);
        directionY = (float) Math.sin(directionAngle);
    }

    private static void updatePanelAngle(int width, int height, int panelW, int panelH, int offset, float scale, float smoothing) {
        if (!hasPanelAngle) {
            panelAngle = directionAngle;
            hasPanelAngle = true;
        }

        float safeMargin = Math.max(PANEL_EDGE_TRIGGER_MARGIN, 18.0f * scale);
        float currentOverflow = getPanelOverflow(panelAngle, width, height, panelW, panelH, offset, safeMargin);
        float targetAngle;
        float deadband;
        if (currentOverflow > 0.0f) {
            targetAngle = findNearestFittingPanelAngle(panelAngle, width, height, panelW, panelH, offset, safeMargin);
            deadband = 0.0f;
        } else {
            float directionDiff = wrapRadians(directionAngle - panelAngle);
            if (Math.abs(directionDiff) <= PANEL_FOLLOW_DEADBAND) {
                return;
            }

            targetAngle = findNearestFittingPanelAngle(directionAngle, width, height, panelW, panelH, offset, safeMargin);
            deadband = PANEL_FOLLOW_DEADBAND;
        }

        float diff = wrapRadians(targetAngle - panelAngle);
        if (Math.abs(diff) <= deadband) {
            return;
        }

        float effectiveDiff = diff - Math.copySign(deadband, diff);
        if (Math.abs(effectiveDiff) <= ANGLE_SNAP_EPSILON) {
            panelAngle = targetAngle;
            return;
        }

        float angleSmoothing = Math.min(0.18f, smoothing * 0.36f);
        panelAngle += effectiveDiff *
                (1.0f - (float) Math.pow(1.0f - clamp(angleSmoothing, 0.01f, 0.95f), 1.25D));
        panelAngle = wrapRadians(panelAngle);
    }

    private static float[] projectTarget(MinecraftClient client, BlockPos target, int width, int height) {
        Vec3d eye = client.player.getEyePos();
        Vec3d targetCenter = Vec3d.ofCenter(target).add(0.0D, 0.16D, 0.0D);
        Vec3d toTarget = targetCenter.subtract(eye);
        Vec3d forward = client.player.getRotationVec(1.0f).normalize();
        Vec3d worldUp = new Vec3d(0.0D, 1.0D, 0.0D);
        Vec3d right = forward.crossProduct(worldUp);
        if (right.lengthSquared() < 1.0E-5D) {
            right = new Vec3d(1.0D, 0.0D, 0.0D);
        } else {
            right = right.normalize();
        }
        Vec3d up = right.crossProduct(forward).normalize();

        double depth = Math.max(0.35D, toTarget.dotProduct(forward));
        double horizontal = toTarget.dotProduct(right) / depth;
        double vertical = toTarget.dotProduct(up) / depth;

        float x = width * 0.5f + (float) horizontal * width * 0.42f;
        float y = height * 0.5f - (float) vertical * height * 0.42f;
        return new float[] {
                quantize(clamp(x, 18.0f, width - 18.0f), 0.25f),
                quantize(clamp(y, 18.0f, height - 18.0f), 0.25f)
        };
    }

    private static void updateTextCache(MinecraftClient client, ContainerToolMode mode) {
        String hotkey = normalizeHotkey(Hotkeys.TOOL_TRIGGER.getKeybind().getKeysDisplayString());
        String switchHotkey = normalizeHotkey(Hotkeys.TOOL_SWITCH_MODE.getKeybind().getKeysDisplayString());
        String closeHotkey = normalizeHotkey(Hotkeys.TOOL_CLOSE_ALL.getKeybind().getKeysDisplayString());

        if (mode == cachedMode && hotkey.equals(cachedHotkey) &&
                switchHotkey.equals(cachedSwitchHotkey) && closeHotkey.equals(cachedCloseHotkey)) {
            return;
        }

        cachedMode = mode;
        cachedHotkey = hotkey;
        cachedSwitchHotkey = switchHotkey;
        cachedCloseHotkey = closeHotkey;
        cachedLabel = mode.getDisplayName();
        cachedHint = hotkey.isEmpty()
                ? StringUtils.translate("litematica_container_filler.hud.tool_action_unbound")
                : StringUtils.translate("litematica_container_filler.hud.tool_action", hotkey);
        cachedSecondaryHint = buildSecondaryHint(switchHotkey, closeHotkey);
        cachedPanelWidth = Math.max(MIN_PANEL_WIDTH,
                Math.max(Math.max(client.textRenderer.getWidth(cachedLabel), client.textRenderer.getWidth(cachedHint)),
                        client.textRenderer.getWidth(cachedSecondaryHint)) + 24);
    }

    private static String normalizeHotkey(String hotkey) {
        if (hotkey == null || hotkey.isBlank() || "NONE".equalsIgnoreCase(hotkey)) {
            return "";
        }
        return hotkey;
    }

    private static String buildSecondaryHint(String switchHotkey, String closeHotkey) {
        if (switchHotkey.isEmpty() && closeHotkey.isEmpty()) {
            return "";
        }
        if (switchHotkey.isEmpty()) {
            return StringUtils.translate("litematica_container_filler.hud.tool_close", closeHotkey);
        }
        if (closeHotkey.isEmpty()) {
            return StringUtils.translate("litematica_container_filler.hud.tool_switch", switchHotkey);
        }
        return StringUtils.translate("litematica_container_filler.hud.tool_switch_close", switchHotkey, closeHotkey);
    }

    private static void drawAnchor(DrawContext context, int x, int y, int alpha) {
        int ring = withAlpha(ACCENT, Math.round(alpha * 0.82f));
        int fill = withAlpha(0xFF041114, Math.round(alpha * 0.82f));
        context.fill(x - 4, y - 4, x + 5, y + 5, withAlpha(0xFF000000, Math.round(alpha * 0.25f)));
        context.fill(x - 3, y - 3, x + 4, y + 4, ring);
        context.fill(x - 2, y - 2, x + 3, y + 3, fill);
        context.fill(x - 1, y - 1, x + 2, y + 2, ring);
    }

    private static void drawLeader(DrawContext context, int x1, int y1, int x2, int y2, int alpha) {
        int[] corner = getLeaderCorner(x1, y1, x2, y2);
        drawBentLine(context, x1, y1, corner[0], corner[1], x2, y2, withAlpha(0xFF000000, Math.round(alpha * 0.22f)));
        drawBentLine(context, x1, y1 - 1, corner[0], corner[1] - 1, x2, y2 - 1, withAlpha(ACCENT, Math.round(alpha * 0.45f)));
        drawBentLine(context, x1, y1, corner[0], corner[1], x2, y2, withAlpha(ACCENT, Math.round(alpha * 0.66f)));
    }

    private static void drawPanel(DrawContext context, MinecraftClient client, int x, int y, int width, int height, float scale, int alpha) {
        int panelAlpha = Math.round(alpha * 0.78f);
        int edgeAlpha = Math.round(alpha * 0.55f);
        int pad = Math.max(7, Math.round(8.0f * scale));
        int lineGap = Math.max(11, Math.round(12.0f * scale));

        drawSoftRect(context, x + 1, y + 2, width, height, withAlpha(0xFF000000, Math.round(alpha * 0.18f)));
        drawSoftRect(context, x, y, width, height, withAlpha(PANEL, panelAlpha));
        context.fill(x + 2, y, x + width - 2, y + 1, withAlpha(PANEL_EDGE, edgeAlpha));
        context.fill(x + 2, y + height - 1, x + width - 2, y + height, withAlpha(PANEL_EDGE, Math.round(edgeAlpha * 0.45f)));
        context.fill(x, y + 4, x + 2, y + height - 4, withAlpha(ACCENT, Math.round(alpha * 0.72f)));

        context.drawTextWithShadow(client.textRenderer, cachedLabel, x + pad, y + Math.round(5.0f * scale), withAlpha(TEXT, alpha));
        context.drawTextWithShadow(client.textRenderer, cachedHint, x + pad, y + Math.round(5.0f * scale) + lineGap, withAlpha(MUTED_TEXT, Math.round(alpha * 0.9f)));

        if (!cachedSecondaryHint.isEmpty()) {
            context.drawTextWithShadow(client.textRenderer, cachedSecondaryHint, x + pad, y + Math.round(5.0f * scale) + lineGap * 2, withAlpha(MUTED_TEXT, Math.round(alpha * 0.72f)));
        }
    }

    private static void drawLine(DrawContext context, int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        if (steps <= 0) {
            context.fill(x1, y1, x1 + 1, y1 + 1, color);
            return;
        }

        float stepX = (x2 - x1) / (float) steps;
        float stepY = (y2 - y1) / (float) steps;
        for (int i = 0; i <= steps; i++) {
            int x = Math.round(x1 + stepX * i);
            int y = Math.round(y1 + stepY * i);
            context.fill(x, y, x + 1, y + 1, color);
        }
    }

    private static void drawBentLine(DrawContext context, int x1, int y1, int cornerX, int cornerY, int x2, int y2, int color) {
        drawLine(context, x1, y1, cornerX, cornerY, color);
        drawLine(context, cornerX, cornerY, x2, y2, color);
    }

    private static void drawSoftRect(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x + 2, y, x + width - 2, y + height, color);
        context.fill(x, y + 2, x + width, y + height - 2, color);
        context.fill(x + 1, y + 1, x + width - 1, y + height - 1, color);
    }

    private static float easeOutCubic(float value) {
        float t = clamp(value, 0.0f, 1.0f);
        return 1.0f - (float)Math.pow(1.0f - t, 3.0D);
    }

    private static boolean hasLayout() {
        return centerX >= 0.0f && centerY >= 0.0f && anchorX >= 0.0f && anchorY >= 0.0f && panelX >= 0.0f && panelY >= 0.0f;
    }

    private static float smoothToward(float current, float target, float smoothing) {
        float delta = target - current;
        float eased = 1.0f - (float) Math.pow(1.0f - clamp(smoothing, 0.01f, 0.95f), 1.25D);
        return current + delta * eased;
    }

    private static float smoothTowardWithSnap(float current, float target, float smoothing, float snapEpsilon) {
        if (Math.abs(target - current) <= snapEpsilon) {
            return target;
        }
        return smoothToward(current, target, smoothing);
    }

    private static float smoothTowardWithDeadband(float current, float target, float smoothing, float deadband, float snapEpsilon) {
        float delta = target - current;
        if (Math.abs(delta) <= snapEpsilon) {
            return target;
        }
        if (Math.abs(delta) <= deadband) {
            return current;
        }

        float adjustedTarget = target - Math.copySign(deadband, delta);
        return smoothTowardWithSnap(current, adjustedTarget, smoothing, snapEpsilon);
    }

    private static float quantize(float value, float step) {
        return Math.round(value / step) * step;
    }

    private static float[] getPanelPosition(float angle, int width, int height, int panelW, int panelH, int offset) {
        float targetPanelCenterX = width * 0.5f + (float) Math.cos(angle) * (width * 0.39f + offset * 0.55f);
        float targetPanelCenterY = height * 0.5f + (float) Math.sin(angle) * (height * 0.34f + offset * 0.45f);
        return new float[] {
                targetPanelCenterX - panelW * 0.5f,
                targetPanelCenterY - panelH * 0.5f
        };
    }

    private static float getPanelOverflow(float angle, int width, int height, int panelW, int panelH, int offset, float margin) {
        float[] panel = getPanelPosition(angle, width, height, panelW, panelH, offset);
        float overflow = 0.0f;
        overflow += Math.max(0.0f, margin - panel[0]);
        overflow += Math.max(0.0f, margin - panel[1]);
        overflow += Math.max(0.0f, panel[0] + panelW - (width - margin));
        overflow += Math.max(0.0f, panel[1] + panelH - (height - margin));
        return overflow;
    }

    private static float findNearestFittingPanelAngle(float baseAngle, int width, int height, int panelW, int panelH,
                                                      int offset, float margin) {
        float bestAngle = baseAngle;
        float bestCost = getPanelOverflow(baseAngle, width, height, panelW, panelH, offset, margin) * 1000.0f;

        for (int i = 0; i < PANEL_ANGLE_SEARCH_STEPS; i++) {
            float angle = (float) (-Math.PI + (Math.PI * 2.0D) * i / PANEL_ANGLE_SEARCH_STEPS);
            float overflow = getPanelOverflow(angle, width, height, panelW, panelH, offset, margin);
            float distance = Math.abs(wrapRadians(angle - baseAngle));
            float cost = overflow * 1000.0f + distance;
            if (cost < bestCost) {
                bestCost = cost;
                bestAngle = angle;
            }
        }

        return bestAngle;
    }

    private static int[] getLeaderCorner(int x1, int y1, int x2, int y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 8.0f) {
            return new int[] { Math.round((x1 + x2) * 0.5f), Math.round((y1 + y2) * 0.5f) };
        }

        float axisX = (float) Math.cos(panelAngle);
        float axisY = (float) Math.sin(panelAngle);
        float axisDistance = dx * axisX + dy * axisY;
        if (axisDistance < 0.0f) {
            axisX = -axisX;
            axisY = -axisY;
            axisDistance = -axisDistance;
        }

        float minLeg = Math.min(24.0f, length * 0.32f);
        float maxLeg = Math.max(minLeg, length - minLeg);
        axisDistance = clamp(axisDistance, minLeg, maxLeg);
        return new int[] {
                Math.round(x1 + axisX * axisDistance),
                Math.round(y1 + axisY * axisDistance)
        };
    }

    private static float wrapRadians(float angle) {
        while (angle <= -Math.PI) {
            angle += (float) (Math.PI * 2.0D);
        }
        while (angle > Math.PI) {
            angle -= (float) (Math.PI * 2.0D);
        }
        return angle;
    }

    private static float[] getPanelEdgePoint(float x, float y, float panelLeft, float panelTop, int panelW, int panelH) {
        float panelCenterX = panelLeft + panelW * 0.5f;
        float panelCenterY = panelTop + panelH * 0.5f;
        float dx = x - panelCenterX;
        float dy = y - panelCenterY;
        float halfW = panelW * 0.5f;
        float halfH = panelH * 0.5f;

        if (Math.abs(dx) < 0.001f && Math.abs(dy) < 0.001f) {
            return new float[] { panelCenterX, panelTop };
        }

        float scaleX = Math.abs(dx) < 0.001f ? Float.POSITIVE_INFINITY : halfW / Math.abs(dx);
        float scaleY = Math.abs(dy) < 0.001f ? Float.POSITIVE_INFINITY : halfH / Math.abs(dy);
        float scale = Math.min(scaleX, scaleY);
        return new float[] {
                clamp(panelCenterX + dx * scale, panelLeft, panelLeft + panelW),
                clamp(panelCenterY + dy * scale, panelTop, panelTop + panelH)
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int withAlpha(int argb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0x00FFFFFF);
    }
}
