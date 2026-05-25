package com.mimicenzymes.litematicafiller.gui;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.config.ToolHudStyle;
import com.mimicenzymes.litematicafiller.tool.ContainerToolMode;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigColor;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
    private float previewYaw = -28.0f;
    private float previewPitch = 18.0f;
    private boolean draggingPreview = false;
    private boolean draggingHudPosition = false;
    private int lastDragX = 0;
    private int lastDragY = 0;

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

        addTabControls();
    }

    private void addTabControls() {
        List<ControlSpec> controls = getControlsForTab();
        ControlLayout layout = getControlLayout(controls.size());
        for (int i = 0; i < controls.size(); i++) {
            ControlRect rect = layout.rectFor(i);
            controls.get(i).addButtons.accept(rect);
        }
    }

    private List<ControlSpec> getControlsForTab() {
        List<ControlSpec> controls = new ArrayList<>();
        switch (this.tab) {
            case HIGHLIGHTS -> {
                controls.add(booleanControl(Configs.HIGHLIGHT_CONTAINERS));
                controls.add(booleanControl(Configs.HIGHLIGHT_XRAY));
                controls.add(booleanControl(Configs.RENDER_STATE_GLASS));
                controls.add(booleanControl(Configs.RENDER_STATE_TOP_PLATE));
                controls.add(booleanControl(Configs.RENDER_STATE_UNFILLED));
                controls.add(booleanControl(Configs.RENDER_STATE_PARTIAL));
                controls.add(booleanControl(Configs.RENDER_STATE_OVERFILLED));
                controls.add(booleanControl(Configs.RENDER_STATE_WRONG));
                controls.add(booleanControl(Configs.RENDER_STATE_SATISFIED));
                controls.add(booleanControl(Configs.RENDER_STATE_UNKNOWN));
                controls.add(booleanControl(Configs.HIGHLIGHT_UNPLACED_CONTAINERS));
                controls.add(doubleStepperControl(Configs.HIGHLIGHT_GLASS_ALPHA_MULTIPLIER, 0.05D));
                controls.add(doubleStepperControl(Configs.HIGHLIGHT_TOP_PLATE_SIZE, 0.05D));
                controls.add(colorControl(Configs.HIGHLIGHT_COLOR_UNFILLED));
                controls.add(colorControl(Configs.HIGHLIGHT_COLOR_PARTIAL));
                controls.add(colorControl(Configs.HIGHLIGHT_COLOR_OVERFILLED));
                controls.add(colorControl(Configs.HIGHLIGHT_COLOR_WRONG));
                controls.add(colorControl(Configs.HIGHLIGHT_COLOR_SATISFIED));
                controls.add(colorControl(Configs.HIGHLIGHT_COLOR_UNKNOWN));
                controls.add(colorControl(Configs.HIGHLIGHT_COLOR_UNPLACED));
            }
            case MARKERS -> {
                controls.add(booleanControl(Configs.RENDER_FILLING_ARROW));
                controls.add(booleanControl(Configs.RENDER_QUEUED_SPINNER));
                controls.add(booleanControl(Configs.RENDER_MISSING_MATERIAL_MARKER));
                controls.add(doubleStepperControl(Configs.TASK_OVERLAY_SCALE, 0.10D));
                controls.add(integerStepperControl(Configs.MAX_QUEUED_RENDER_OVERLAYS, 5));
                controls.add(integerStepperControl(Configs.TASK_MARKER_ANIMATION_FPS, 5));
                controls.add(integerStepperControl(Configs.TASK_OVERLAY_LINGER_TICKS, 10));
                controls.add(colorControl(Configs.HIGHLIGHT_COLOR_FILLING));
                controls.add(colorControl(Configs.HIGHLIGHT_COLOR_QUEUED));
                controls.add(colorControl(Configs.HIGHLIGHT_COLOR_MISSING_MATERIAL));
            }
            case HUD -> {
                controls.add(booleanControl(Configs.ENABLE_TOOL_HUD));
                controls.add(optionControl(Configs.TOOL_HUD_STYLE));
                controls.add(doubleStepperControl(Configs.TOOL_HUD_OPACITY, 0.05D));
                controls.add(doubleStepperControl(Configs.TOOL_HUD_SMOOTHING, 0.05D));
                controls.add(integerStepperControl(Configs.TOOL_HUD_OFFSET, 4));
                controls.add(integerStepperControl(Configs.TOOL_HUD_SCALE, 5));
                controls.add(integerStepperControl(Configs.TOOL_HUD_CUSTOM_X, 8));
                controls.add(integerStepperControl(Configs.TOOL_HUD_CUSTOM_Y, 8));
                controls.add(resetHudPositionControl());
                controls.add(integerStepperControl(Configs.TOOL_HUD_FRAME_RATE, 5));
            }
        }
        return controls;
    }

    private ControlSpec booleanControl(ConfigBoolean config) {
        return new ControlSpec(
                rect -> addBooleanButton(rect.x, rect.y, rect.width, config),
                () -> booleanLabel(config),
                config::getComment,
                false
        );
    }

    private ControlSpec optionControl(ConfigOptionList config) {
        return new ControlSpec(
                rect -> addOptionButton(rect.x, rect.y, rect.width, config),
                () -> optionLabel(config),
                config::getComment,
                false
        );
    }

    private ControlSpec integerStepperControl(ConfigInteger config, int step) {
        return new ControlSpec(
                rect -> addStepper(rect.x, rect.y, rect.width, config, step),
                () -> integerLabel(config),
                config::getComment,
                true
        );
    }

    private ControlSpec doubleStepperControl(ConfigDouble config, double step) {
        return new ControlSpec(
                rect -> addStepper(rect.x, rect.y, rect.width, config, step),
                () -> doubleLabel(config),
                config::getComment,
                true
        );
    }

    private ControlSpec colorControl(ConfigColor config) {
        return new ControlSpec(
                rect -> addColorCycle(rect.x, rect.y, rect.width, config),
                () -> colorLabel(config),
                config::getComment,
                false
        );
    }

    private ControlSpec resetHudPositionControl() {
        return new ControlSpec(rect -> {
            ButtonGeneric button = new ButtonGeneric(rect.x, rect.y, rect.width, 20, tr("litematica_container_filler.gui.button.reset_hud_position"));
            button.setHoverStrings(tr("litematica_container_filler.gui.label.hud_position_hint"));
            this.addButton(button, (clickedButton, mouseButton) -> resetHudPosition());
        }, () -> tr("litematica_container_filler.gui.button.reset_hud_position"), () -> "litematica_container_filler.gui.label.hud_position_hint", false);
    }

    private int addBooleanButton(int x, int y, int width, ConfigBoolean config) {
        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, booleanLabel(config));
        button.setHoverStrings(tr(config.getComment()));
        this.addButton(button, (clickedButton, mouseButton) -> {
            config.setBooleanValue(!config.getBooleanValue());
            Configs.saveToFile();
            this.initGui();
        });
        return y + 24;
    }

    private int addOptionButton(int x, int y, int width, ConfigOptionList config) {
        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, optionLabel(config));
        button.setHoverStrings(tr(config.getComment()));
        this.addButton(button, (clickedButton, mouseButton) -> {
            config.setOptionListValue(config.getOptionListValue().cycle(mouseButton == 0));
            Configs.saveToFile();
            this.initGui();
        });
        return y + 24;
    }

    private int addStepper(int x, int y, int width, ConfigInteger config, int step) {
        int resetW = Math.min(54, Math.max(44, width / 4));
        int valueW = Math.max(70, width - resetW - 86);
        this.addButton(new ButtonGeneric(x, y, 24, 20, "-"), (button, mouseButton) -> {
            config.setIntegerValue(config.getIntegerValue() - step);
            Configs.saveToFile();
            this.initGui();
        });
        ButtonGeneric value = new ButtonGeneric(x + 28, y, valueW, 20, integerLabel(config));
        value.setHoverStrings(tr(config.getComment()));
        this.addButton(value, (button, mouseButton) -> {});
        this.addButton(new ButtonGeneric(x + 32 + valueW, y, 24, 20, "+"), (button, mouseButton) -> {
            config.setIntegerValue(config.getIntegerValue() + step);
            Configs.saveToFile();
            this.initGui();
        });
        this.addButton(new ButtonGeneric(x + width - resetW, y, resetW, 20, tr("litematica_container_filler.gui.button.reset")), (button, mouseButton) -> {
            config.resetToDefault();
            Configs.saveToFile();
            this.initGui();
        });
        return y + 24;
    }

    private int addStepper(int x, int y, int width, ConfigDouble config, double step) {
        int resetW = Math.min(54, Math.max(44, width / 4));
        int valueW = Math.max(70, width - resetW - 86);
        this.addButton(new ButtonGeneric(x, y, 24, 20, "-"), (button, mouseButton) -> {
            config.setDoubleValue(config.getDoubleValue() - step);
            Configs.saveToFile();
            this.initGui();
        });
        ButtonGeneric value = new ButtonGeneric(x + 28, y, valueW, 20, doubleLabel(config));
        value.setHoverStrings(tr(config.getComment()));
        this.addButton(value, (button, mouseButton) -> {});
        this.addButton(new ButtonGeneric(x + 32 + valueW, y, 24, 20, "+"), (button, mouseButton) -> {
            config.setDoubleValue(config.getDoubleValue() + step);
            Configs.saveToFile();
            this.initGui();
        });
        this.addButton(new ButtonGeneric(x + width - resetW, y, resetW, 20, tr("litematica_container_filler.gui.button.reset")), (button, mouseButton) -> {
            config.resetToDefault();
            Configs.saveToFile();
            this.initGui();
        });
        return y + 24;
    }

    private int addColorCycle(int x, int y, int width, ConfigColor config) {
        this.addButton(new ButtonGeneric(x, y, width, 20, colorLabel(config)), (button, mouseButton) -> {
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
    public boolean onMouseClicked(Click click, boolean doubleClick) {
        double mouseX = click.x();
        double mouseY = click.y();
        Rect preview = getPreviewBounds();
        if (click.button() == 0 && isPointInside(mouseX, mouseY, preview.x, preview.y, preview.width, preview.height)) {
            if (this.tab == EditorTab.HUD) {
                Rect card = getHudPreviewCardRect(preview.x, preview.y, preview.width, preview.height);
                if (isPointInside(mouseX, mouseY, card.x, card.y, card.width, card.height)) {
                    this.draggingHudPosition = true;
                    this.lastDragX = (int)mouseX;
                    this.lastDragY = (int)mouseY;
                    return true;
                }
            }
            this.draggingPreview = true;
            this.lastDragX = (int)mouseX;
            this.lastDragY = (int)mouseY;
            return true;
        }
        return super.onMouseClicked(click, doubleClick);
    }

    @Override
    public boolean onMouseReleased(Click click) {
        if (this.draggingPreview || this.draggingHudPosition) {
            this.draggingPreview = false;
            this.draggingHudPosition = false;
            Configs.saveToFile();
            return true;
        }
        return super.onMouseReleased(click);
    }

    @Override
    protected void drawContents(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        updateDrag(mouseX, mouseY);
        drawString(drawContext, this.title, 18, 18, TEXT);
        drawString(drawContext, tr("litematica_container_filler.gui.label.render_editor"), 168, 19, MUTED);

        EditorLayout layout = getEditorLayout();

        drawCard(drawContext, layout.previewPanel.x, layout.previewPanel.y, layout.previewPanel.width, layout.previewPanel.height);
        drawCard(drawContext, layout.controlsPanel.x, layout.controlsPanel.y, layout.controlsPanel.width, layout.controlsPanel.height);
        drawString(drawContext, tr("litematica_container_filler.gui.label.render_editor_preview"), layout.previewPanel.x + 12, layout.previewPanel.y + 10, TEXT);
        drawString(drawContext, tr("litematica_container_filler.gui.label.render_editor_settings"), layout.controlsPanel.x + 12, layout.controlsPanel.y + 10, TEXT);
        drawTabDescription(drawContext, layout.controlsPanel.x + 12, layout.controlsPanel.y + 28, layout.controlsPanel.width - 24);
        drawPreview(drawContext, layout.preview.x, layout.preview.y, layout.preview.width, layout.preview.height, partialTicks);
        super.drawContents(drawContext, mouseX, mouseY, partialTicks);
        drawTabControlsOverlay(drawContext, mouseX, mouseY);
    }

    private void drawTabDescription(DrawContext context, int x, int y, int width) {
        String key = "litematica_container_filler.gui.label.render_editor." + this.tab.key + ".desc";
        for (String line : wrap(tr(key), Math.max(18, width / 6))) {
            drawString(context, line, x, y, MUTED);
            y += 11;
        }
    }

    private void updateDrag(int mouseX, int mouseY) {
        if (!this.draggingPreview && !this.draggingHudPosition) return;
        int deltaX = mouseX - this.lastDragX;
        int deltaY = mouseY - this.lastDragY;
        if (this.draggingHudPosition) {
            Configs.TOOL_HUD_CUSTOM_X.setIntegerValue(Configs.TOOL_HUD_CUSTOM_X.getIntegerValue() + deltaX);
            Configs.TOOL_HUD_CUSTOM_Y.setIntegerValue(Configs.TOOL_HUD_CUSTOM_Y.getIntegerValue() + deltaY);
        } else {
            this.previewYaw += deltaX * 0.55f;
            this.previewPitch = clampFloat(this.previewPitch + deltaY * 0.35f, -45.0f, 45.0f);
        }
        this.lastDragX = mouseX;
        this.lastDragY = mouseY;
    }

    private void drawTabControlsOverlay(DrawContext context, int mouseX, int mouseY) {
        List<ControlSpec> controls = getControlsForTab();
        ControlLayout layout = getControlLayout(controls.size());
        for (int i = 0; i < controls.size(); i++) {
            ControlRect rect = layout.rectFor(i);
            ControlSpec control = controls.get(i);
            if (control.stepper) {
                drawStepperOverlay(context, mouseX, mouseY, rect.x, rect.y, rect.width, control.label.get());
            } else {
                drawButtonOverlay(context, mouseX, mouseY, rect.x, rect.y, rect.width, control.label.get());
            }
        }
    }

    private int drawBooleanOverlay(DrawContext context, int mouseX, int mouseY, int x, int y, int width, ConfigBoolean config) {
        return drawButtonOverlay(context, mouseX, mouseY, x, y, width, booleanLabel(config));
    }

    private int drawButtonOverlay(DrawContext context, int mouseX, int mouseY, int x, int y, int width, String label) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 20;
        drawSoftRect(context, x, y, width, 20, hovered ? 0xFF2B4B54 : 0xFF1B2A31);
        context.fill(x, y, x + width, y + 1, hovered ? ACCENT : BORDER);
        drawString(context, fit(label, Math.max(12, width / 6)), x + 8, y + 6, hovered ? TEXT : MUTED);
        return y + 24;
    }

    private int drawStepperOverlay(DrawContext context, int mouseX, int mouseY, int x, int y, int width, String label) {
        int resetW = Math.min(54, Math.max(44, width / 4));
        int valueW = Math.max(70, width - resetW - 86);
        drawButtonOverlay(context, mouseX, mouseY, x, y, 24, "-");
        drawButtonOverlay(context, mouseX, mouseY, x + 28, y, valueW, label);
        drawButtonOverlay(context, mouseX, mouseY, x + 32 + valueW, y, 24, "+");
        drawButtonOverlay(context, mouseX, mouseY, x + width - resetW, y, resetW, tr("litematica_container_filler.gui.button.reset"));
        return y + 24;
    }

    private EditorLayout getEditorLayout() {
        int screenW = this.getScreenWidth();
        int screenH = this.getScreenHeight();
        int top = 82;
        int height = Math.max(132, screenH - top - 42);
        int gap = 12;
        int sideMargin = 18;
        boolean narrow = screenW < 760;

        if (narrow) {
            int panelW = Math.max(260, screenW - sideMargin * 2);
            int previewH = Math.max(130, (height - gap) / 2);
            int controlsH = Math.max(110, height - previewH - gap);
            Rect previewPanel = new Rect(sideMargin, top, panelW, previewH);
            Rect controlsPanel = new Rect(sideMargin, top + previewH + gap, panelW, controlsH);
            return new EditorLayout(
                    previewPanel,
                    controlsPanel,
                    new Rect(previewPanel.x + 14, previewPanel.y + 34, previewPanel.width - 28, previewPanel.height - 48)
            );
        }

        int availableW = screenW - sideMargin * 2 - gap;
        int previewW = clamp((int)(availableW * 0.48D), 250, Math.max(250, availableW - 280));
        int controlsW = availableW - previewW - gap;
        if (controlsW < 280) {
            controlsW = 280;
            previewW = availableW - controlsW - gap;
        }

        Rect previewPanel = new Rect(sideMargin + 4, top, previewW, height);
        Rect controlsPanel = new Rect(previewPanel.x + previewW + gap, top, controlsW, height);
        return new EditorLayout(
                previewPanel,
                controlsPanel,
                new Rect(previewPanel.x + 14, previewPanel.y + 34, previewPanel.width - 28, previewPanel.height - 48)
        );
    }

    private ControlLayout getControlLayout(int controlCount) {
        EditorLayout editor = getEditorLayout();
        int startX = editor.controlsPanel.x + 12;
        int startY = editor.controlsPanel.y + 54;
        int availableW = Math.max(120, editor.controlsPanel.width - 24);
        int availableH = Math.max(24, editor.controlsPanel.y + editor.controlsPanel.height - 34 - startY);
        int rowH = 24;
        int rows = Math.max(1, availableH / rowH);
        int columns = Math.max(1, (int)Math.ceil(controlCount / (double)rows));
        columns = Math.min(columns, Math.max(1, availableW / 132));
        rows = Math.max(1, (int)Math.ceil(controlCount / (double)columns));
        int gap = 8;
        int width = Math.max(112, (availableW - gap * (columns - 1)) / columns);
        if (rows * rowH > availableH && columns < Math.max(1, availableW / 112)) {
            columns++;
            rows = Math.max(1, (int)Math.ceil(controlCount / (double)columns));
            width = Math.max(104, (availableW - gap * (columns - 1)) / columns);
        }
        return new ControlLayout(startX, startY, width, rowH, rows, gap);
    }

    private void resetHudPosition() {
        Configs.TOOL_HUD_CUSTOM_X.resetToDefault();
        Configs.TOOL_HUD_CUSTOM_Y.resetToDefault();
        Configs.saveToFile();
        this.initGui();
    }

    private void drawPreview(DrawContext context, int x, int y, int width, int height, float partialTicks) {
        drawGradient(context, x, y, x + width, y + height, 0xFF101922, 0xFF172A2C);
        drawGrid(context, x, y, width, height);
        String hint = this.tab == EditorTab.HUD
                ? tr("litematica_container_filler.gui.label.hud_position_hint")
                : tr("litematica_container_filler.gui.label.render_editor_drag");
        drawString(context, hint, x + 8, y + 8, 0x779FB1B8);
        switch (this.tab) {
            case HIGHLIGHTS -> drawHighlightPreview(context, x, y, width, height);
            case MARKERS -> drawMarkerPreview(context, x, y, width, height);
            case HUD -> drawHudPreview(context, x, y, width, height);
        }
    }

    private void drawHighlightPreview(DrawContext context, int x, int y, int width, int height) {
        List<StatePreview> states = List.of(
                new StatePreview("unfilled", Configs.HIGHLIGHT_COLOR_UNFILLED),
                new StatePreview("partial", Configs.HIGHLIGHT_COLOR_PARTIAL),
                new StatePreview("overfilled", Configs.HIGHLIGHT_COLOR_OVERFILLED),
                new StatePreview("wrong", Configs.HIGHLIGHT_COLOR_WRONG),
                new StatePreview("satisfied", Configs.HIGHLIGHT_COLOR_SATISFIED),
                new StatePreview("unknown", Configs.HIGHLIGHT_COLOR_UNKNOWN),
                new StatePreview("unplaced", Configs.HIGHLIGHT_COLOR_UNPLACED)
        );

        int columns = width < 360 ? 2 : Math.min(4, Math.max(2, width / 130));
        int rows = (int)Math.ceil(states.size() / (double)columns);
        int cellW = Math.max(72, width / columns);
        int cellH = Math.max(72, (height - 34) / Math.max(1, rows));
        int originY = y + 30;
        for (int i = 0; i < states.size(); i++) {
            StatePreview state = states.get(i);
            int col = i % columns;
            int row = i / columns;
            int cx = x + col * cellW + cellW / 2;
            int cy = originY + row * cellH + Math.max(30, cellH / 2);
            float size = Math.min(cellW, cellH) * 0.30f;
            drawHighlightModel(context, cx, cy, size, state.color.getColor(), Configs.RENDER_STATE_GLASS.getBooleanValue(), Configs.RENDER_STATE_TOP_PLATE.getBooleanValue());
            drawString(context, tr("litematica_container_filler.gui.label.render_state." + state.key), cx - Math.min(42, cellW / 3), cy + Math.round(size * 0.86f) + 12, MUTED);
        }
    }

    private void drawMarkerPreview(DrawContext context, int x, int y, int width, int height) {
        double time = quantizedTime(Configs.TASK_MARKER_ANIMATION_FPS.getIntegerValue());
        List<MarkerPreview> markers = new ArrayList<>();
        if (Configs.RENDER_FILLING_ARROW.getBooleanValue()) markers.add(new MarkerPreview("marker_filling", MarkerKind.FILLING));
        if (Configs.RENDER_QUEUED_SPINNER.getBooleanValue()) markers.add(new MarkerPreview("marker_queued", MarkerKind.QUEUED));
        if (Configs.RENDER_MISSING_MATERIAL_MARKER.getBooleanValue()) markers.add(new MarkerPreview("marker_missing", MarkerKind.MISSING));
        if (markers.isEmpty()) {
            drawString(context, tr("litematica_container_filler.gui.value.off"), x + width / 2 - 24, y + height / 2, MUTED);
            return;
        }

        int columns = Math.min(markers.size(), width < 330 ? 1 : 3);
        int rows = (int)Math.ceil(markers.size() / (double)columns);
        int cellW = width / columns;
        int cellH = Math.max(88, (height - 26) / Math.max(1, rows));
        int originY = y + 34;
        for (int i = 0; i < markers.size(); i++) {
            MarkerPreview marker = markers.get(i);
            int col = i % columns;
            int row = i / columns;
            int cx = x + col * cellW + cellW / 2;
            int cy = originY + row * cellH + cellH / 2;
            float size = Math.min(cellW, cellH) * 0.42f * (float)Configs.TASK_OVERLAY_SCALE.getDoubleValue();
            drawMarkerModel(context, cx, cy, Math.max(18.0f, size), time, marker.kind);
            drawString(context, tr("litematica_container_filler.gui.label." + marker.key), cx - Math.min(42, cellW / 3), cy + Math.min(48, cellH / 3), marker.kind == MarkerKind.MISSING ? WARNING : TEXT);
        }
    }

    private void drawHudPreview(DrawContext context, int x, int y, int width, int height) {
        int crossX = x + width / 2;
        int crossY = y + height / 2;
        drawCrosshairReference(context, crossX, crossY);

        double opacity = Configs.TOOL_HUD_OPACITY.getDoubleValue();
        int alpha = clamp((int)(255.0D * opacity), 25, 255);
        Rect card = getHudPreviewCardRect(x, y, width, height);
        ToolHudStyle style = Configs.TOOL_HUD_STYLE.getOptionListValue() instanceof ToolHudStyle hudStyle ? hudStyle : ToolHudStyle.FIXED_CARD;

        if (style == ToolHudStyle.ANCHORED_CARD) {
            int anchorX = clamp(crossX - 42, x + 14, x + width - 14);
            int anchorY = clamp(crossY + 24, y + 22, y + height - 22);
            int edgeX = card.x + (card.x > anchorX ? 0 : card.width);
            int edgeY = card.y + card.height / 2;
            int cornerX = (anchorX + edgeX) / 2;

            drawLine(context, anchorX, anchorY, cornerX, anchorY, withAlpha(ACCENT, alpha));
            drawLine(context, cornerX, anchorY, edgeX, edgeY, withAlpha(ACCENT, alpha));
            context.fill(anchorX - 3, anchorY - 3, anchorX + 4, anchorY + 4, withAlpha(ACCENT, alpha));
        }

        drawHudCard(context, card.x, card.y, card.width, card.height, alpha);
    }

    private Rect getHudPreviewCardRect(int x, int y, int width, int height) {
        float scale = Configs.TOOL_HUD_SCALE.getIntegerValue() / 100.0f;
        int panelW = Math.round(184 * scale);
        int panelH = Math.round(68 * scale);
        int panelX = x + width / 2 + Configs.TOOL_HUD_CUSTOM_X.getIntegerValue();
        int panelY = y + height / 2 + Configs.TOOL_HUD_CUSTOM_Y.getIntegerValue();
        panelX = clamp(panelX, x + 8, Math.max(x + 8, x + width - panelW - 8));
        panelY = clamp(panelY, y + 22, Math.max(y + 22, y + height - panelH - 8));
        return new Rect(panelX, panelY, panelW, panelH);
    }

    private void drawHudCard(DrawContext context, int panelX, int panelY, int panelW, int panelH, int alpha) {
        drawSoftRect(context, panelX + 2, panelY + 3, panelW, panelH, withAlpha(0xFF000000, (int)(alpha * 0.32D)));
        drawSoftRect(context, panelX, panelY, panelW, panelH, withAlpha(0xFFE6F2E8, (int)(alpha * 0.88D)));
        drawSoftRect(context, panelX + 2, panelY + 2, panelW - 4, panelH - 4, withAlpha(0xFF050708, (int)(alpha * 0.92D)));
        float scale = Configs.TOOL_HUD_SCALE.getIntegerValue() / 100.0f;
        int headerH = Math.max(15, Math.min(20, panelH / 4));
        context.fill(panelX + 6, panelY + 6, panelX + panelW - 6, panelY + headerH + 5, withAlpha(0xFF2A332B, (int)(alpha * 0.46D)));
        drawScaledString(context, tr("litematica_container_filler.gui.label.hud_title"), panelX + 10, panelY + 8, scale, withAlpha(TEXT, alpha));
        double time = quantizedTime(Configs.TOOL_HUD_FRAME_RATE.getIntegerValue());
        ContainerToolMode mode = Configs.CONTAINER_TOOL_MODE.getOptionListValue() instanceof ContainerToolMode toolMode ? toolMode : ContainerToolMode.CLEAR;
        int pulse = (int)(Math.sin(time * 5.0D) * 2.0D);
        int iconX = panelX + Math.round(24 * scale) + (mode == ContainerToolMode.COPY ? pulse : 0);
        int iconY = panelY + headerH + Math.round(29 * scale) + (mode == ContainerToolMode.COPY ? 0 : pulse);
        drawMiniHudToolIcon(context, iconX, iconY, alpha, scale, mode);
        int textX = panelX + Math.round(48 * scale);
        int labelY = panelY + headerH + 8;
        drawScaledString(context, fit(mode.getDisplayName(), Math.max(8, panelW / 8)), textX, labelY, scale, withAlpha(MUTED, alpha));
        drawScaledString(context, fit(tr("litematica_container_filler.hud.tool_action", "V"), Math.max(8, panelW / 8)), textX, labelY + Math.round(12.0f * scale), scale, withAlpha(0xFF55FF68, alpha));
        drawScaledString(context, fit(tr("litematica_container_filler.hud.tool_switch_close", "G", "H"), Math.max(8, panelW / 7)), textX, labelY + Math.round(24.0f * scale), scale, withAlpha(MUTED, (int)(alpha * 0.78D)));
    }

    private void drawMiniHudToolIcon(DrawContext context, int cx, int cy, int alpha, float scale, ContainerToolMode mode) {
        ArrowDirection direction = switch (mode) {
            case CLEAR -> ArrowDirection.UP;
            case COPY -> ArrowDirection.RIGHT;
            case PACK, FILL_FULL -> ArrowDirection.DOWN;
        };

        if (mode == ContainerToolMode.PACK) {
            int boxW = Math.max(18, Math.round(24.0f * scale));
            int boxH = Math.max(14, Math.round(17.0f * scale));
            int boxX = cx - boxW / 2;
            int boxY = cy + Math.round(1.0f * scale);
            int box = withAlpha(0xFF8E55D9, Math.round(alpha * 0.58f));
            int edge = withAlpha(0xFFB68CFF, Math.round(alpha * 0.9f));
            context.fill(boxX + 1, boxY + 2, boxX + boxW - 1, boxY + boxH, box);
            context.fill(boxX, boxY, boxX + boxW, boxY + Math.max(4, Math.round(4.0f * scale)), edge);
            context.fill(boxX, boxY + 3, boxX + 2, boxY + boxH, edge);
            context.fill(boxX + boxW - 2, boxY + 3, boxX + boxW, boxY + boxH, edge);
            drawMiniHudArrow(context, cx, cy - Math.round(13.0f * scale), alpha, scale * 0.52f, direction);
            return;
        }

        drawMiniHudArrow(context, cx, cy, alpha, scale, direction);
    }

    private void drawMiniHudArrow(DrawContext context, int cx, int cy, int alpha, float scale, ArrowDirection direction) {
        Color4f color = Configs.HIGHLIGHT_COLOR_FILLING.getColor();
        int c = color.toVanillaArgb();
        int body = withAlpha(c, Math.round(alpha * 0.88f));
        int shaft = Math.max(3, Math.round(4.0f * scale));
        int halfW = Math.max(8, Math.round(11.0f * scale));
        fillRotatedRect(context, cx, cy, -shaft, Math.round(-14.0f * scale), shaft + 1, Math.round(1.0f * scale), body, direction);
        fillRotatedRect(context, cx, cy, -halfW, Math.round(-1.0f * scale), halfW + 1, Math.round(5.0f * scale), body, direction);
        fillRotatedRect(context, cx, cy, -Math.round(halfW * 0.68f), Math.round(5.0f * scale), Math.round(halfW * 0.68f) + 1, Math.round(10.0f * scale), body, direction);
        fillRotatedRect(context, cx, cy, -Math.round(halfW * 0.36f), Math.round(10.0f * scale), Math.round(halfW * 0.36f) + 1, Math.round(14.0f * scale), body, direction);
        fillRotatedRect(context, cx, cy, -1, Math.round(-12.0f * scale), 2, Math.round(13.0f * scale), withAlpha(0xFFFFFFFF, Math.round(alpha * 0.28f)), direction);
    }

    private void drawHighlightModel(DrawContext context, int cx, int cy, float size, Color4f color, boolean glass, boolean topPlate) {
        if (glass) {
            drawPreviewCuboid(context, cx, cy, size, color);
        } else {
            drawPreviewCuboid(context, cx, cy, size, new Color4f(color.r, color.g, color.b, Math.min(0.16f, color.a)));
        }

        if (topPlate) {
            Color4f plate = new Color4f(color.r, color.g, color.b, Math.min(0.72f, Math.max(0.20f, color.a * 0.9f)));
            float plateWidth = size * (float)Configs.HIGHLIGHT_TOP_PLATE_SIZE.getDoubleValue();
            drawPreviewBox(context, cx, cy - Math.round(size * 0.78f), plateWidth, Math.max(3.0f, size * 0.08f), size * 0.34f, plate);
        }
    }

    private void drawMarkerModel(DrawContext context, int cx, int cy, float size, double time, MarkerKind kind) {
        switch (kind) {
            case FILLING -> drawPreviewDownArrow(context, cx, cy + (int)(Math.sin(time * 5.0D) * 3.0D), size * 0.44f, size * 0.84f, size * 0.12f, Configs.HIGHLIGHT_COLOR_FILLING.getColor());
            case QUEUED -> drawPreviewSpinner(context, cx, cy, size, time, Configs.HIGHLIGHT_COLOR_QUEUED.getColor());
            case MISSING -> drawPreviewExclamation(context, cx, cy, size, Configs.HIGHLIGHT_COLOR_MISSING_MATERIAL.getColor());
        }
    }

    private void drawPreviewDownArrow(DrawContext context, int cx, int cy, float halfWidth, float height, float depth, Color4f color) {
        drawPreviewBox(context, cx, cy - height * 0.18f, halfWidth * 0.28f, height * 0.28f, depth, color);
        drawPreviewBox(context, cx, cy + height * 0.11f, halfWidth, height * 0.09f, depth, color);
        drawPreviewBox(context, cx, cy + height * 0.23f, halfWidth * 0.72f, height * 0.08f, depth, color);
        drawPreviewBox(context, cx, cy + height * 0.34f, halfWidth * 0.44f, height * 0.07f, depth, color);
        drawPreviewBox(context, cx, cy + height * 0.43f, halfWidth * 0.18f, height * 0.06f, depth, color);
    }

    private void drawPreviewSpinner(DrawContext context, int cx, int cy, float size, double time, Color4f color) {
        for (int i = 0; i < 8; i++) {
            double angle = time * 3.2D + i * Math.PI / 4.0D;
            float px = cx + (float)Math.cos(angle) * size * 0.42f;
            float py = cy + (float)Math.sin(angle) * size * 0.26f;
            Color4f dot = new Color4f(color.r, color.g, color.b, Math.min(0.72f, color.a * (0.18f + i * 0.055f)));
            drawPreviewBox(context, px, py, size * 0.055f, size * 0.055f, size * 0.055f, dot);
        }
        drawPreviewBox(context, cx, cy, size * 0.12f, size * 0.040f, size * 0.12f, new Color4f(color.r, color.g, color.b, Math.min(0.18f, color.a)));
    }

    private void drawPreviewExclamation(DrawContext context, int cx, int cy, float size, Color4f color) {
        drawPreviewBox(context, cx, cy - size * 0.20f, size * 0.070f, size * 0.30f, size * 0.070f, color);
        drawPreviewBox(context, cx, cy + size * 0.34f, size * 0.085f, size * 0.070f, size * 0.085f, color);
    }

    private void drawCrosshairReference(DrawContext context, int cx, int cy) {
        int c = withAlpha(0xFFFFFFFF, 118);
        context.fill(cx - 12, cy, cx - 3, cy + 1, c);
        context.fill(cx + 4, cy, cx + 13, cy + 1, c);
        context.fill(cx, cy - 12, cx + 1, cy - 3, c);
        context.fill(cx, cy + 4, cx + 1, cy + 13, c);
        context.fill(cx - 1, cy - 1, cx + 2, cy + 2, withAlpha(ACCENT, 150));
    }

    private void fillRotatedRect(DrawContext context, int cx, int cy, int relX1, int relY1, int relX2, int relY2, int color, ArrowDirection direction) {
        int[] a = rotate(cx, cy, relX1, relY1, direction);
        int[] b = rotate(cx, cy, relX2, relY2, direction);
        context.fill(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1]), color);
    }

    private int[] rotate(int cx, int cy, int x, int y, ArrowDirection direction) {
        return switch (direction) {
            case DOWN -> new int[]{cx + x, cy + y};
            case UP -> new int[]{cx - x, cy - y};
            case RIGHT -> new int[]{cx + y, cy - x};
        };
    }

    private enum ArrowDirection {
        DOWN,
        UP,
        RIGHT
    }

    private void drawPreviewCuboid(DrawContext context, int cx, int cy, float size, Color4f color) {
        drawPreviewBox(context, cx, cy, size, size, size, color);
    }

    private void drawPreviewBox(DrawContext context, float cx, float cy, float halfX, float halfY, float halfZ, Color4f color) {
        double yaw = Math.toRadians(this.previewYaw);
        double pitch = Math.toRadians(this.previewPitch);
        double cosY = Math.cos(yaw);
        double sinY = Math.sin(yaw);
        double cosP = Math.cos(pitch);
        double sinP = Math.sin(pitch);
        double[][] vertices = {
                {-1, -1, -1}, {1, -1, -1}, {1, 1, -1}, {-1, 1, -1},
                {-1, -1, 1}, {1, -1, 1}, {1, 1, 1}, {-1, 1, 1}
        };
        int[] sx = new int[8];
        int[] sy = new int[8];
        double[] sz = new double[8];

        for (int i = 0; i < vertices.length; i++) {
            double x = vertices[i][0] * halfX;
            double y = vertices[i][1] * halfY;
            double z = vertices[i][2] * halfZ;
            double rx = x * cosY - z * sinY;
            double rz = x * sinY + z * cosY;
            double ry = y * cosP - rz * sinP;
            rz = y * sinP + rz * cosP;
            sx[i] = Math.round(cx + (float)(rx * 0.82D));
            sy[i] = Math.round(cy - (float)(ry * 0.62D));
            sz[i] = rz;
        }

        int[][] faces = {
                {0, 1, 2, 3}, {4, 7, 6, 5}, {0, 4, 5, 1},
                {3, 2, 6, 7}, {1, 5, 6, 2}, {0, 3, 7, 4}
        };
        java.util.Arrays.sort(faces, java.util.Comparator.comparingDouble(face -> {
            double total = 0.0D;
            for (int index : face) total += sz[index];
            return total / face.length;
        }));

        int argb = color.toVanillaArgb();
        for (int[] face : faces) {
            int alpha = clamp((int)(color.a * 155.0f), 38, 170);
            fillQuad(context, sx, sy, face, withAlpha(argb, alpha));
        }

        int edge = withAlpha(argb, clamp((int)(color.a * 255.0f), 110, 255));
        int[][] edges = {
                {0, 1}, {1, 2}, {2, 3}, {3, 0},
                {4, 5}, {5, 6}, {6, 7}, {7, 4},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };
        for (int[] line : edges) {
            drawLine(context, sx[line[0]], sy[line[0]], sx[line[1]], sy[line[1]], edge);
        }
    }

    private void fillQuad(DrawContext context, int[] xs, int[] ys, int[] face, int color) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int index : face) {
            minY = Math.min(minY, ys[index]);
            maxY = Math.max(maxY, ys[index]);
        }

        for (int y = minY; y <= maxY; y++) {
            java.util.ArrayList<Integer> intersections = new java.util.ArrayList<>();
            for (int i = 0; i < face.length; i++) {
                int a = face[i];
                int b = face[(i + 1) % face.length];
                int y1 = ys[a];
                int y2 = ys[b];
                if ((y1 <= y && y2 > y) || (y2 <= y && y1 > y)) {
                    double t = (y - y1) / (double)(y2 - y1);
                    intersections.add((int)Math.round(xs[a] + (xs[b] - xs[a]) * t));
                }
            }
            java.util.Collections.sort(intersections);
            for (int i = 0; i + 1 < intersections.size(); i += 2) {
                context.fill(intersections.get(i), y, intersections.get(i + 1) + 1, y + 1, color);
            }
        }
    }

    private Rect getPreviewBounds() {
        return getEditorLayout().preview;
    }

    private boolean isPointInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
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

    private void drawScaledString(DrawContext context, String text, int x, int y, float scale, int color) {
        if (Math.abs(scale - 1.0f) < 0.01f) {
            drawString(context, text, x, y, color);
            return;
        }

        Matrix3x2fStack matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(x, y);
        matrices.scale(scale, scale);
        drawString(context, text, 0, 0, color);
        matrices.popMatrix();
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

    private String fit(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        if (maxChars <= 1) return text.substring(0, 1);
        return text.substring(0, maxChars - 1) + "...";
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

    private static String tr(String key, Object... args) {
        return StringUtils.translate(key, args);
    }

    private static int withAlpha(int argb, int alpha) {
        return (clamp(alpha, 0, 255) << 24) | (argb & 0x00FFFFFF);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Rect(int x, int y, int width, int height) {
    }

    private record EditorLayout(Rect previewPanel, Rect controlsPanel, Rect preview) {
    }

    private record ControlRect(int x, int y, int width) {
    }

    private record ControlLayout(int x, int y, int width, int rowHeight, int rows, int gap) {
        ControlRect rectFor(int index) {
            int column = index / this.rows;
            int row = index % this.rows;
            return new ControlRect(this.x + column * (this.width + this.gap), this.y + row * this.rowHeight, this.width);
        }
    }

    private record ControlSpec(Consumer<ControlRect> addButtons, Supplier<String> label, Supplier<String> comment, boolean stepper) {
    }

    private record StatePreview(String key, ConfigColor color) {
    }

    private record MarkerPreview(String key, MarkerKind kind) {
    }

    private enum MarkerKind {
        FILLING,
        QUEUED,
        MISSING
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
