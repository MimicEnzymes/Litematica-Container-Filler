package com.mimicenzymes.litematicafiller.gui;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.config.ToolHudStyle;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigColor;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;

import java.util.List;

public class GuiRenderEditor extends GuiBase {
    private static final int PANEL = 0xE5141A20;
    private static final int PANEL_SOFT = 0xB91F2A34;
    private static final int BORDER = 0xFF3B5965;
    private static final int ACCENT = 0xFF18F6E8;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFF9FB1B8;
    private static final int WARNING = 0xFFFFB02E;

    private final Screen parent;
    private EditorTab tab = EditorTab.HIGHLIGHTS;

    public GuiRenderEditor(Screen parent) {
        this.parent = parent;
        this.setParent(parent);
        this.title = tr("litematica_container_filler.gui.title.render_editor");
    }

    @Override
    public Screen getParent() {
        return parent;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void close() {
        GuiBase.openGui(this.getParent());
    }

    @Override
    public void initGui() {
        super.initGui();
        this.clearButtons();

        int x = 18;
        int y = 46;
        for (EditorTab value : EditorTab.values()) {
            String key = "litematica_container_filler.gui.button.render_editor." + value.key;
            ButtonGeneric button = new ButtonGeneric(x, y, 86, 20, tr(key));
            button.setEnabled(value != this.tab);
            this.addButton(button, (clickedButton, mouseButton) -> {
                this.tab = value;
                this.initGui();
            });
            x += 90;
        }

        int right = this.getScreenWidth() - 26;
        this.addButton(new ButtonGeneric(right - 80, this.getScreenHeight() - 30, 80, 20, tr("litematica_container_filler.gui.button.back")),
                (button, mouseButton) -> GuiBase.openGui(parent));

        addTabButtons();
    }

    private void addTabButtons() {
        int controlsX = Math.max(300, this.getScreenWidth() / 2 + 38);
        int y = 92;

        switch (this.tab) {
            case HIGHLIGHTS -> {
                y = addBooleanButton(controlsX, y, Configs.HIGHLIGHT_CONTAINERS);
                y = addBooleanButton(controlsX, y, Configs.HIGHLIGHT_XRAY);
                y = addBooleanButton(controlsX, y, Configs.RENDER_STATE_GLASS);
                y = addBooleanButton(controlsX, y, Configs.RENDER_STATE_TOP_PLATE);
                y = addBooleanButton(controlsX, y, Configs.RENDER_STATE_UNFILLED);
                y = addBooleanButton(controlsX, y, Configs.RENDER_STATE_PARTIAL);
                y = addBooleanButton(controlsX, y, Configs.RENDER_STATE_WRONG);
                y = addBooleanButton(controlsX, y, Configs.RENDER_STATE_UNKNOWN);
                y = addStepper(controlsX, y, Configs.HIGHLIGHT_GLASS_ALPHA_MULTIPLIER, 0.05D);
                y = addStepper(controlsX, y, Configs.HIGHLIGHT_TOP_PLATE_SIZE, 0.05D);
                y = addColorCycle(controlsX, y, Configs.HIGHLIGHT_COLOR_UNFILLED);
                y = addColorCycle(controlsX, y, Configs.HIGHLIGHT_COLOR_PARTIAL);
                y = addColorCycle(controlsX, y, Configs.HIGHLIGHT_COLOR_WRONG);
                addColorCycle(controlsX, y, Configs.HIGHLIGHT_COLOR_UNKNOWN);
            }
            case MARKERS -> {
                y = addBooleanButton(controlsX, y, Configs.RENDER_FILLING_ARROW);
                y = addBooleanButton(controlsX, y, Configs.RENDER_QUEUED_SPINNER);
                y = addBooleanButton(controlsX, y, Configs.RENDER_MISSING_MATERIAL_MARKER);
                y = addStepper(controlsX, y, Configs.TASK_OVERLAY_SCALE, 0.10D);
                y = addStepper(controlsX, y, Configs.MAX_QUEUED_RENDER_OVERLAYS, 5);
                y = addStepper(controlsX, y, Configs.TASK_MARKER_ANIMATION_FPS, 5);
                addStepper(controlsX, y, Configs.TASK_OVERLAY_LINGER_TICKS, 10);
            }
            case HUD -> {
                y = addBooleanButton(controlsX, y, Configs.ENABLE_TOOL_HUD);
                y = addOptionButton(controlsX, y, Configs.TOOL_HUD_STYLE);
                y = addStepper(controlsX, y, Configs.TOOL_HUD_OPACITY, 0.05D);
                y = addStepper(controlsX, y, Configs.TOOL_HUD_SMOOTHING, 0.05D);
                y = addStepper(controlsX, y, Configs.TOOL_HUD_OFFSET, 4);
                y = addStepper(controlsX, y, Configs.TOOL_HUD_SCALE, 5);
                addStepper(controlsX, y, Configs.TOOL_HUD_FRAME_RATE, 5);
            }
        }
    }

    private int addBooleanButton(int x, int y, ConfigBoolean config) {
        ButtonGeneric button = new ButtonGeneric(x, y, 210, 20, booleanLabel(config));
        button.setHoverStrings(tr(config.getComment()));
        this.addButton(button, (clickedButton, mouseButton) -> {
            config.setBooleanValue(!config.getBooleanValue());
            Configs.saveToFile();
            this.initGui();
        });
        return y + 24;
    }

    private int addOptionButton(int x, int y, ConfigOptionList config) {
        ButtonGeneric button = new ButtonGeneric(x, y, 210, 20, optionLabel(config));
        button.setHoverStrings(tr(config.getComment()));
        this.addButton(button, (clickedButton, mouseButton) -> {
            config.setOptionListValue(config.getOptionListValue().cycle(mouseButton == 0));
            Configs.saveToFile();
            this.initGui();
        });
        return y + 24;
    }

    private int addStepper(int x, int y, ConfigInteger config, int step) {
        this.addButton(new ButtonGeneric(x, y, 24, 20, "-"), (button, mouseButton) -> {
            config.setIntegerValue(config.getIntegerValue() - step);
            Configs.saveToFile();
            this.initGui();
        });
        ButtonGeneric value = new ButtonGeneric(x + 28, y, 126, 20, integerLabel(config));
        value.setHoverStrings(tr(config.getComment()));
        this.addButton(value, (button, mouseButton) -> {});
        this.addButton(new ButtonGeneric(x + 158, y, 24, 20, "+"), (button, mouseButton) -> {
            config.setIntegerValue(config.getIntegerValue() + step);
            Configs.saveToFile();
            this.initGui();
        });
        this.addButton(new ButtonGeneric(x + 186, y, 54, 20, tr("litematica_container_filler.gui.button.reset")), (button, mouseButton) -> {
            config.resetToDefault();
            Configs.saveToFile();
            this.initGui();
        });
        return y + 24;
    }

    private int addStepper(int x, int y, ConfigDouble config, double step) {
        this.addButton(new ButtonGeneric(x, y, 24, 20, "-"), (button, mouseButton) -> {
            config.setDoubleValue(config.getDoubleValue() - step);
            Configs.saveToFile();
            this.initGui();
        });
        ButtonGeneric value = new ButtonGeneric(x + 28, y, 126, 20, doubleLabel(config));
        value.setHoverStrings(tr(config.getComment()));
        this.addButton(value, (button, mouseButton) -> {});
        this.addButton(new ButtonGeneric(x + 158, y, 24, 20, "+"), (button, mouseButton) -> {
            config.setDoubleValue(config.getDoubleValue() + step);
            Configs.saveToFile();
            this.initGui();
        });
        this.addButton(new ButtonGeneric(x + 186, y, 54, 20, tr("litematica_container_filler.gui.button.reset")), (button, mouseButton) -> {
            config.resetToDefault();
            Configs.saveToFile();
            this.initGui();
        });
        return y + 24;
    }

    private int addColorCycle(int x, int y, ConfigColor config) {
        this.addButton(new ButtonGeneric(x, y, 210, 20, colorLabel(config)), (button, mouseButton) -> {
            cycleColor(config);
            Configs.saveToFile();
            this.initGui();
        });
        return y + 24;
    }

    @Override
    protected void drawScreenBackground(DrawContext drawContext, int mouseX, int mouseY) {
        drawContext.fill(0, 0, this.getScreenWidth(), this.getScreenHeight(), 0xDD070A0E);
        drawContext.fill(10, 10, this.getScreenWidth() - 10, this.getScreenHeight() - 10, 0xE711171D);
        drawContext.fill(10, 10, this.getScreenWidth() - 10, 40, 0xF01B2832);
        drawContext.fill(10, 40, this.getScreenWidth() - 10, 42, ACCENT);
    }

    @Override
    protected void drawTitle(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
    }

    @Override
    protected void drawContents(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        super.drawContents(drawContext, mouseX, mouseY, partialTicks);
        drawString(drawContext, this.title, 18, 18, TEXT);
        drawString(drawContext, tr("litematica_container_filler.gui.label.render_editor"), 168, 19, MUTED);

        int previewX = 22;
        int previewY = 82;
        int previewW = Math.max(250, this.getScreenWidth() / 2 - 46);
        int previewH = this.getScreenHeight() - 124;
        int controlsX = Math.max(300, this.getScreenWidth() / 2 + 28);
        int controlsW = this.getScreenWidth() - controlsX - 22;

        drawCard(drawContext, previewX, previewY, previewW, previewH);
        drawCard(drawContext, controlsX, previewY, controlsW, previewH);
        drawString(drawContext, tr("litematica_container_filler.gui.label.render_editor_preview"), previewX + 12, previewY + 10, TEXT);
        drawString(drawContext, tr("litematica_container_filler.gui.label.render_editor_settings"), controlsX + 12, previewY + 10, TEXT);
        drawTabDescription(drawContext, controlsX + 12, previewY + 28, controlsW - 24);
        drawPreview(drawContext, previewX + 14, previewY + 34, previewW - 28, previewH - 48, partialTicks);
    }

    private void drawTabDescription(DrawContext context, int x, int y, int width) {
        String key = "litematica_container_filler.gui.label.render_editor." + this.tab.key + ".desc";
        for (String line : wrap(tr(key), Math.max(18, width / 6))) {
            drawString(context, line, x, y, MUTED);
            y += 11;
        }
    }

    private void drawPreview(DrawContext context, int x, int y, int width, int height, float partialTicks) {
        drawGradient(context, x, y, x + width, y + height, 0xFF101922, 0xFF172A2C);
        drawGrid(context, x, y, width, height);
        switch (this.tab) {
            case HIGHLIGHTS -> drawHighlightPreview(context, x, y, width, height);
            case MARKERS -> drawMarkerPreview(context, x, y, width, height);
            case HUD -> drawHudPreview(context, x, y, width, height);
        }
    }

    private void drawHighlightPreview(DrawContext context, int x, int y, int width, int height) {
        int boxW = Math.max(42, width / 5);
        int boxH = Math.max(44, height / 4);
        int baseY = y + height / 2 - boxH / 2;
        List<StatePreview> states = List.of(
                new StatePreview("unfilled", Configs.HIGHLIGHT_COLOR_UNFILLED),
                new StatePreview("partial", Configs.HIGHLIGHT_COLOR_PARTIAL),
                new StatePreview("wrong", Configs.HIGHLIGHT_COLOR_WRONG),
                new StatePreview("satisfied", Configs.HIGHLIGHT_COLOR_SATISFIED),
                new StatePreview("unknown", Configs.HIGHLIGHT_COLOR_UNKNOWN)
        );

        int gap = Math.max(8, (width - boxW * states.size()) / (states.size() + 1));
        for (int i = 0; i < states.size(); i++) {
            StatePreview state = states.get(i);
            int bx = x + gap + i * (boxW + gap);
            drawContainerGlyph(context, bx, baseY, boxW, boxH, state.color.getColor());
            drawString(context, tr("litematica_container_filler.gui.label.render_state." + state.key), bx, baseY + boxH + 10, MUTED);
        }
    }

    private void drawMarkerPreview(DrawContext context, int x, int y, int width, int height) {
        int centerX = x + width / 2;
        int baseY = y + height / 2 + 34;
        int boxW = 68;
        int boxH = 44;
        drawContainerGlyph(context, centerX - boxW / 2, baseY - boxH / 2, boxW, boxH, Configs.HIGHLIGHT_COLOR_UNFILLED.getColor());

        double time = quantizedTime(Configs.TASK_MARKER_ANIMATION_FPS.getIntegerValue());
        if (Configs.RENDER_FILLING_ARROW.getBooleanValue()) {
            drawFlatArrow(context, centerX - 74, baseY - 78 + (int)(Math.sin(time * 5.0D) * 3.0D), Configs.HIGHLIGHT_COLOR_FILLING.getColor());
            drawString(context, tr("litematica_container_filler.gui.label.marker_filling"), centerX - 112, baseY - 32, TEXT);
        }
        if (Configs.RENDER_QUEUED_SPINNER.getBooleanValue()) {
            drawSpinner(context, centerX + 72, baseY - 74, time, Configs.HIGHLIGHT_COLOR_QUEUED.getColor());
            drawString(context, tr("litematica_container_filler.gui.label.marker_queued"), centerX + 40, baseY - 32, TEXT);
        }
        if (Configs.RENDER_MISSING_MATERIAL_MARKER.getBooleanValue()) {
            drawMissingMarker(context, centerX, baseY - 88, Configs.HIGHLIGHT_COLOR_MISSING_MATERIAL.getColor());
            drawString(context, tr("litematica_container_filler.gui.label.marker_missing"), centerX - 38, baseY - 110, WARNING);
        }
    }

    private void drawHudPreview(DrawContext context, int x, int y, int width, int height) {
        int targetX = x + width / 2 - 70;
        int targetY = y + height / 2 + 12;
        drawContainerGlyph(context, targetX - 22, targetY - 18, 44, 36, Configs.HIGHLIGHT_COLOR_SATISFIED.getColor());

        double opacity = Configs.TOOL_HUD_OPACITY.getDoubleValue();
        int alpha = clamp((int)(255.0D * opacity), 25, 255);
        int panelW = Math.round(150 * Configs.TOOL_HUD_SCALE.getIntegerValue() / 100.0f);
        ToolHudStyle style = Configs.TOOL_HUD_STYLE.getOptionListValue() instanceof ToolHudStyle hudStyle ? hudStyle : ToolHudStyle.FIXED_CARD;
        int panelH = Math.round((style == ToolHudStyle.FIXED_CARD ? 58 : 48) * Configs.TOOL_HUD_SCALE.getIntegerValue() / 100.0f);

        if (style == ToolHudStyle.ANCHORED_CARD) {
            int panelX = x + width / 2 + Configs.TOOL_HUD_OFFSET.getIntegerValue();
            int panelY = y + height / 2 - panelH / 2;
            int anchorX = targetX + 22;
            int anchorY = targetY - 18;
            int cornerX = panelX - 28;
            int cornerY = anchorY;

            drawLine(context, anchorX, anchorY, cornerX, cornerY, withAlpha(ACCENT, alpha));
            drawLine(context, cornerX, cornerY, panelX, panelY + panelH / 2, withAlpha(ACCENT, alpha));
            context.fill(anchorX - 3, anchorY - 3, anchorX + 4, anchorY + 4, withAlpha(ACCENT, alpha));
            drawLegacyHudCard(context, panelX, panelY, panelW, panelH, alpha);
            return;
        }

        int panelX = x + width / 2 + Math.max(24, Configs.TOOL_HUD_OFFSET.getIntegerValue());
        int panelY = y + height / 3 - panelH / 2;
        drawFixedHudCard(context, panelX, panelY, panelW, panelH, alpha);
    }

    private void drawLegacyHudCard(DrawContext context, int panelX, int panelY, int panelW, int panelH, int alpha) {
        drawSoftRect(context, panelX, panelY, panelW, panelH, withAlpha(0xFF071014, (int)(alpha * 0.78D)));
        context.fill(panelX, panelY, panelX + 2, panelY + panelH, withAlpha(ACCENT, alpha));
        drawString(context, tr("litematica_container_filler.gui.label.hud_title"), panelX + 10, panelY + 7, withAlpha(TEXT, alpha));
        drawString(context, tr("litematica_container_filler.gui.label.hud_hint"), panelX + 10, panelY + 22, withAlpha(MUTED, alpha));
    }

    private void drawFixedHudCard(DrawContext context, int panelX, int panelY, int panelW, int panelH, int alpha) {
        drawSoftRect(context, panelX + 2, panelY + 3, panelW, panelH, withAlpha(0xFF000000, (int)(alpha * 0.32D)));
        drawSoftRect(context, panelX, panelY, panelW, panelH, withAlpha(0xFFE6F2E8, (int)(alpha * 0.88D)));
        drawSoftRect(context, panelX + 2, panelY + 2, panelW - 4, panelH - 4, withAlpha(0xFF050708, (int)(alpha * 0.92D)));
        context.fill(panelX + 6, panelY + 6, panelX + panelW - 6, panelY + 20, withAlpha(0xFF2A332B, (int)(alpha * 0.46D)));
        drawString(context, tr("litematica_container_filler.gui.label.hud_title"), panelX + 34, panelY + 8, withAlpha(TEXT, alpha));
        drawGogglesIcon(context, panelX + 10, panelY + 8, 17, alpha);
        drawString(context, tr("litematica_container_filler.gui.label.hud_hint"), panelX + 34, panelY + 24, withAlpha(MUTED, alpha));
        int barX = panelX + 34;
        int barY = panelY + panelH - 14;
        int barW = panelW - 46;
        context.fill(barX, barY, barX + barW, barY + 8, withAlpha(0xFF0B2310, alpha));
        context.fill(barX, barY, barX + Math.round(barW * 0.62f), barY + 8, withAlpha(0xFF35F05E, alpha));
    }

    private void drawGogglesIcon(DrawContext context, int x, int y, int size, int alpha) {
        int gold = withAlpha(0xFFFFA629, alpha);
        int glass = withAlpha(0xFF5A2B10, Math.round(alpha * 0.78f));
        int lens = Math.max(5, size / 2 - 1);
        int gap = Math.max(3, size / 5);
        drawRing(context, x, y + 2, lens, gold, glass);
        drawRing(context, x + lens + gap, y + 2, lens, gold, glass);
        context.fill(x + lens - 1, y + 2 + lens / 2, x + lens + gap + 1, y + 4 + lens / 2, gold);
    }

    private void drawRing(DrawContext context, int x, int y, int size, int border, int fill) {
        context.fill(x, y + 2, x + size, y + size - 2, border);
        context.fill(x + 2, y, x + size - 2, y + size, border);
        context.fill(x + 2, y + 2, x + size - 2, y + size - 2, fill);
    }

    private void drawContainerGlyph(DrawContext context, int x, int y, int width, int height, Color4f color) {
        int argb = color.toVanillaArgb();
        int fill = withAlpha(argb, clamp((int)(color.a * 210.0f), 42, 210));
        int top = withAlpha(argb, clamp((int)(color.a * 255.0f), 80, 255));
        context.fill(x + 6, y + 8, x + width - 6, y + height, withAlpha(0xFF000000, 70));
        context.fill(x + 4, y + 4, x + width - 4, y + height - 4, fill);
        context.fill(x + 8, y, x + width - 8, y + 7, top);
        context.fill(x + 4, y + 4, x + 8, y + height - 4, top);
        context.fill(x + width - 8, y + 4, x + width - 4, y + height - 4, top);
        context.fill(x + 10, y + height / 2 - 1, x + width - 10, y + height / 2 + 1, withAlpha(0xFFFFFFFF, 70));
    }

    private void drawFlatArrow(DrawContext context, int x, int y, Color4f color) {
        int c = color.toVanillaArgb();
        int glow = withAlpha(c, 58);
        int body = withAlpha(c, 210);
        context.fill(x - 18, y - 8, x + 18, y + 8, glow);
        context.fill(x - 6, y - 28, x + 6, y - 4, body);
        context.fill(x - 22, y - 8, x + 22, y + 4, body);
        context.fill(x - 16, y + 4, x + 16, y + 12, body);
        context.fill(x - 9, y + 12, x + 9, y + 19, body);
        context.fill(x - 4, y + 19, x + 4, y + 25, body);
        context.fill(x - 3, y - 26, x + 3, y + 20, withAlpha(0xFFFFFFFF, 70));
    }

    private void drawSpinner(DrawContext context, int cx, int cy, double time, Color4f color) {
        int base = color.toVanillaArgb();
        for (int i = 0; i < 8; i++) {
            double angle = time * 3.2D + i * Math.PI / 4.0D;
            int x = cx + (int)Math.round(Math.cos(angle) * 23.0D);
            int y = cy + (int)Math.round(Math.sin(angle) * 23.0D);
            int alpha = 60 + i * 19;
            context.fill(x - 3, y - 3, x + 4, y + 4, withAlpha(base, alpha));
        }
        context.fill(cx - 7, cy - 7, cx + 8, cy + 8, withAlpha(base, 42));
    }

    private void drawMissingMarker(DrawContext context, int cx, int cy, Color4f color) {
        int c = color.toVanillaArgb();
        context.fill(cx - 6, cy - 24, cx + 7, cy + 10, withAlpha(c, 220));
        context.fill(cx - 8, cy + 16, cx + 9, cy + 30, withAlpha(c, 220));
        context.fill(cx - 2, cy - 20, cx + 3, cy + 8, withAlpha(0xFFFFFFFF, 80));
    }

    private void drawCard(DrawContext context, int x, int y, int width, int height) {
        drawSoftRect(context, x + 2, y + 3, width, height, 0x66000000);
        drawSoftRect(context, x, y, width, height, PANEL);
        context.fill(x, y, x + width, y + 1, BORDER);
        context.fill(x, y, x + 2, y + height, PANEL_SOFT);
    }

    private void drawGrid(DrawContext context, int x, int y, int width, int height) {
        for (int gx = x; gx < x + width; gx += 18) {
            context.fill(gx, y, gx + 1, y + height, 0x1DFFFFFF);
        }
        for (int gy = y; gy < y + height; gy += 18) {
            context.fill(x, gy, x + width, gy + 1, 0x1DFFFFFF);
        }
    }

    private void drawGradient(DrawContext context, int x1, int y1, int x2, int y2, int top, int bottom) {
        context.fillGradient(x1, y1, x2, y2, top, bottom);
    }

    private void drawSoftRect(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x + 2, y, x + width - 2, y + height, color);
        context.fill(x, y + 2, x + width, y + height - 2, color);
        context.fill(x + 1, y + 1, x + width - 1, y + height - 1, color);
    }

