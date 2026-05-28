package com.mimicenzymes.litematicafiller.render;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.config.Hotkeys;
import com.mimicenzymes.litematicafiller.config.ToolHudStyle;
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
    private static final int FIXED_PANEL_EDGE = 0xFFE6F2E8;
    private static final int FIXED_PANEL_INNER = 0xEE050708;
    private static final int FIXED_PANEL_HEADER = 0xFF2A332B;
    private static final int FIXED_PANEL_BAR_BG = 0xFF0B2310;
    private static final int FIXED_PANEL_BAR = 0xFF35F05E;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED_TEXT = 0xFFC5D7DA;
    private static final int MIN_PANEL_WIDTH = 128;
    private static final int FIXED_PANEL_BASE_HEIGHT = 68;
    private static final int ANCHORED_PANEL_BASE_HEIGHT = 68;
    private static final float EDGE_MARGIN = 8.0f;
    private static final float PANEL_EDGE_TRIGGER_MARGIN = 22.0f;
    private static final float PANEL_FOLLOW_DEADBAND = 0.72f;
    private static final float PANEL_MOVE_DEADBAND = 1.5f;
    private static final float CENTER_SNAP_EPSILON = 0.45f;
    private static final float ANCHOR_SNAP_EPSILON = 0.45f;
    private static final float PANEL_SNAP_EPSILON = 0.5f;
    private static final float ANGLE_SNAP_EPSILON = 0.006f;
    private static final int PANEL_ANGLE_SEARCH_STEPS = 96;
    private static final float[] PROJECTED_TARGET = new float[2];
    private static final int[] REL_XS = new int[8];
    private static final int[] REL_YS = new int[8];
    private static final int[] SCRATCH_XS = new int[8];
    private static final int[] SCRATCH_YS = new int[8];
    private static final int[] SCRATCH_INTERSECTIONS = new int[16];

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
    private static float fixedPanelX = -1.0f;
    private static float fixedPanelY = -1.0f;
    private static float progressAnimation = 0.0f;

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
        int maxPanelW = Math.max(72, Math.round(width - EDGE_MARGIN * 2.0f));
        int minPanelW = Math.min(Math.round(MIN_PANEL_WIDTH * scale), maxPanelW);
        int panelW = clamp(Math.round(cachedPanelWidth * scale), minPanelW, maxPanelW);
        ToolHudStyle style = getHudStyle();
        int panelH = Math.round((style == ToolHudStyle.FIXED_CARD ? FIXED_PANEL_BASE_HEIGHT : ANCHORED_PANEL_BASE_HEIGHT) * scale);
        int offset = Math.round(Configs.TOOL_HUD_OFFSET.getIntegerValue() * scale);

        if (style == ToolHudStyle.ANCHORED_CARD && visible && updateFrame) {
            updateLayout(client, target, width, height, panelW, panelH, offset, scale, smoothing);
        } else if (style == ToolHudStyle.FIXED_CARD && updateFrame) {
            updateFixedLayout(width, height, panelW, panelH, smoothing);
        } else if ((style == ToolHudStyle.ANCHORED_CARD && !hasLayout()) || (style == ToolHudStyle.FIXED_CARD && !hasFixedLayout())) {
            return;
        }

        float eased = easeOutCubic(visibility);
        float opacity = clamp((float) Configs.TOOL_HUD_OPACITY.getDoubleValue(), 0.1f, 1.0f);
        int alpha = Math.round(255.0f * opacity * eased);

        if (style == ToolHudStyle.FIXED_CARD) {
            drawToolCard(context, client, Math.round(fixedPanelX), Math.round(fixedPanelY), panelW, panelH, scale, alpha, eased, tools.isWorking(), now);
            return;
        }

        int startX = Math.round(anchorX);
        int startY = Math.round(anchorY);
        int panelLeft = Math.round(panelX);
        int panelTop = Math.round(panelY);
        float[] edge = getPanelEdgePoint(anchorX, anchorY, panelX, panelY, panelW, panelH);
        int endX = Math.round(edge[0]);
        int endY = Math.round(edge[1]);

        drawLeader(context, startX, startY, endX, endY, alpha);
        drawAnchor(context, startX, startY, alpha);
        drawToolCard(context, client, panelLeft, panelTop, panelW, panelH, scale, alpha, eased, tools.isWorking(), now);
    }

    private static ToolHudStyle getHudStyle() {
        if (Configs.TOOL_HUD_STYLE.getOptionListValue() instanceof ToolHudStyle style) {
            return style;
        }
        return ToolHudStyle.FIXED_CARD;
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
        projectTarget(client, target, width, height);
        float targetCenterX = PROJECTED_TARGET[0];
        float targetCenterY = PROJECTED_TARGET[1];

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

    private static void updateFixedLayout(int width, int height, int panelW, int panelH, float smoothing) {
        float targetX = width * 0.5f + Configs.TOOL_HUD_CUSTOM_X.getIntegerValue();
        float targetY = height * 0.5f + Configs.TOOL_HUD_CUSTOM_Y.getIntegerValue();
        targetX = clamp(targetX, EDGE_MARGIN, Math.max(EDGE_MARGIN, width - panelW - EDGE_MARGIN));
        targetY = clamp(targetY, EDGE_MARGIN, Math.max(EDGE_MARGIN, height - panelH - EDGE_MARGIN));

        if (fixedPanelX < 0.0f || fixedPanelY < 0.0f) {
            fixedPanelX = targetX;
            fixedPanelY = targetY;
        } else {
            float panelSmoothing = Math.min(0.24f, smoothing * 0.55f);
            fixedPanelX = smoothTowardWithDeadband(fixedPanelX, targetX, panelSmoothing, PANEL_MOVE_DEADBAND, PANEL_SNAP_EPSILON);
            fixedPanelY = smoothTowardWithDeadband(fixedPanelY, targetY, panelSmoothing, PANEL_MOVE_DEADBAND, PANEL_SNAP_EPSILON);
        }

        float targetProgress = cachedMode == ContainerToolMode.PACK ? 0.82f : cachedMode == ContainerToolMode.COPY ? 0.68f : 0.55f;
        progressAnimation = smoothTowardWithSnap(progressAnimation, targetProgress, Math.min(0.34f, smoothing * 0.9f), 0.005f);
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

    private static void projectTarget(MinecraftClient client, BlockPos target, int width, int height) {
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
        PROJECTED_TARGET[0] = quantize(clamp(x, 18.0f, width - 18.0f), 0.25f);
        PROJECTED_TARGET[1] = quantize(clamp(y, 18.0f, height - 18.0f), 0.25f);
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
                        client.textRenderer.getWidth(cachedSecondaryHint)) + 58);
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

    private static void drawToolCard(DrawContext context, MinecraftClient client, int x, int y, int width, int height, float scale, int alpha, float eased, boolean showProgress, long nowNanos) {
        int panelAlpha = Math.round(alpha * 0.92f);
        int borderAlpha = Math.round(alpha * 0.88f);
        int shadowAlpha = Math.round(alpha * 0.34f);
        int pad = Math.max(8, Math.round(9.0f * scale));
        int headerHeight = Math.max(15, Math.round(17.0f * scale));
        int barHeight = Math.max(2, Math.round(2.0f * scale));
        int textHeight = Math.max(1, (int)Math.ceil(client.textRenderer.fontHeight * scale));
        int breathe = Math.round((float)Math.sin(nowNanos / 260_000_000.0D) * 2.0f * eased);
        int lineGap = Math.max(textHeight + 1, Math.round(11.0f * scale));
        int iconBaseX = x + pad + Math.round(12.0f * scale);
        int iconBaseY = y + headerHeight + Math.round(27.0f * scale);
        int iconCenterX = iconBaseX + (cachedMode == ContainerToolMode.COPY ? breathe : 0);
        int iconCenterY = iconBaseY + (cachedMode == ContainerToolMode.COPY ? 0 : breathe);
        float iconScale = scale * clamp(Configs.TOOL_HUD_ICON_SCALE.getIntegerValue() / 100.0f, 0.5f, 1.5f);
        int textX = x + pad + Math.round(36.0f * scale);

        drawRoundedInfoCard(context, x + 2, y + 3, width, height, withAlpha(0xFF000000, shadowAlpha));
        drawRoundedInfoCard(context, x, y, width, height, withAlpha(FIXED_PANEL_INNER, panelAlpha));
        if (Configs.TOOL_HUD_BORDER.getBooleanValue()) {
            drawRoundedInfoCardOutline(context, x, y, width, height, withAlpha(FIXED_PANEL_EDGE, borderAlpha));
        }
        context.fill(x + 5, y + 5, x + width - 5, y + headerHeight + 5, withAlpha(FIXED_PANEL_HEADER, Math.round(alpha * 0.46f)));
        int titleMaxWidth = Math.max(0, width - pad * 2);
        drawScaledText(context, client, ellipsize(client, StringUtils.translate("litematica_container_filler.hud.fixed.title"), unscaledWidth(titleMaxWidth, scale)),
                x + pad, y + 8, scale, withAlpha(TEXT, alpha));

        int labelY = y + headerHeight + 9;
        int textMaxWidth = Math.max(0, width - (textX - x) - pad);
        int textMaxUnscaled = unscaledWidth(textMaxWidth, scale);
        int progressReserve = showProgress ? barHeight + Math.max(3, Math.round(3.0f * scale)) : 0;
        int contentBottom = y + height - Math.max(5, Math.round(5.0f * scale)) - progressReserve;
        int maxLines = countFittingLines(labelY, lineGap, textHeight, contentBottom, cachedSecondaryHint.isEmpty() ? 2 : 3);
        drawHudToolIcon(context, iconCenterX, iconCenterY, iconScale, alpha, eased, cachedMode, nowNanos);
        if (maxLines <= 1) {
            drawScaledText(context, client, ellipsize(client, cachedHint, textMaxUnscaled), textX, Math.min(labelY, Math.max(y + headerHeight + 2, contentBottom - textHeight)), scale, withAlpha(0xFF55FF68, alpha));
        } else {
            drawScaledText(context, client, ellipsize(client, cachedLabel, textMaxUnscaled), textX, labelY, scale, withAlpha(MUTED_TEXT, Math.round(alpha * 0.88f)));
            drawScaledText(context, client, ellipsize(client, cachedHint, textMaxUnscaled), textX, labelY + lineGap, scale, withAlpha(0xFF55FF68, alpha));
        }
        if (maxLines >= 3 && !cachedSecondaryHint.isEmpty()) {
            drawScaledText(context, client, ellipsize(client, cachedSecondaryHint, textMaxUnscaled), textX, labelY + lineGap * 2, scale, withAlpha(MUTED_TEXT, Math.round(alpha * 0.78f)));
        }

        if (showProgress) {
            int barX = x + 5;
            int barY = y + height - barHeight - 2;
            int barW = Math.max(48, width - 10);
            int fillW = Math.max(5, Math.round(barW * clamp(progressAnimation, 0.08f, 1.0f)));
            context.fill(barX, barY, barX + barW, barY + barHeight, withAlpha(FIXED_PANEL_BAR_BG, Math.round(alpha * 0.58f)));
            context.fill(barX, barY, barX + fillW, barY + barHeight, withAlpha(FIXED_PANEL_BAR, Math.round(alpha * 0.95f)));
        }
    }

    private static int countFittingLines(int firstY, int lineGap, int textHeight, int bottom, int requestedLines) {
        int lines = 0;
        for (int i = 0; i < requestedLines; i++) {
            if (firstY + lineGap * i + textHeight <= bottom) {
                lines++;
            }
        }
        return lines;
    }

    private static void drawHudToolIcon(DrawContext context, int cx, int cy, float scale, int alpha, float eased, ContainerToolMode mode, long nowNanos) {
        ArrowDirection direction = switch (mode) {
            case CLEAR -> ArrowDirection.UP;
            case COPY -> ArrowDirection.RIGHT;
            case PACK, FILL_FULL -> ArrowDirection.DOWN;
        };

        if (mode == ContainerToolMode.PACK) {
            int boxW = Math.max(20, Math.round(24.0f * scale));
            int boxH = Math.max(15, Math.round(17.0f * scale));
            int boxX = cx - boxW / 2;
            int boxY = cy + Math.round(1.0f * scale);
            int box = withAlpha(0xFF8E55D9, Math.round(alpha * 0.58f));
            int edge = withAlpha(0xFFB68CFF, Math.round(alpha * 0.9f));
            int dark = withAlpha(0xFF2F1748, Math.round(alpha * 0.68f));
            context.fill(boxX + 1, boxY + 2, boxX + boxW - 1, boxY + boxH, box);
            context.fill(boxX, boxY, boxX + boxW, boxY + Math.max(4, Math.round(4.0f * scale)), edge);
            context.fill(boxX, boxY + 3, boxX + 2, boxY + boxH, edge);
            context.fill(boxX + boxW - 2, boxY + 3, boxX + boxW, boxY + boxH, edge);
            context.fill(boxX + Math.round(5.0f * scale), boxY + Math.round(7.0f * scale), boxX + boxW - Math.round(5.0f * scale), boxY + Math.round(9.0f * scale), dark);
            int arrowBounce = Math.round((float)Math.sin(nowNanos / 250_000_000.0D) * 1.8f * eased);
            drawHudArrowIcon(context, cx, cy - Math.round(13.0f * scale) + arrowBounce, scale * 0.52f, alpha, direction);
            return;
        }

        drawHudArrowIcon(context, cx, cy, scale, alpha, direction);
    }

    private static void drawHudArrowIcon(DrawContext context, int cx, int cy, float scale, int alpha, ArrowDirection direction) {
        int base = Configs.HIGHLIGHT_COLOR_FILLING.getColor().intValue;
        int body = withAlpha(base, Math.round(alpha * 0.88f));
        int shine = withAlpha(0xFFFFFFFF, Math.round(alpha * 0.28f));
        int shaft = Math.max(3, Math.round(3.5f * scale));
        int halfW = Math.max(7, Math.round(10.5f * scale));
        int top = cy - Math.round(13.0f * scale);
        int headY = cy - Math.round(1.0f * scale);
        int shoulderY = cy + Math.round(1.0f * scale);
        int tailY = cy + Math.round(15.0f * scale);

        REL_XS[0] = -shaft;
        REL_XS[1] = shaft;
        REL_XS[2] = shaft;
        REL_XS[3] = halfW;
        REL_XS[4] = 0;
        REL_XS[5] = -halfW;
        REL_XS[6] = -shaft;
        REL_YS[0] = top - cy;
        REL_YS[1] = top - cy;
        REL_YS[2] = headY - cy;
        REL_YS[3] = shoulderY - cy;
        REL_YS[4] = tailY - cy;
        REL_YS[5] = shoulderY - cy;
        REL_YS[6] = headY - cy;
        fillRotatedPolygon(context, cx, cy, direction, body, REL_XS, REL_YS, 7);
        fillRotatedRect(context, cx, cy, -1, top - cy + 2, 2, tailY - cy - 3, shine, direction);
    }

    private static void drawScaledText(DrawContext context, MinecraftClient client, String text, int x, int y, float scale, int color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (Math.abs(scale - 1.0f) < 0.01f) {
            context.drawTextWithShadow(client.textRenderer, text, x, y, color);
            return;
        }

        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(x, y, 0.0f);
        matrices.scale(scale, scale, 1.0f);
        context.drawTextWithShadow(client.textRenderer, text, 0, 0, color);
        matrices.pop();
    }

    private static int unscaledWidth(int scaledWidth, float scale) {
        if (scaledWidth <= 0 || scale <= 0.01f) {
            return 0;
        }
        return Math.max(0, (int)Math.floor(scaledWidth / scale));
    }

    private static String ellipsize(MinecraftClient client, String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (client.textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }

        String suffix = "...";
        int suffixWidth = client.textRenderer.getWidth(suffix);
        if (suffixWidth > maxWidth) {
            return "";
        }

        int end = text.length();
        while (end > 0 && client.textRenderer.getWidth(text.substring(0, end)) + suffixWidth > maxWidth) {
            end--;
        }
        return end <= 0 ? suffix : text.substring(0, end) + suffix;
    }

    private static void fillRotatedRect(DrawContext context, int cx, int cy, int relX1, int relY1, int relX2, int relY2, int color, ArrowDirection direction) {
        int ax = rotateX(cx, cy, relX1, relY1, direction);
        int ay = rotateY(cx, cy, relX1, relY1, direction);
        int bx = rotateX(cx, cy, relX2, relY2, direction);
        int by = rotateY(cx, cy, relX2, relY2, direction);
        context.fill(Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx), Math.max(ay, by), color);
    }

    private static void fillRotatedPolygon(DrawContext context, int cx, int cy, ArrowDirection direction, int color, int[] relXs, int[] relYs, int count) {
        for (int i = 0; i < count; i++) {
            SCRATCH_XS[i] = rotateX(cx, cy, relXs[i], relYs[i], direction);
            SCRATCH_YS[i] = rotateY(cx, cy, relXs[i], relYs[i], direction);
        }
        fillPolygon(context, SCRATCH_XS, SCRATCH_YS, count, color);
    }

    private static void fillPolygon(DrawContext context, int[] xs, int[] ys, int count, int color) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            minY = Math.min(minY, ys[i]);
            maxY = Math.max(maxY, ys[i]);
        }

        for (int y = minY; y <= maxY; y++) {
            int intersectionCount = 0;
            for (int i = 0; i < count; i++) {
                int next = (i + 1) % count;
                int y1 = ys[i];
                int y2 = ys[next];
                if ((y1 <= y && y2 > y) || (y2 <= y && y1 > y)) {
                    double t = (y - y1) / (double)(y2 - y1);
                    SCRATCH_INTERSECTIONS[intersectionCount++] = (int)Math.round(xs[i] + (xs[next] - xs[i]) * t);
                }
            }
            sortScratchIntersections(intersectionCount);
            for (int i = 0; i + 1 < intersectionCount; i += 2) {
                context.fill(SCRATCH_INTERSECTIONS[i], y, SCRATCH_INTERSECTIONS[i + 1] + 1, y + 1, color);
            }
        }
    }

    private static void sortScratchIntersections(int count) {
        for (int i = 1; i < count; i++) {
            int value = SCRATCH_INTERSECTIONS[i];
            int j = i - 1;
            while (j >= 0 && SCRATCH_INTERSECTIONS[j] > value) {
                SCRATCH_INTERSECTIONS[j + 1] = SCRATCH_INTERSECTIONS[j];
                j--;
            }
            SCRATCH_INTERSECTIONS[j + 1] = value;
        }
    }

    private static int rotateX(int cx, int cy, int x, int y, ArrowDirection direction) {
        return switch (direction) {
            case DOWN -> cx + x;
            case UP -> cx - x;
            case RIGHT -> cx + y;
        };
    }

    private static int rotateY(int cx, int cy, int x, int y, ArrowDirection direction) {
        return switch (direction) {
            case DOWN -> cy + y;
            case UP -> cy - y;
            case RIGHT -> cy - x;
        };
    }

    private enum ArrowDirection {
        DOWN,
        UP,
        RIGHT
    }

    private static void drawRoundedInfoCard(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x + 4, y, x + width - 4, y + height, color);
        context.fill(x, y + 4, x + width, y + height - 4, color);
        context.fill(x + 2, y + 2, x + width - 2, y + height - 2, color);
    }

    private static void drawRoundedInfoCardOutline(DrawContext context, int x, int y, int width, int height, int color) {
        if (width <= 2 || height <= 2) {
            return;
        }

        for (int row = 0; row < height; row++) {
            int outerInset = Math.min(infoCardInset(row, height), Math.max(0, (width - 1) / 2));
            int outerLeft = x + outerInset;
            int outerRight = x + width - outerInset;
            if (outerLeft >= outerRight) {
                continue;
            }

            if (row == 0 || row == height - 1) {
                context.fill(outerLeft, y + row, outerRight, y + row + 1, color);
                continue;
            }

            int innerWidth = width - 2;
            int innerHeight = height - 2;
            int innerInset = Math.min(infoCardInset(row - 1, innerHeight), Math.max(0, (innerWidth - 1) / 2));
            int innerLeft = x + 1 + innerInset;
            int innerRight = x + width - 1 - innerInset;
            if (outerLeft < innerLeft) {
                context.fill(outerLeft, y + row, Math.min(innerLeft, outerRight), y + row + 1, color);
            }
            if (innerRight < outerRight) {
                context.fill(Math.max(innerRight, outerLeft), y + row, outerRight, y + row + 1, color);
            }
        }
    }

    private static int infoCardInset(int row, int height) {
        int edgeDistance = Math.min(row, height - row - 1);
        if (edgeDistance <= 1) {
            return 4;
        }
        if (edgeDistance <= 3) {
            return 2;
        }
        return 0;
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

    private static boolean hasFixedLayout() {
        return fixedPanelX >= 0.0f && fixedPanelY >= 0.0f;
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
