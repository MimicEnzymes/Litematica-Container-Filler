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
import fi.dy.masa.malilib.gui.GuiColorEditorHSV;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class GuiRenderEditor extends GuiBase {
    private static final int BACKGROUND_TOP = 0xFF0E1218;
    private static final int BACKGROUND_BOTTOM = 0xFF151B24;
    private static final int APP_BAR = 0xF2121720;
    private static final int SURFACE = 0xF21D242E;
    private static final int SURFACE_CONTAINER = 0xFF252D38;
    private static final int SURFACE_CONTAINER_HIGH = 0xFF303A46;
    private static final int OUTLINE = 0xFF3F4A58;
    private static final int PRIMARY = 0xFF8AB4F8;
    private static final int PRIMARY_CONTAINER = 0xFF244B78;
    private static final int TEXT = 0xFFE8EAED;
    private static final int MUTED = 0xFFBDC1C6;
    private static final int MUTED_SOFT = 0xFF8E98A4;
    private static final int WARNING = 0xFFFDD663;
    private static final int RENDER_WARNING_HEIGHT = 38;
    private static final float TOP_PLATE_MIN_INSET = 0.02f;
    private static final float TOP_PLATE_BOTTOM_OFFSET = 0.035f;
    private static final float TOP_PLATE_TOP_OFFSET = 0.095f;
    private static final float MANUAL_BADGE_GAP = 0.014f;
    private static final float MANUAL_BADGE_SIZE = 0.44f;
    private static final float MANUAL_BADGE_THICKNESS = 0.034f;

    private final Screen parent;
    private EditorTab tab = EditorTab.HIGHLIGHTS;
    private float previewYaw = -28.0f;
    private float previewPitch = 18.0f;
    private boolean draggingPreview = false;
    private boolean draggingHudPosition = false;
    private int lastDragX = 0;
    private int lastDragY = 0;
    private long cachedHighlightPreviewKey = Long.MIN_VALUE;
    private List<PreviewDrawCommand> cachedHighlightPreviewCommands = List.of();
    private final List<ColorInputBinding> colorInputs = new ArrayList<>();

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
        this.colorInputs.clear();

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
                controls.add(booleanControl(Configs.TOOL_HUD_BORDER));
                controls.add(optionControl(Configs.TOOL_HUD_STYLE));
                controls.add(doubleStepperControl(Configs.TOOL_HUD_OPACITY, 0.05D));
                controls.add(doubleStepperControl(Configs.TOOL_HUD_SMOOTHING, 0.05D));
                controls.add(integerStepperControl(Configs.TOOL_HUD_OFFSET, 4));
                controls.add(integerStepperControl(Configs.TOOL_HUD_SCALE, 5));
                controls.add(integerStepperControl(Configs.TOOL_HUD_ICON_SCALE, 5));
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
                ControlKind.BUTTON,
                null
        );
    }

    private ControlSpec optionControl(ConfigOptionList config) {
        return new ControlSpec(
                rect -> addOptionButton(rect.x, rect.y, rect.width, config),
                () -> optionLabel(config),
                config::getComment,
                ControlKind.BUTTON,
                null
        );
    }

    private ControlSpec integerStepperControl(ConfigInteger config, int step) {
        return new ControlSpec(
                rect -> addStepper(rect.x, rect.y, rect.width, config, step),
                () -> integerLabel(config),
                config::getComment,
                ControlKind.STEPPER,
                null
        );
    }

    private ControlSpec doubleStepperControl(ConfigDouble config, double step) {
        return new ControlSpec(
                rect -> addStepper(rect.x, rect.y, rect.width, config, step),
                () -> doubleLabel(config),
                config::getComment,
                ControlKind.STEPPER,
                null
        );
    }

    private ControlSpec colorControl(ConfigColor config) {
        return new ControlSpec(
                rect -> addColorControl(rect.x, rect.y, rect.width, config),
                () -> colorLabel(config),
                config::getComment,
                ControlKind.COLOR,
                config
        );
    }

    private ControlSpec resetHudPositionControl() {
        return new ControlSpec(rect -> {
            ButtonGeneric button = new ButtonGeneric(rect.x, rect.y, rect.width, 20, "");
            button.setHoverStrings(tr("litematica_container_filler.gui.label.hud_position_hint"));
            this.addButton(button, (clickedButton, mouseButton) -> resetHudPosition());
        }, () -> tr("litematica_container_filler.gui.button.reset_hud_position"), () -> "litematica_container_filler.gui.label.hud_position_hint", ControlKind.BUTTON, null);
    }

    private int addBooleanButton(int x, int y, int width, ConfigBoolean config) {
        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, "");
        button.setHoverStrings(tr(config.getComment()));
        this.addButton(button, (clickedButton, mouseButton) -> {
            config.setBooleanValue(!config.getBooleanValue());
            Configs.saveToFile();
            this.initGui();
        });
        return y + 24;
    }

    private int addOptionButton(int x, int y, int width, ConfigOptionList config) {
        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, "");
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
        this.addButton(new ButtonGeneric(x, y, 24, 20, ""), (button, mouseButton) -> {
            config.setIntegerValue(config.getIntegerValue() - step);
            Configs.saveToFile();
            this.initGui();
        });
        ButtonGeneric value = new ButtonGeneric(x + 28, y, valueW, 20, "");
        value.setHoverStrings(tr(config.getComment()));
        this.addButton(value, (button, mouseButton) -> {});
        this.addButton(new ButtonGeneric(x + 32 + valueW, y, 24, 20, ""), (button, mouseButton) -> {
            config.setIntegerValue(config.getIntegerValue() + step);
            Configs.saveToFile();
            this.initGui();
        });
        this.addButton(new ButtonGeneric(x + width - resetW, y, resetW, 20, ""), (button, mouseButton) -> {
            config.resetToDefault();
            Configs.saveToFile();
            this.initGui();
        });
        return y + 24;
    }

    private int addStepper(int x, int y, int width, ConfigDouble config, double step) {
        int resetW = Math.min(54, Math.max(44, width / 4));
        int valueW = Math.max(70, width - resetW - 86);
        this.addButton(new ButtonGeneric(x, y, 24, 20, ""), (button, mouseButton) -> {
            config.setDoubleValue(config.getDoubleValue() - step);
            Configs.saveToFile();
            this.initGui();
        });
        ButtonGeneric value = new ButtonGeneric(x + 28, y, valueW, 20, "");
        value.setHoverStrings(tr(config.getComment()));
        this.addButton(value, (button, mouseButton) -> {});
        this.addButton(new ButtonGeneric(x + 32 + valueW, y, 24, 20, ""), (button, mouseButton) -> {
            config.setDoubleValue(config.getDoubleValue() + step);
            Configs.saveToFile();
            this.initGui();
        });
        this.addButton(new ButtonGeneric(x + width - resetW, y, resetW, 20, ""), (button, mouseButton) -> {
            config.resetToDefault();
            Configs.saveToFile();
            this.initGui();
        });
        return y + 24;
    }

    private int addColorControl(int x, int y, int width, ConfigColor config) {
        ColorControlLayout layout = getColorControlLayout(x, y, width);
        this.addButton(new ButtonGeneric(layout.swatchX, y, layout.swatchW, 20, ""), (button, mouseButton) -> openColorEditor(config));
        GuiTextFieldGeneric field = new GuiTextFieldGeneric(layout.valueX + 3, y + 2, Math.max(24, layout.valueW - 6), 16, this.textRenderer);
        field.setTextWrapper(config.getStringValue());
        field.setMaxLengthWrapper(10);
        field.setDrawsBackground(false);
        field.setTextPredicate(GuiRenderEditor::isPotentialColorInput);
        field.setHoverTooltip(tr(config.getComment()));
        this.colorInputs.add(new ColorInputBinding(config, field));
        this.addButton(new ButtonGeneric(layout.resetX, y, layout.resetW, 20, ""), (button, mouseButton) -> {
            config.resetToDefault();
            Configs.saveToFile();
            this.initGui();
        });
        return y + 24;
    }

    private void openColorEditor(ConfigColor config) {
        GuiColorEditorHSV editor = new GuiColorEditorHSV(config, null, this) {
            @Override
            public void close() {
                super.close();
                Configs.saveToFile();
                GuiRenderEditor.this.initGui();
            }
        };
        GuiBase.openGui(editor);
    }

    @Override
    protected void drawScreenBackground(DrawContext drawContext, int mouseX, int mouseY) {
        drawGradient(drawContext, 0, 0, this.getScreenWidth(), this.getScreenHeight(), BACKGROUND_TOP, BACKGROUND_BOTTOM);
        drawContext.fill(0, 0, this.getScreenWidth(), 68, APP_BAR);
        drawContext.fill(0, 67, this.getScreenWidth(), 68, OUTLINE);
        drawContext.fill(0, 68, this.getScreenWidth(), 100, 0x26000000);
    }

    @Override
    protected void drawTitle(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
    }

    @Override
    public boolean onMouseClicked(Click click, boolean doubleClick) {
        double mouseX = click.x();
        double mouseY = click.y();
        if (handleColorInputClick(click, doubleClick)) {
            return true;
        }
        if (click.button() == 0 && handleChromeClick(mouseX, mouseY)) {
            return true;
        }

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
    public boolean onKeyTyped(KeyInput keyInput) {
        ColorInputBinding focused = getFocusedColorInput();
        if (focused != null) {
            int key = keyInput.key();
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                finishColorInput(focused, true);
                return true;
            }

            if (focused.field.keyPressedWrapper(keyInput)) {
                applyColorInput(focused);
                return true;
            }
        }
        return super.onKeyTyped(keyInput);
    }

    @Override
    public boolean onCharTyped(CharInput charInput) {
        ColorInputBinding focused = getFocusedColorInput();
        if (focused != null && focused.field.charTypedWrapper(charInput)) {
            applyColorInput(focused);
            return true;
        }
        return super.onCharTyped(charInput);
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
        drawMaterialTitle(drawContext);
        drawRenderWarningTopRight(drawContext);

        EditorLayout layout = getEditorLayout();

        drawCard(drawContext, layout.previewPanel.x, layout.previewPanel.y, layout.previewPanel.width, layout.previewPanel.height);
        drawCard(drawContext, layout.controlsPanel.x, layout.controlsPanel.y, layout.controlsPanel.width, layout.controlsPanel.height);
        drawSectionHeader(drawContext, tr("litematica_container_filler.gui.label.render_editor_preview"), layout.previewPanel.x + 16, layout.previewPanel.y + 12);
        drawSectionHeader(drawContext, tr("litematica_container_filler.gui.label.render_editor_settings"), layout.controlsPanel.x + 16, layout.controlsPanel.y + 12);
        drawTabDescription(drawContext, layout.controlsPanel.x + 16, layout.controlsPanel.y + 31, layout.controlsPanel.width - 32);
        drawPreview(drawContext, layout.preview.x, layout.preview.y, layout.preview.width, layout.preview.height, partialTicks);
        super.drawContents(drawContext, mouseX, mouseY, partialTicks);
        drawTabButtonOverlay(drawContext, mouseX, mouseY);
        drawBackButtonOverlay(drawContext, mouseX, mouseY);
        drawTabControlsOverlay(drawContext, mouseX, mouseY);
    }

    private void drawTabDescription(DrawContext context, int x, int y, int width) {
        String key = "litematica_container_filler.gui.label.render_editor." + this.tab.key + ".desc";
        for (String line : wrap(tr(key), Math.max(18, width / 6))) {
            drawString(context, line, x, y, MUTED);
            y += 11;
        }
    }

    private void drawRenderWarning(DrawContext context, int x, int y, int width) {
        if (width < 160 || y < 4) return;

        drawRoundedOutline(context, x, y, width, RENDER_WARNING_HEIGHT, 6, 0x88FDD663, 0xDD1D242E);
        drawString(context, tr("litematica_container_filler.gui.label.render_editor.warning.title"), x + 10, y + 7, WARNING);
        int textY = y + 19;
        int maxChars = Math.max(18, (width - 20) / 6);
        for (String line : wrap(tr("litematica_container_filler.gui.label.render_editor.warning.body"), maxChars)) {
            if (textY > y + 30) break;
            drawString(context, line, x + 10, textY, MUTED);
            textY += 10;
        }
    }

    private void drawRenderWarningTopRight(DrawContext context) {
        int screenW = this.getScreenWidth();
        int width = Math.min(680, screenW - 520);
        if (width < 260) return;

        int rightMargin = clamp(screenW / 80, 14, 24);
        drawRenderWarning(context, screenW - rightMargin - width, 12, width);
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

    private void drawMaterialTitle(DrawContext context) {
        drawString(context, this.title, 24, 17, TEXT);
        drawString(context, tr("litematica_container_filler.gui.label.render_editor"), 168, 18, MUTED);
    }

    private void drawSectionHeader(DrawContext context, String label, int x, int y) {
        drawString(context, label, x, y, TEXT);
        context.fill(x, y + 13, x + 28, y + 15, PRIMARY);
        context.fill(x + 29, y + 13, x + 44, y + 15, PRIMARY_CONTAINER);
    }

    private void drawTabButtonOverlay(DrawContext context, int mouseX, int mouseY) {
        int x = 18;
        int y = 46;
        for (EditorTab value : EditorTab.values()) {
            boolean selected = value == this.tab;
            boolean hovered = mouseX >= x && mouseX < x + 86 && mouseY >= y && mouseY < y + 20;
            if (hovered) drawSoftRect(context, x, y, 86, 20, 0x55303A46);
            int text = selected ? TEXT : hovered ? PRIMARY : MUTED;
            drawString(context, tr("litematica_container_filler.gui.button.render_editor." + value.key), x + 10, y + 6, text);
            if (selected) context.fill(x + 10, y + 18, x + 76, y + 20, PRIMARY);
            x += 90;
        }
    }

    private void drawBackButtonOverlay(DrawContext context, int mouseX, int mouseY) {
        int x = this.getScreenWidth() - 106;
        int y = this.getScreenHeight() - 30;
        boolean hovered = mouseX >= x && mouseX < x + 80 && mouseY >= y && mouseY < y + 20;
        drawMaterialButton(context, x, y, 80, 20, tr("litematica_container_filler.gui.button.back"), hovered, true);
    }

    private boolean handleChromeClick(double mouseX, double mouseY) {
        int x = 18;
        int y = 46;
        for (EditorTab value : EditorTab.values()) {
            if (isPointInside(mouseX, mouseY, x, y, 86, 20)) {
                if (value != this.tab) {
                    this.tab = value;
                    this.initGui();
                }
                return true;
            }
            x += 90;
        }

        int backX = this.getScreenWidth() - 106;
        int backY = this.getScreenHeight() - 30;
        if (isPointInside(mouseX, mouseY, backX, backY, 80, 20)) {
            GuiBase.openGui(parent);
            return true;
        }

        return false;
    }

    private boolean handleColorInputClick(Click click, boolean doubleClick) {
        boolean clickedInput = false;
        for (ColorInputBinding input : this.colorInputs) {
            boolean handled = input.field.mouseClickedWrapper(click, doubleClick);
            if (handled || input.field.isFocusedWrapper()) {
                clickedInput = true;
            } else {
                finishColorInput(input, false);
            }
        }
        return clickedInput;
    }

    private ColorInputBinding getFocusedColorInput() {
        for (ColorInputBinding input : this.colorInputs) {
            if (input.field.isFocusedWrapper()) {
                return input;
            }
        }
        return null;
    }

    private ColorInputBinding findColorInput(ConfigColor config) {
        for (ColorInputBinding input : this.colorInputs) {
            if (input.config == config) {
                return input;
            }
        }
        return null;
    }

    private void applyColorInput(ColorInputBinding input) {
        String value = normalizeColorInput(input.field.getTextWrapper());
        if (!isCompleteColorInput(value)) {
            return;
        }

        try {
            input.config.setValueFromString(value);
            Configs.saveToFile();
            this.cachedHighlightPreviewKey = Long.MIN_VALUE;
        } catch (RuntimeException ignored) {
        }
    }

    private void finishColorInput(ColorInputBinding input, boolean clearFocus) {
        applyColorInput(input);
        if (!isCompleteColorInput(normalizeColorInput(input.field.getTextWrapper()))) {
            input.field.setTextWrapper(input.config.getStringValue());
        }
        if (clearFocus) {
            input.field.setFocusedWrapper(false);
        }
    }

    private static boolean isPotentialColorInput(String value) {
        if (value == null || value.length() > 10) {
            return false;
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || "0".equals(normalized) || "0x".equalsIgnoreCase(normalized)) {
            return true;
        }
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            normalized = normalized.substring(2);
        }
        if (normalized.length() > 8) {
            return false;
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.digit(normalized.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeColorInput(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            normalized = normalized.substring(2);
        }
        return "0x" + normalized.toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean isCompleteColorInput(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            normalized = normalized.substring(2);
        }
        if (normalized.length() != 8) {
            return false;
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.digit(normalized.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private void drawTabControlsOverlay(DrawContext context, int mouseX, int mouseY) {
        List<ControlSpec> controls = getControlsForTab();
        ControlLayout layout = getControlLayout(controls.size());
        for (int i = 0; i < controls.size(); i++) {
            ControlRect rect = layout.rectFor(i);
            ControlSpec control = controls.get(i);
            if (control.kind == ControlKind.STEPPER) {
                drawStepperOverlay(context, mouseX, mouseY, rect.x, rect.y, rect.width, control.label.get());
            } else if (control.kind == ControlKind.COLOR) {
                drawColorOverlay(context, mouseX, mouseY, rect.x, rect.y, rect.width, control.label.get(), control.colorConfig);
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
        drawMaterialButton(context, x, y, width, 20, fit(label, Math.max(12, width / 6)), hovered, false);
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

    private int drawColorOverlay(DrawContext context, int mouseX, int mouseY, int x, int y, int width, String label, ConfigColor config) {
        ColorControlLayout layout = getColorControlLayout(x, y, width);
        int color = config != null ? config.getColor().toVanillaArgb() : 0xFFFFFFFF;
        drawMaterialButton(context, layout.swatchX, y, layout.swatchW, 20, "", isHover(mouseX, mouseY, layout.swatchX, y, layout.swatchW, 20), false);
        drawRoundedRect(context, layout.swatchX + 5, y + 4, layout.swatchW - 10, 12, 4, color);
        ColorInputBinding input = findColorInput(config);
        boolean focused = input != null && input.field.isFocusedWrapper();
        boolean hovered = isHover(mouseX, mouseY, layout.valueX, y, layout.valueW, 20);
        drawMaterialButton(context, layout.valueX, y, layout.valueW, 20, "", hovered || focused, false);
        if (input != null) {
            input.field.renderWrapper(context, mouseX, mouseY, 0.0f);
        } else {
            drawString(context, fit(label, Math.max(12, layout.valueW / 6)), layout.valueX + 8, y + 6, TEXT);
        }
        drawMaterialButton(context, layout.resetX, y, layout.resetW, 20, tr("litematica_container_filler.gui.button.reset"), isHover(mouseX, mouseY, layout.resetX, y, layout.resetW, 20), false);
        return y + 24;
    }

    private EditorLayout getEditorLayout() {
        int screenW = this.getScreenWidth();
        int screenH = this.getScreenHeight();
        int top = screenH < 360 ? 72 : 82;
        int bottomReserve = screenH < 360 ? 34 : 42;
        int height = Math.max(96, screenH - top - bottomReserve);
        int gap = clamp(screenW / 80, 8, 14);
        int sideMargin = clamp(screenW / 48, 10, 18);
        boolean stacked = screenW < 700 || (screenW < 900 && screenH > screenW * 0.78D);

        if (stacked) {
            int panelW = Math.max(180, screenW - sideMargin * 2);
            int minPreviewH = Math.min(128, Math.max(72, height / 3));
            int previewH = clamp((int)(height * 0.42D), minPreviewH, Math.max(minPreviewH, height - 92));
            int controlsH = Math.max(72, height - previewH - gap);
            Rect previewPanel = new Rect(sideMargin, top, panelW, previewH);
            Rect controlsPanel = new Rect(sideMargin, top + previewH + gap, panelW, controlsH);
            return new EditorLayout(
                    previewPanel,
                    controlsPanel,
                    new Rect(previewPanel.x + 14, previewPanel.y + 34, previewPanel.width - 28, previewPanel.height - 48)
            );
        }

        int availableW = screenW - sideMargin * 2 - gap;
        int minControlsW = screenW < 960 ? 270 : 320;
        int previewW = clamp((int)(availableW * 0.48D), 220, Math.max(220, availableW - minControlsW));
        int controlsW = availableW - previewW - gap;
        if (controlsW < minControlsW) {
            controlsW = minControlsW;
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
        int gap = availableW < 360 ? 6 : 8;
        int minControlW = availableW < 360 ? 104 : 132;
        int maxColumns = Math.max(1, (availableW + gap) / (minControlW + gap));
        int columns = Math.max(1, (int)Math.ceil(controlCount / (double)rows));
        columns = Math.min(columns, maxColumns);
        rows = Math.max(1, (int)Math.ceil(controlCount / (double)columns));
        int width = Math.max(112, (availableW - gap * (columns - 1)) / columns);
        if (rows * rowH > availableH && columns < Math.max(1, (availableW + gap) / (104 + gap))) {
            columns++;
            rows = Math.max(1, (int)Math.ceil(controlCount / (double)columns));
            width = Math.max(104, (availableW - gap * (columns - 1)) / columns);
        }
        return new ControlLayout(startX, startY, width, rowH, rows, gap);
    }

    private ColorControlLayout getColorControlLayout(int x, int y, int width) {
        int resetW = Math.min(54, Math.max(44, width / 5));
        int swatchW = Math.min(34, Math.max(28, width / 5));
        int valueW = Math.max(64, width - swatchW - resetW - 8);
        return new ColorControlLayout(
                x,
                swatchW,
                x + swatchW + 4,
                valueW,
                x + width - resetW,
                resetW
        );
    }

    private void resetHudPosition() {
        Configs.TOOL_HUD_CUSTOM_X.resetToDefault();
        Configs.TOOL_HUD_CUSTOM_Y.resetToDefault();
        Configs.saveToFile();
        this.initGui();
    }

    private void drawPreview(DrawContext context, int x, int y, int width, int height, float partialTicks) {
        drawGradient(context, x, y, x + width, y + height, 0xFF111821, 0xFF172131);
        drawGrid(context, x, y, width, height);
        String hint = this.tab == EditorTab.HUD
                ? tr("litematica_container_filler.gui.label.hud_position_hint")
                : tr("litematica_container_filler.gui.label.render_editor_drag");
        int hintW = Math.max(48, Math.min(Math.max(48, width - 16), Math.max(84, width / 2)));
        drawSoftRect(context, x + 8, y + 8, hintW, 18, 0xCC1D242E);
        drawString(context, fit(hint, Math.max(8, (hintW - 12) / 6)), x + 16, y + 13, MUTED_SOFT);
        switch (this.tab) {
            case HIGHLIGHTS -> drawHighlightPreview(context, x, y, width, height);
            case MARKERS -> drawMarkerPreview(context, x, y, width, height);
            case HUD -> drawHudPreview(context, x, y, width, height);
        }
    }

    private void drawHighlightPreview(DrawContext context, int x, int y, int width, int height) {
        long key = highlightPreviewKey(x, y, width, height);
        if (key != this.cachedHighlightPreviewKey) {
            this.cachedHighlightPreviewCommands = buildHighlightPreviewCommands(x, y, width, height);
            this.cachedHighlightPreviewKey = key;
        }

        for (PreviewDrawCommand command : this.cachedHighlightPreviewCommands) {
            command.draw(context);
        }
    }

    private long highlightPreviewKey(int x, int y, int width, int height) {
        long key = (((long)x & 0xFFFFL) << 48) ^ (((long)y & 0xFFFFL) << 32) ^ ((long)width << 16) ^ height;
        key = key * 31L + Math.round(this.previewYaw * 4.0f);
        key = key * 31L + Math.round(this.previewPitch * 4.0f);
        key = key * 31L + (Configs.RENDER_STATE_GLASS.getBooleanValue() ? 1L : 0L);
        key = key * 31L + (Configs.RENDER_STATE_TOP_PLATE.getBooleanValue() ? 1L : 0L);
        key = key * 31L + Double.doubleToLongBits(Configs.HIGHLIGHT_TOP_PLATE_SIZE.getDoubleValue());
        key = key * 31L + Configs.HIGHLIGHT_COLOR_UNFILLED.getColor().getIntValue();
        key = key * 31L + Configs.HIGHLIGHT_COLOR_PARTIAL.getColor().getIntValue();
        key = key * 31L + Configs.HIGHLIGHT_COLOR_OVERFILLED.getColor().getIntValue();
        key = key * 31L + Configs.HIGHLIGHT_COLOR_WRONG.getColor().getIntValue();
        key = key * 31L + Configs.HIGHLIGHT_COLOR_SATISFIED.getColor().getIntValue();
        key = key * 31L + Configs.HIGHLIGHT_COLOR_UNKNOWN.getColor().getIntValue();
        key = key * 31L + Configs.HIGHLIGHT_COLOR_UNPLACED.getColor().getIntValue();
        return key;
    }

    private List<PreviewDrawCommand> buildHighlightPreviewCommands(int x, int y, int width, int height) {
        List<PreviewDrawCommand> rawCommands = new ArrayList<>();
        List<StatePreview> states = List.of(
                new StatePreview("unfilled", Configs.HIGHLIGHT_COLOR_UNFILLED),
                new StatePreview("partial", Configs.HIGHLIGHT_COLOR_PARTIAL),
                new StatePreview("overfilled", Configs.HIGHLIGHT_COLOR_OVERFILLED),
                new StatePreview("wrong", Configs.HIGHLIGHT_COLOR_WRONG),
                new StatePreview("satisfied", Configs.HIGHLIGHT_COLOR_SATISFIED),
                new StatePreview("unknown", Configs.HIGHLIGHT_COLOR_UNKNOWN),
                new StatePreview("unplaced", Configs.HIGHLIGHT_COLOR_UNPLACED),
                new StatePreview("manual_completed", Configs.HIGHLIGHT_COLOR_SATISFIED),
                new StatePreview("manual_needs_fill", Configs.HIGHLIGHT_COLOR_UNFILLED)
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
            boolean manual = state.key.startsWith("manual_");
            addHighlightModelCommands(rawCommands, cx, cy, size, state.color.getColor(), Configs.RENDER_STATE_GLASS.getBooleanValue(), Configs.RENDER_STATE_TOP_PLATE.getBooleanValue(), manual, state.key);
            rawCommands.add(new PreviewLabelCommand(translateOrFallback("litematica_container_filler.gui.label.render_state." + state.key, state.key), cx, cy + Math.round(size * 0.86f) + 12, cellW));
        }

        return compactPreviewCommands(rawCommands);
    }

    private void addHighlightModelCommands(List<PreviewDrawCommand> commands, int cx, int cy, float size, Color4f color, boolean glass, boolean topPlate, boolean manual, String key) {
        Color4f base = glass ? color : new Color4f(color.r, color.g, color.b, Math.min(0.16f, color.a));
        addPreviewWorldBoxCommands(commands, cx, cy, size, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, base);

        if (topPlate) {
            Color4f plate = new Color4f(color.r, color.g, color.b, Math.min(0.72f, Math.max(0.20f, color.a * 0.9f)));
            float inset = Math.max(TOP_PLATE_MIN_INSET, (1.0f - (float)Configs.HIGHLIGHT_TOP_PLATE_SIZE.getDoubleValue()) * 0.5f);
            addPreviewWorldBoxCommands(commands, cx, cy, size,
                    inset, 1.0f + TOP_PLATE_BOTTOM_OFFSET, inset,
                    1.0f - inset, 1.0f + TOP_PLATE_TOP_OFFSET, 1.0f - inset,
                    plate);
        }

        if (manual) {
            addManualBadgePreviewCommands(commands, cx, cy, size, "manual_completed".equals(key));
        }
    }

    private void addManualBadgePreviewCommands(List<PreviewDrawCommand> commands, float cx, float cy, float size, boolean completed) {
        Color4f ring = completed
                ? new Color4f(0.88f, 1.0f, 0.95f, 0.76f)
                : new Color4f(1.0f, 0.86f, 0.34f, 0.76f);
        Color4f accent = completed
                ? new Color4f(0.16f, 1.0f, 0.62f, 0.90f)
                : new Color4f(1.0f, 0.52f, 0.12f, 0.90f);
        float half = MANUAL_BADGE_SIZE * 0.5f;
        float thick = Math.max(0.022f, MANUAL_BADGE_THICKNESS);
        float y = 1.0f + TOP_PLATE_TOP_OFFSET + MANUAL_BADGE_GAP;

        addPreviewWorldBoxCommands(commands, cx, cy, size, 0.5f - half, y, 0.5f - half, 0.5f + half, y + thick, 0.5f - half + thick, ring);
        addPreviewWorldBoxCommands(commands, cx, cy, size, 0.5f - half, y, 0.5f + half - thick, 0.5f + half, y + thick, 0.5f + half, ring);
        addPreviewWorldBoxCommands(commands, cx, cy, size, 0.5f - half, y, 0.5f - half, 0.5f - half + thick, y + thick, 0.5f + half, ring);
        addPreviewWorldBoxCommands(commands, cx, cy, size, 0.5f + half - thick, y, 0.5f - half, 0.5f + half, y + thick, 0.5f + half, ring);
        if (completed) {
            addPreviewWorldBoxCommands(commands, cx, cy, size, 0.5f - half * 0.48f, y + thick, 0.5f - thick * 0.5f, 0.5f - half * 0.08f, y + thick * 2.0f, 0.5f + thick * 0.5f, accent);
            addPreviewWorldBoxCommands(commands, cx, cy, size, 0.5f - half * 0.12f, y + thick, 0.5f - thick * 0.5f, 0.5f + half * 0.56f, y + thick * 2.0f, 0.5f + thick * 0.5f, accent);
        } else {
            addPreviewWorldBoxCommands(commands, cx, cy, size, 0.5f - thick * 0.5f, y + thick, 0.5f - half * 0.58f, 0.5f + thick * 0.5f, y + thick * 2.0f, 0.5f + half * 0.22f, accent);
            addPreviewWorldBoxCommands(commands, cx, cy, size, 0.5f - thick * 0.6f, y + thick, 0.5f + half * 0.42f, 0.5f + thick * 0.6f, y + thick * 2.0f, 0.5f + half * 0.56f, accent);
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
            drawPreviewLabel(context, tr("litematica_container_filler.gui.label." + marker.key), cx, cy + Math.min(48, cellH / 3), cellW);
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

            drawLine(context, anchorX, anchorY, cornerX, anchorY, withAlpha(0xFF6F7B86, alpha));
            drawLine(context, cornerX, anchorY, edgeX, edgeY, withAlpha(0xFF6F7B86, alpha));
            context.fill(anchorX - 3, anchorY - 3, anchorX + 4, anchorY + 4, withAlpha(0xFF6F7B86, alpha));
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
        drawRoundedPreviewCard(context, panelX, panelY, panelW, panelH, withAlpha(0xFF050708, (int)(alpha * 0.92D)));
        int headerH = Math.max(15, Math.min(20, panelH / 4));
        drawRoundedPreviewCardSection(context, panelX, panelY, panelW, panelH, 0, headerH + 7, withAlpha(0xFF151F26, (int)(alpha * 0.56D)));
        if (Configs.TOOL_HUD_BORDER.getBooleanValue()) {
            drawRoundedPreviewCardOutline(context, panelX, panelY, panelW, panelH, withAlpha(0xFF6F7B86, (int)(alpha * 0.88D)));
        }
        float scale = Configs.TOOL_HUD_SCALE.getIntegerValue() / 100.0f;
        float iconScale = scale * clampFloat(Configs.TOOL_HUD_ICON_SCALE.getIntegerValue() / 100.0f, 0.5f, 1.5f);
        int titleMaxWidth = Math.max(0, panelW - 20);
        drawScaledString(context, fitToWidth(tr("litematica_container_filler.gui.label.hud_title"), unscaledWidth(titleMaxWidth, scale)), panelX + 10, panelY + 8, scale, withAlpha(TEXT, alpha));
        double time = quantizedTime(Configs.TOOL_HUD_FRAME_RATE.getIntegerValue());
        ContainerToolMode mode = Configs.CONTAINER_TOOL_MODE.getOptionListValue() instanceof ContainerToolMode toolMode ? toolMode : ContainerToolMode.CLEAR;
        int pulse = (int)(Math.sin(time * 5.0D) * 2.0D);
        int textHeight = Math.max(1, (int)Math.ceil(this.textRenderer.fontHeight * scale));
        int lineGap = Math.max(textHeight + 1, Math.round(11.0f * scale));
        int iconX = panelX + Math.round(24 * scale) + (mode == ContainerToolMode.COPY ? pulse : 0);
        int iconY = panelY + headerH + Math.round(29 * scale) + (mode == ContainerToolMode.COPY ? 0 : pulse);
        drawMiniHudToolIcon(context, iconX, iconY, alpha, iconScale, mode);
        int textX = panelX + Math.round(48 * scale);
        int labelY = panelY + headerH + 8;
        int textMaxWidth = Math.max(0, panelW - (textX - panelX) - 10);
        int textMaxUnscaled = unscaledWidth(textMaxWidth, scale);
        String actionText = tr("litematica_container_filler.hud.tool_action", "V");
        String secondaryText = tr("litematica_container_filler.hud.tool_switch_close", "G", "H");
        int contentBottom = panelY + panelH - Math.max(5, Math.round(5.0f * scale));
        int maxLines = countFittingLines(labelY, lineGap, textHeight, contentBottom, 3);
        if (maxLines <= 1) {
            drawScaledString(context, fitToWidth(actionText, textMaxUnscaled), textX, Math.min(labelY, Math.max(panelY + headerH + 2, contentBottom - textHeight)), scale, withAlpha(0xFF55FF68, alpha));
        } else {
            drawScaledString(context, fitToWidth(mode.getDisplayName(), textMaxUnscaled), textX, labelY, scale, withAlpha(MUTED, alpha));
            drawScaledString(context, fitToWidth(actionText, textMaxUnscaled), textX, labelY + lineGap, scale, withAlpha(0xFF55FF68, alpha));
        }
        if (maxLines >= 3) {
            drawScaledString(context, fitToWidth(secondaryText, textMaxUnscaled), textX, labelY + lineGap * 2, scale, withAlpha(MUTED, (int)(alpha * 0.78D)));
        }
    }

    private void drawRoundedPreviewCard(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x + 4, y, x + width - 4, y + height, color);
        context.fill(x, y + 4, x + width, y + height - 4, color);
        context.fill(x + 2, y + 2, x + width - 2, y + height - 2, color);
    }

    private void drawRoundedPreviewCardSection(DrawContext context, int x, int y, int width, int height, int sectionTop, int sectionBottom, int color) {
        int top = clamp(sectionTop, 0, height);
        int bottom = clamp(sectionBottom, top, height);
        drawRoundedPreviewRows(context, x, y, width, height, 7, top, bottom, color);
    }

    private void drawRoundedPreviewCardOutline(DrawContext context, int x, int y, int width, int height, int color) {
        if (width <= 2 || height <= 2) {
            return;
        }

        for (int row = 0; row < height; row++) {
            int outerInset = Math.min(previewCardInset(row, height), Math.max(0, (width - 1) / 2));
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
            int innerInset = Math.min(previewCardInset(row - 1, innerHeight), Math.max(0, (innerWidth - 1) / 2));
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

    private int previewCardInset(int row, int height) {
        int edgeDistance = Math.min(row, height - row - 1);
        if (edgeDistance <= 1) {
            return 4;
        }
        if (edgeDistance <= 3) {
            return 2;
        }
        return 0;
    }

    private int countFittingLines(int firstY, int lineGap, int textHeight, int bottom, int requestedLines) {
        int lines = 0;
        for (int i = 0; i < requestedLines; i++) {
            if (firstY + lineGap * i + textHeight <= bottom) {
                lines++;
            }
        }
        return lines;
    }

    private void drawRoundedPreviewRows(DrawContext context, int x, int y, int width, int height, int radius, int fromRow, int toRow, int color) {
        if (width <= 0 || height <= 0) return;
        int start = clamp(fromRow, 0, height);
        int end = clamp(toRow, start, height);
        int r = clamp(radius, 0, Math.min(width, height) / 2);

        for (int row = start; row < end; row++) {
            int inset = roundedInset(row, height, r);
            context.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
        }
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
        int shaft = Math.max(3, Math.round(3.4f * scale));
        int halfW = Math.max(7, Math.round(10.5f * scale));
        int top = Math.round(-14.0f * scale);
        int neck = Math.round(-0.5f * scale);
        int shoulder = Math.round(1.0f * scale);
        int tip = Math.round(15.0f * scale);
        fillRotatedPolygon(context, cx, cy, direction, body,
                new int[]{-shaft, shaft, shaft, halfW, 0, -halfW, -shaft},
                new int[]{top, top, neck, shoulder, tip, shoulder, neck});
        fillRotatedRect(context, cx, cy, -1, top + 2, 2, tip - 3, withAlpha(0xFFFFFFFF, Math.round(alpha * 0.28f)), direction);
    }

    private void drawHighlightModel(DrawContext context, int cx, int cy, float size, Color4f color, boolean glass, boolean topPlate) {
        if (glass) {
            drawPreviewWorldBox(context, cx, cy, size, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, color);
        } else {
            drawPreviewWorldBox(context, cx, cy, size, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, new Color4f(color.r, color.g, color.b, Math.min(0.16f, color.a)));
        }

        if (topPlate) {
            Color4f plate = new Color4f(color.r, color.g, color.b, Math.min(0.72f, Math.max(0.20f, color.a * 0.9f)));
            float inset = Math.max(TOP_PLATE_MIN_INSET, (1.0f - (float)Configs.HIGHLIGHT_TOP_PLATE_SIZE.getDoubleValue()) * 0.5f);
            drawPreviewWorldBox(context, cx, cy, size,
                    inset, 1.0f + TOP_PLATE_BOTTOM_OFFSET, inset,
                    1.0f - inset, 1.0f + TOP_PLATE_TOP_OFFSET, 1.0f - inset,
                    plate);
        }
    }

    private void drawMarkerModel(DrawContext context, int cx, int cy, float size, double time, MarkerKind kind) {
        switch (kind) {
            case FILLING -> drawPreviewDownArrow(context, cx, cy + (int)(Math.sin(time * 5.0D) * 3.0D), size * 0.35f, size * 0.84f, size * 0.10f, Configs.HIGHLIGHT_COLOR_FILLING.getColor());
            case QUEUED -> drawPreviewSpinner(context, cx, cy, size, time, Configs.HIGHLIGHT_COLOR_QUEUED.getColor());
            case MISSING -> drawPreviewExclamation(context, cx, cy, size, Configs.HIGHLIGHT_COLOR_MISSING_MATERIAL.getColor());
        }
    }

    private void drawPreviewDownArrow(DrawContext context, int cx, int cy, float halfWidth, float height, float depth, Color4f color) {
        float shaftHalf = halfWidth * 0.26f;
        float shaftTop = cy - height * 0.44f;
        float neckY = cy + height * 0.02f;
        float shoulderY = cy + height * 0.03f;
        float tipY = cy + height * 0.48f;
        drawPreviewPrism(context,
                new float[]{
                        cx - shaftHalf,
                        cx + shaftHalf,
                        cx + shaftHalf,
                        cx + halfWidth,
                        cx,
                        cx - halfWidth,
                        cx - shaftHalf
                },
                new float[]{
                        shaftTop,
                        shaftTop,
                        neckY,
                        shoulderY,
                        tipY,
                        shoulderY,
                        neckY
                },
                depth,
                color);
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
        context.fill(cx - 1, cy - 1, cx + 2, cy + 2, withAlpha(PRIMARY, 150));
    }

    private void fillRotatedRect(DrawContext context, int cx, int cy, int relX1, int relY1, int relX2, int relY2, int color, ArrowDirection direction) {
        int[] a = rotate(cx, cy, relX1, relY1, direction);
        int[] b = rotate(cx, cy, relX2, relY2, direction);
        context.fill(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1]), color);
    }

    private void fillRotatedPolygon(DrawContext context, int cx, int cy, ArrowDirection direction, int color, int[] relXs, int[] relYs) {
        int[] xs = new int[relXs.length];
        int[] ys = new int[relYs.length];
        for (int i = 0; i < relXs.length; i++) {
            int[] point = rotate(cx, cy, relXs[i], relYs[i], direction);
            xs[i] = point[0];
            ys[i] = point[1];
        }
        fillPolygon(context, xs, ys, color);
    }

    private void fillPolygon(DrawContext context, int[] xs, int[] ys, int color) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int y : ys) {
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }

        for (int y = minY; y <= maxY; y++) {
            java.util.ArrayList<Integer> intersections = new java.util.ArrayList<>();
            for (int i = 0; i < xs.length; i++) {
                int next = (i + 1) % xs.length;
                int y1 = ys[i];
                int y2 = ys[next];
                if ((y1 <= y && y2 > y) || (y2 <= y && y1 > y)) {
                    double t = (y - y1) / (double)(y2 - y1);
                    intersections.add((int)Math.round(xs[i] + (xs[next] - xs[i]) * t));
                }
            }
            java.util.Collections.sort(intersections);
            for (int i = 0; i + 1 < intersections.size(); i += 2) {
                context.fill(intersections.get(i), y, intersections.get(i + 1) + 1, y + 1, color);
            }
        }
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

    private void drawPreviewPrism(DrawContext context, float[] xs, float[] ys, float halfZ, Color4f color) {
        double yaw = Math.toRadians(this.previewYaw);
        double pitch = Math.toRadians(this.previewPitch);
        double cosY = Math.cos(yaw);
        double sinY = Math.sin(yaw);
        double cosP = Math.cos(pitch);
        double sinP = Math.sin(pitch);
        int count = xs.length * 2;
        int[] sx = new int[count];
        int[] sy = new int[count];
        double[] sz = new double[count];
        float cx = 0.0f;
        float cy = 0.0f;
        for (float x : xs) cx += x;
        for (float y : ys) cy += y;
        cx /= xs.length;
        cy /= ys.length;

        for (int i = 0; i < xs.length; i++) {
            for (int side = 0; side < 2; side++) {
                int index = i + side * xs.length;
                double localX = xs[i] - cx;
                double localY = ys[i] - cy;
                double localZ = side == 0 ? -halfZ : halfZ;
                double rx = localX * cosY - localZ * sinY;
                double rz = localX * sinY + localZ * cosY;
                double ry = localY * cosP - rz * sinP;
                rz = localY * sinP + rz * cosP;
                sx[index] = Math.round(cx + (float)(rx * 0.82D));
                sy[index] = Math.round(cy - (float)(ry * 0.62D));
                sz[index] = rz;
            }
        }

        java.util.ArrayList<int[]> faceList = new java.util.ArrayList<>();
        int[] front = new int[xs.length];
        int[] back = new int[xs.length];
        for (int i = 0; i < xs.length; i++) {
            front[i] = i;
            back[i] = xs.length + (xs.length - 1 - i);
        }
        faceList.add(front);
        faceList.add(back);
        for (int i = 0; i < xs.length; i++) {
            int next = (i + 1) % xs.length;
            faceList.add(new int[]{i, next, next + xs.length, i + xs.length});
        }
        int[][] faces = faceList.toArray(new int[0][]);
        java.util.Arrays.sort(faces, java.util.Comparator.comparingDouble(face -> {
            double total = 0.0D;
            for (int index : face) total += sz[index];
            return total / face.length;
        }));
        int argb = color.toVanillaArgb();
        for (int[] face : faces) {
            fillQuad(context, sx, sy, face, withAlpha(argb, clamp((int)(color.a * 165.0f), 45, 180)));
        }
        int edge = withAlpha(argb, clamp((int)(color.a * 255.0f), 110, 255));
        for (int i = 0; i < xs.length; i++) {
            int next = (i + 1) % xs.length;
            drawLine(context, sx[i], sy[i], sx[next], sy[next], edge);
            drawLine(context, sx[i + xs.length], sy[i + xs.length], sx[next + xs.length], sy[next + xs.length], edge);
            drawLine(context, sx[i], sy[i], sx[i + xs.length], sy[i + xs.length], edge);
        }
    }

    private void drawPreviewBox(DrawContext context, float cx, float cy, float halfX, float halfY, float halfZ, Color4f color) {
        drawPreviewBox(context, cx, cy, 0.0f, 0.0f, 0.0f, halfX, halfY, halfZ, color, null);
    }

    private void addPreviewBoxCommands(List<PreviewDrawCommand> commands, float cx, float cy, float halfX, float halfY, float halfZ, Color4f color) {
        drawPreviewBox(null, cx, cy, 0.0f, 0.0f, 0.0f, halfX, halfY, halfZ, color, commands);
    }

    private void drawPreviewWorldBox(DrawContext context, float cx, float cy, float scale, float minX, float minY, float minZ, float maxX, float maxY, float maxZ, Color4f color) {
        drawPreviewWorldBox(context, null, cx, cy, scale, minX, minY, minZ, maxX, maxY, maxZ, color);
    }

    private void addPreviewWorldBoxCommands(List<PreviewDrawCommand> commands, float cx, float cy, float scale, float minX, float minY, float minZ, float maxX, float maxY, float maxZ, Color4f color) {
        drawPreviewWorldBox(null, commands, cx, cy, scale, minX, minY, minZ, maxX, maxY, maxZ, color);
    }

    private void drawPreviewWorldBox(DrawContext context, List<PreviewDrawCommand> commands, float cx, float cy, float scale, float minX, float minY, float minZ, float maxX, float maxY, float maxZ, Color4f color) {
        float centerX = ((minX + maxX) * 0.5f - 0.5f) * scale * 2.0f;
        float centerY = ((minY + maxY) * 0.5f - 0.5f) * scale * 2.0f;
        float centerZ = ((minZ + maxZ) * 0.5f - 0.5f) * scale * 2.0f;
        float halfX = Math.max(0.5f, (maxX - minX) * scale);
        float halfY = Math.max(0.5f, (maxY - minY) * scale);
        float halfZ = Math.max(0.5f, (maxZ - minZ) * scale);
        drawPreviewBox(context, cx, cy, centerX, centerY, centerZ, halfX, halfY, halfZ, color, commands);
    }

    private void drawPreviewBox(DrawContext context, float cx, float cy, float centerX, float centerY, float centerZ, float halfX, float halfY, float halfZ, Color4f color, List<PreviewDrawCommand> commands) {
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
            double x = centerX + vertices[i][0] * halfX;
            double y = centerY + vertices[i][1] * halfY;
            double z = centerZ + vertices[i][2] * halfZ;
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
            fillQuad(context, sx, sy, face, withAlpha(argb, alpha), commands);
        }

        int edge = withAlpha(argb, clamp((int)(color.a * 255.0f), 110, 255));
        int[][] edges = {
                {0, 1}, {1, 2}, {2, 3}, {3, 0},
                {4, 5}, {5, 6}, {6, 7}, {7, 4},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };
        for (int[] line : edges) {
            drawLine(context, sx[line[0]], sy[line[0]], sx[line[1]], sy[line[1]], edge, commands);
        }
    }

    private void fillQuad(DrawContext context, int[] xs, int[] ys, int[] face, int color) {
        fillQuad(context, xs, ys, face, color, null);
    }

    private void fillQuad(DrawContext context, int[] xs, int[] ys, int[] face, int color, List<PreviewDrawCommand> commands) {
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
                int x1 = intersections.get(i);
                int x2 = intersections.get(i + 1) + 1;
                if (commands != null) {
                    commands.add(new PreviewFillCommand(x1, y, x2, y + 1, color));
                } else {
                    context.fill(x1, y, x2, y + 1, color);
                }
            }
        }
    }

    private List<PreviewDrawCommand> compactPreviewCommands(List<PreviewDrawCommand> commands) {
        if (commands.isEmpty()) {
            return List.of();
        }

        ArrayList<PreviewDrawCommand> compacted = new ArrayList<>(commands.size());
        PreviewFillCommand pending = null;
        for (PreviewDrawCommand command : commands) {
            if (command instanceof PreviewFillCommand fill) {
                if (pending != null && pending.color == fill.color && pending.y1 == fill.y1 && pending.y2 == fill.y2 && pending.x2 >= fill.x1) {
                    pending = new PreviewFillCommand(pending.x1, pending.y1, Math.max(pending.x2, fill.x2), pending.y2, pending.color);
                } else {
                    if (pending != null) {
                        compacted.add(pending);
                    }
                    pending = fill;
                }
                continue;
            }

            if (pending != null) {
                compacted.add(pending);
                pending = null;
            }
            compacted.add(command);
        }
        if (pending != null) {
            compacted.add(pending);
        }
        return compacted;
    }

    private Rect getPreviewBounds() {
        return getEditorLayout().preview;
    }

    private boolean isPointInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private boolean isHover(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private void drawCard(DrawContext context, int x, int y, int width, int height) {
        drawRoundedRect(context, x + 2, y + 4, width, height, 8, 0x55000000);
        drawRoundedRect(context, x, y, width, height, 8, SURFACE);
        context.fill(x + 3, y, x + width - 3, y + 1, 0x66303A46);
        context.fill(x + 2, y + height - 1, x + width - 2, y + height, OUTLINE);
    }

    private void drawGrid(DrawContext context, int x, int y, int width, int height) {
        for (int gx = x; gx < x + width; gx += 18) {
            context.fill(gx, y, gx + 1, y + height, 0x185B6674);
        }
        for (int gy = y; gy < y + height; gy += 18) {
            context.fill(x, gy, x + width, gy + 1, 0x185B6674);
        }
    }

    private void drawGradient(DrawContext context, int x1, int y1, int x2, int y2, int top, int bottom) {
        context.fillGradient(x1, y1, x2, y2, top, bottom);
    }

    private void drawSoftRect(DrawContext context, int x, int y, int width, int height, int color) {
        if (width <= 0 || height <= 0) return;
        context.fill(x, y, x + width, y + height, color);
    }

    private void drawRoundedRect(DrawContext context, int x, int y, int width, int height, int radius, int color) {
        if (width <= 0 || height <= 0) return;
        int r = clamp(radius, 0, Math.min(width, height) / 2);
        if (r <= 0) {
            drawSoftRect(context, x, y, width, height, color);
            return;
        }

        for (int row = 0; row < height; row++) {
            int inset = roundedInset(row, height, r);
            context.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
        }
    }

    private int roundedInset(int row, int height, int radius) {
        if (row >= radius && row < height - radius) return 0;
        double dy = row < radius ? radius - row - 0.5D : row - (height - radius) + 0.5D;
        return Math.max(0, radius - (int)Math.sqrt(Math.max(0.0D, radius * radius - dy * dy)));
    }

    private void drawRoundedOutline(DrawContext context, int x, int y, int width, int height, int radius, int border, int fill) {
        drawRoundedRect(context, x, y, width, height, radius, border);
        drawRoundedRect(context, x + 1, y + 1, width - 2, height - 2, Math.max(0, radius - 1), fill);
    }

    private void drawMaterialButton(DrawContext context, int x, int y, int width, int height, String label, boolean hovered, boolean primaryText) {
        int fill = hovered ? SURFACE_CONTAINER_HIGH : SURFACE_CONTAINER;
        int border = hovered ? PRIMARY : OUTLINE;
        drawSoftRect(context, x, y, width, height, fill);
        context.fill(x + 1, y, x + width - 1, y + 1, border);
        context.fill(x + 1, y + height - 1, x + width - 1, y + height, border);
        context.fill(x, y + 1, x + 1, y + height - 1, border);
        context.fill(x + width - 1, y + 1, x + width, y + height - 1, border);
        if (!label.isEmpty()) drawString(context, label, x + 8, y + 6, primaryText || hovered ? PRIMARY : TEXT);
    }

    private void drawPreviewLabel(DrawContext context, String label, int cx, int y, int cellWidth) {
        String clipped = fit(label, Math.max(10, cellWidth / 7));
        int labelWidth = Math.min(cellWidth - 12, Math.max(42, clipped.length() * 6 + 12));
        int x = cx - labelWidth / 2;
        drawSoftRect(context, x, y - 3, labelWidth, 18, 0xDD1D242E);
        drawString(context, clipped, x + 6, y + 2, MUTED);
    }

    private void drawLine(DrawContext context, int x1, int y1, int x2, int y2, int color) {
        drawLine(context, x1, y1, x2, y2, color, null);
    }

    private void drawLine(DrawContext context, int x1, int y1, int x2, int y2, int color, List<PreviewDrawCommand> commands) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        if (steps <= 0) {
            if (commands != null) {
                commands.add(new PreviewFillCommand(x1, y1, x1 + 1, y1 + 1, color));
            } else {
                context.fill(x1, y1, x1 + 1, y1 + 1, color);
            }
            return;
        }
        float stepX = (x2 - x1) / (float)steps;
        float stepY = (y2 - y1) / (float)steps;
        for (int i = 0; i <= steps; i++) {
            int px = Math.round(x1 + stepX * i);
            int py = Math.round(y1 + stepY * i);
            if (commands != null) {
                commands.add(new PreviewFillCommand(px, py, px + 1, py + 1, color));
            } else {
                context.fill(px, py, px + 1, py + 1, color);
            }
        }
    }

    private void drawScaledString(DrawContext context, String text, int x, int y, float scale, int color) {
        if (text == null || text.isEmpty()) {
            return;
        }
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

    private int unscaledWidth(int scaledWidth, float scale) {
        if (scaledWidth <= 0 || scale <= 0.01f) {
            return 0;
        }
        return Math.max(0, (int)Math.floor(scaledWidth / scale));
    }

    private String fitToWidth(String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (this.textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }

        String suffix = "...";
        int suffixWidth = this.textRenderer.getWidth(suffix);
        if (suffixWidth > maxWidth) {
            return "";
        }

        int end = text.length();
        while (end > 0 && this.textRenderer.getWidth(text.substring(0, end)) + suffixWidth > maxWidth) {
            end--;
        }
        return end <= 0 ? suffix : text.substring(0, end) + suffix;
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

    private static String translateOrFallback(String key, String fallback) {
        String translated = tr(key);
        return translated.equals(key) ? fallback : translated;
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

    private record ColorControlLayout(int swatchX, int swatchW, int valueX, int valueW, int resetX, int resetW) {
    }

    private record ColorInputBinding(ConfigColor config, GuiTextFieldGeneric field) {
    }

    private record ControlSpec(Consumer<ControlRect> addButtons, Supplier<String> label, Supplier<String> comment, ControlKind kind, ConfigColor colorConfig) {
    }

    private interface PreviewDrawCommand {
        void draw(DrawContext context);
    }

    private record PreviewFillCommand(int x1, int y1, int x2, int y2, int color) implements PreviewDrawCommand {
        @Override
        public void draw(DrawContext context) {
            context.fill(x1, y1, x2, y2, color);
        }
    }

    private final class PreviewLabelCommand implements PreviewDrawCommand {
        private final String label;
        private final int cx;
        private final int y;
        private final int cellWidth;

        private PreviewLabelCommand(String label, int cx, int y, int cellWidth) {
            this.label = label;
            this.cx = cx;
            this.y = y;
            this.cellWidth = cellWidth;
        }

        @Override
        public void draw(DrawContext context) {
            drawPreviewLabel(context, label, cx, y, cellWidth);
        }
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

    private enum ControlKind {
        BUTTON,
        STEPPER,
        COLOR
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