    private void drawLine(DrawContext context, int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        if (steps <= 0) {
            context.fill(x1, y1, x1 + 1, y1 + 1, color);
            return;
        }
        float stepX = (x2 - x1) / (float)steps;
        float stepY = (y2 - y1) / (float)steps;
        for (int i = 0; i <= steps; i++) {
            int px = Math.round(x1 + stepX * i);
            int py = Math.round(y1 + stepY * i);
            context.fill(px, py, px + 1, py + 1, color);
        }
    }

    private String booleanLabel(ConfigBoolean config) {
        return tr(config.getName()) + ": " + tr(config.getBooleanValue()
                ? "litematica_container_filler.gui.value.on"
                : "litematica_container_filler.gui.value.off");
    }

    private String integerLabel(ConfigInteger config) {
        String value = config.getIntegerValue() == 0 && (config == Configs.TASK_MARKER_ANIMATION_FPS || config == Configs.TOOL_HUD_FRAME_RATE)
                ? tr("litematica_container_filler.gui.value.unlimited")
                : Integer.toString(config.getIntegerValue());
        return tr(config.getName()) + ": " + value;
    }

    private String doubleLabel(ConfigDouble config) {
        return tr(config.getName()) + ": " + String.format(java.util.Locale.ROOT, "%.2f", config.getDoubleValue());
    }

    private String colorLabel(ConfigColor config) {
        return tr(config.getName()) + ": " + config.getStringValue();
    }

    private String optionLabel(ConfigOptionList config) {
        return tr(config.getName()) + ": " + config.getOptionListValue().getDisplayName();
    }

    private void cycleColor(ConfigColor config) {
        String current = config.getStringValue();
        String next = current.equalsIgnoreCase("0x806E5CFF") ? "0x80FFB02E" :
                current.equalsIgnoreCase("0x80FFB02E") ? "0x80FF5A45" :
                current.equalsIgnoreCase("0x80FF5A45") ? "0x8044FFB2" :
                current.equalsIgnoreCase("0x8044FFB2") ? "0x80B66DFF" : "0x806E5CFF";
        config.setValueFromString(next);
    }

    private double quantizedTime(int fpsLimit) {
        double time = System.nanoTime() / 1_000_000_000.0D;
        if (fpsLimit <= 0) return time;
        return Math.floor(time * fpsLimit) / fpsLimit;
    }

    private List<String> wrap(String text, int maxChars) {
        if (text == null || text.isEmpty()) return List.of();
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        for (String paragraph : text.split("\\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (line.length() > 0 && line.length() + word.length() + 1 > maxChars) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
            if (line.length() > 0) lines.add(line.toString());
        }
        return lines;
    }

    private static String tr(String key) {
        return StringUtils.translate(key);
    }

    private static int withAlpha(int argb, int alpha) {
        return (clamp(alpha, 0, 255) << 24) | (argb & 0x00FFFFFF);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record StatePreview(String key, ConfigColor color) {
    }

    private enum EditorTab {
        HIGHLIGHTS("highlights"),
        MARKERS("markers"),
        HUD("hud");

        private final String key;

        EditorTab(String key) {
            this.key = key;
        }
    }
}
