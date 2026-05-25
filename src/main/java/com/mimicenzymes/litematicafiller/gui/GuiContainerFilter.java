package com.mimicenzymes.litematicafiller.gui;

import com.google.common.collect.ImmutableList;
import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.block.Block;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class GuiContainerFilter extends GuiBase {
    private static final int BUTTON_MIN_WIDTH = 112;
    private static final int BUTTON_MAX_WIDTH = 156;
    private static final int BUTTON_HEIGHT = 25;
    private static final int BACKGROUND_TOP = 0xFF0E1218;
    private static final int BACKGROUND_BOTTOM = 0xFF151B24;
    private static final int APP_BAR = 0xF2121720;
    private static final int SURFACE = 0xF21D242E;
    private static final int SURFACE_CONTAINER = 0xFF252D38;
    private static final int SURFACE_CONTAINER_HIGH = 0xFF303A46;
    private static final int OUTLINE = 0xFF3F4A58;
    private static final int PRIMARY = 0xFF8AB4F8;
    private static final int SUCCESS = 0xFF81C995;
    private static final int TEXT = 0xFFE8EAED;
    private static final int MUTED = 0xFFBDC1C6;
    private static final List<ContainerEntry> CONTAINERS = ImmutableList.of(
            ContainerEntry.of("minecraft:chest", "minecraft:chest"),
            ContainerEntry.of("minecraft:trapped_chest", "minecraft:trapped_chest"),
            ContainerEntry.of("minecraft:barrel", "minecraft:barrel"),
            ContainerEntry.of(List.of("minecraft:shulker_box", "minecraft:*_shulker_box"), "minecraft:shulker_box"),
            ContainerEntry.of("minecraft:crafter", "minecraft:crafter"),
            ContainerEntry.of("minecraft:hopper", "minecraft:hopper"),
            ContainerEntry.of("minecraft:dispenser", "minecraft:dispenser"),
            ContainerEntry.of("minecraft:dropper", "minecraft:dropper"),
            ContainerEntry.of("minecraft:furnace", "minecraft:furnace"),
            ContainerEntry.of("minecraft:blast_furnace", "minecraft:blast_furnace"),
            ContainerEntry.of("minecraft:smoker", "minecraft:smoker"),
            ContainerEntry.of("minecraft:brewing_stand", "minecraft:brewing_stand"),
            ContainerEntry.of("minecraft:ender_chest", "minecraft:ender_chest")
    );

    private final Screen parent;

    public GuiContainerFilter(Screen parent) {
        this.parent = parent;
        this.setParent(parent);
        this.title = StringUtils.translate("litematica_container_filler.gui.title.container_filter_picker");
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

        FilterLayout layout = getFilterLayout();

        for (int i = 0; i < CONTAINERS.size(); i++) {
            ContainerEntry entry = CONTAINERS.get(i);
            int col = i % layout.columns;
            int row = i / layout.columns;
            int buttonX = layout.gridX + col * (layout.buttonW + layout.gap);
            int buttonY = layout.gridY + row * layout.rowH;

            ButtonGeneric button = new ContainerButton(buttonX, buttonY, layout.buttonW, entry);
            button.setHoverStrings(entry.patterns.toArray(String[]::new));
            this.addButton(button, new ToggleListener(entry, this));
        }

        FooterLayout footer = getFooterLayout();
        ButtonGeneric defaults = new ButtonGeneric(footer.defaultsX, footer.y, footer.defaultsW, 20, StringUtils.translate("litematica_container_filler.gui.button.defaults"));
        this.addButton(defaults, (button, mouseButton) -> {
            Configs.CONTAINER_FILTER_LIST.setStrings(new ArrayList<>(Configs.CONTAINER_FILTER_LIST.getDefaultStrings()));
            Configs.saveToFile();
            this.initGui();
        });

        ButtonGeneric clear = new ButtonGeneric(footer.clearX, footer.y, footer.clearW, 20, StringUtils.translate("litematica_container_filler.gui.button.clear"));
        this.addButton(clear, (button, mouseButton) -> {
            Configs.CONTAINER_FILTER_LIST.setStrings(List.of());
            Configs.saveToFile();
            this.initGui();
        });

        ButtonGeneric back = new ButtonGeneric(footer.backX, footer.y, footer.backW, 20, StringUtils.translate("litematica_container_filler.gui.button.back"));
        this.addButton(back, (button, mouseButton) -> GuiBase.openGui(parent));
    }

    @Override
    protected void drawScreenBackground(GuiContext drawContext, int mouseX, int mouseY) {
        drawContext.fillGradient(0, 0, this.getScreenWidth(), this.getScreenHeight(), BACKGROUND_TOP, BACKGROUND_BOTTOM);
        drawContext.fill(0, 0, this.getScreenWidth(), 68, APP_BAR);
        drawContext.fill(0, 67, this.getScreenWidth(), 68, OUTLINE);
        drawContext.fill(0, 68, this.getScreenWidth(), 100, 0x26000000);
        FilterLayout layout = getFilterLayout();
        drawCard(drawContext, layout.panelX, layout.panelY, layout.panelW, layout.panelH);
    }

    @Override
    protected void drawTitle(GuiContext drawContext, int mouseX, int mouseY, float partialTicks) {
    }

    @Override
    protected void drawContents(GuiContext drawContext, int mouseX, int mouseY, float partialTicks) {
        int x = 16;
        int y = 18;
        super.drawContents(drawContext, mouseX, mouseY, partialTicks);
        this.drawString(drawContext, this.title, x + 8, y, TEXT);
        this.drawString(drawContext, StringUtils.translate("litematica_container_filler.gui.label.container_filter_picker"), x + 8, y + 14, MUTED);
        drawContext.fill(x + 8, y + 31, x + 36, y + 33, PRIMARY);
        drawContext.fill(x + 37, y + 31, x + 52, y + 33, SURFACE_CONTAINER_HIGH);
        drawFooterButtonOverlays(drawContext, mouseX, mouseY);
    }

    private void drawFooterButtonOverlays(GuiContext context, int mouseX, int mouseY) {
        FooterLayout footer = getFooterLayout();
        drawMaterialButton(context, footer.defaultsX, footer.y, footer.defaultsW, 20, StringUtils.translate("litematica_container_filler.gui.button.defaults"), mouseX, mouseY, false);
        drawMaterialButton(context, footer.clearX, footer.y, footer.clearW, 20, StringUtils.translate("litematica_container_filler.gui.button.clear"), mouseX, mouseY, false);
        drawMaterialButton(context, footer.backX, footer.y, footer.backW, 20, StringUtils.translate("litematica_container_filler.gui.button.back"), mouseX, mouseY, true);
    }

    private static String getButtonLabel(ContainerEntry entry) {
        return entry.getDisplayName();
    }

    private static boolean isSelected(ContainerEntry entry) {
        return Configs.CONTAINER_FILTER_LIST.getStrings().containsAll(entry.patterns);
    }

    private static void toggle(ContainerEntry entry) {
        Set<String> values = new LinkedHashSet<>(Configs.CONTAINER_FILTER_LIST.getStrings());
        if (values.containsAll(entry.patterns)) {
            values.removeAll(entry.patterns);
        } else {
            values.addAll(entry.patterns);
        }
        Configs.CONTAINER_FILTER_LIST.setStrings(new ArrayList<>(values));
        Configs.saveToFile();
    }

    private record ContainerEntry(List<String> patterns, String iconBlockId) {
        static ContainerEntry of(String pattern, String iconBlockId) {
            return new ContainerEntry(List.of(pattern), iconBlockId);
        }

        static ContainerEntry of(List<String> patterns, String iconBlockId) {
            return new ContainerEntry(patterns, iconBlockId);
        }

        String getDisplayName() {
            Identifier id = Identifier.tryParse(iconBlockId);
            if (id != null && Registries.BLOCK.containsId(id)) {
                return Registries.BLOCK.get(id).getName().getString();
            }
            return patterns.isEmpty() ? iconBlockId : patterns.getFirst();
        }

        ItemStack getIconStack() {
            Identifier id = Identifier.tryParse(iconBlockId);
            if (id == null || !Registries.BLOCK.containsId(id)) return ItemStack.EMPTY;
            Block block = Registries.BLOCK.get(id);
            return new ItemStack(block.asItem());
        }
    }

    private record ToggleListener(ContainerEntry entry, GuiContainerFilter parent) implements IButtonActionListener {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            toggle(entry);
            button.setDisplayString(getButtonLabel(entry));
        }
    }

    private static void drawSoftRect(GuiContext context, int x, int y, int width, int height, int color) {
        if (width <= 0 || height <= 0) return;
        context.fill(x + 2, y, x + width - 2, y + height, color);
        context.fill(x, y + 2, x + width, y + height - 2, color);
        context.fill(x + 1, y + 1, x + width - 1, y + height - 1, color);
    }

    private static void drawCard(GuiContext context, int x, int y, int width, int height) {
        drawSoftRect(context, x + 2, y + 4, width, height, 0x55000000);
        drawSoftRect(context, x, y, width, height, SURFACE);
        context.fill(x + 2, y, x + width - 2, y + 1, 0x66303A46);
        context.fill(x + 2, y + height - 1, x + width - 2, y + height, OUTLINE);
    }

    private void drawMaterialButton(GuiContext context, int x, int y, int width, int height, String label, int mouseX, int mouseY, boolean primary) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        int fill = hovered ? SURFACE_CONTAINER_HIGH : SURFACE_CONTAINER;
        int border = hovered ? PRIMARY : OUTLINE;
        drawSoftRect(context, x, y, width, height, fill);
        context.fill(x + 1, y, x + width - 1, y + 1, border);
        context.fill(x + 1, y + height - 1, x + width - 1, y + height, border);
        context.fill(x, y + 1, x + 1, y + height - 1, border);
        context.fill(x + width - 1, y + 1, x + width, y + height - 1, border);
        this.drawString(context, fit(label, Math.max(4, (width - 12) / 6)), x + 8, y + 6, primary || hovered ? PRIMARY : TEXT);
    }

    private FilterLayout getFilterLayout() {
        int screenW = this.getScreenWidth();
        int screenH = this.getScreenHeight();
        int margin = clamp(screenW / 48, 10, 18);
        int panelX = margin;
        int panelY = screenH < 330 ? 70 : 74;
        int panelW = Math.max(160, screenW - margin * 2);
        int panelH = Math.max(70, screenH - panelY - 48);
        int innerPad = clamp(panelW / 48, 8, 14);
        int gridX = panelX + innerPad;
        int gridY = panelY + innerPad;
        int usableW = Math.max(80, panelW - innerPad * 2);
        int gap = usableW < 360 ? 6 : 10;
        int minButtonW = usableW < 260 ? 96 : BUTTON_MIN_WIDTH;
        int columns = Math.max(1, Math.min(CONTAINERS.size(), (usableW + gap) / (minButtonW + gap)));
        int availableH = Math.max(BUTTON_HEIGHT, panelY + panelH - innerPad - gridY);
        int maxRows = Math.max(1, availableH / (BUTTON_HEIGHT + 1));
        while (columns < CONTAINERS.size() && Math.ceil(CONTAINERS.size() / (double)columns) > maxRows) {
            int nextColumns = columns + 1;
            int nextButtonW = (usableW - gap * (nextColumns - 1)) / nextColumns;
            if (nextButtonW < 86) {
                break;
            }
            columns = nextColumns;
        }
        int buttonW = Math.min(BUTTON_MAX_WIDTH, Math.max(86, (usableW - gap * (columns - 1)) / columns));
        while (columns > 1 && buttonW < 86) {
            columns--;
            buttonW = Math.min(BUTTON_MAX_WIDTH, Math.max(86, (usableW - gap * (columns - 1)) / columns));
        }
        int rows = (int)Math.ceil(CONTAINERS.size() / (double)columns);
        int rowH = clamp(availableH / Math.max(1, rows), BUTTON_HEIGHT + 1, 31);
        return new FilterLayout(panelX, panelY, panelW, panelH, gridX, gridY, columns, buttonW, gap, rowH);
    }

    private FooterLayout getFooterLayout() {
        int y = this.getScreenHeight() - 30;
        int margin = clamp(this.getScreenWidth() / 48, 10, 16);
        int gap = this.getScreenWidth() < 360 ? 5 : 8;
        int defaultsW = this.getScreenWidth() < 360 ? 82 : 100;
        int clearW = this.getScreenWidth() < 360 ? 64 : 80;
        int backW = 80;
        int leftX = margin;
        int clearX = leftX + defaultsW + gap;
        int backX = this.getScreenWidth() - margin - backW;
        if (clearX + clearW + gap > backX) {
            backW = Math.max(56, (this.getScreenWidth() - margin * 2 - gap * 2) / 3);
            defaultsW = backW;
            clearW = backW;
            leftX = margin;
            clearX = leftX + defaultsW + gap;
            backX = clearX + clearW + gap;
        }
        return new FooterLayout(y, leftX, defaultsW, clearX, clearW, backX, backW);
    }

    private static String fit(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        if (maxChars <= 1) return text.substring(0, 1);
        return text.substring(0, maxChars - 1) + "...";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class ContainerButton extends ButtonGeneric {
        private final ContainerEntry entry;
        private final ItemStack iconStack;

        private ContainerButton(int x, int y, int width, ContainerEntry entry) {
            super(x, y, width, BUTTON_HEIGHT, getButtonLabel(entry));
            this.entry = entry;
            this.iconStack = entry.getIconStack();
        }

        @Override
        public void render(GuiContext drawContext, int mouseX, int mouseY, boolean selected) {
            boolean enabled = isSelected(this.entry);
            boolean hovered = this.isMouseOver(mouseX, mouseY);
            int x = this.getX();
            int y = this.getY();
            int background = hovered ? SURFACE_CONTAINER_HIGH : SURFACE_CONTAINER;
            int border = enabled ? SUCCESS : hovered ? PRIMARY : OUTLINE;
            int rail = enabled ? SUCCESS : OUTLINE;

            drawSoftRect(drawContext, x, y, this.getWidth(), this.getHeight(), background);
            drawContext.fill(x + 1, y, x + this.getWidth() - 1, y + 1, border);
            drawContext.fill(x + 1, y + this.getHeight() - 1, x + this.getWidth() - 1, y + this.getHeight(), border);
            drawContext.fill(x, y + 1, x + 1, y + this.getHeight() - 1, border);
            drawContext.fill(x + this.getWidth() - 1, y + 1, x + this.getWidth(), y + this.getHeight() - 1, border);
            drawContext.fill(x + 3, y + 4, x + 5, y + this.getHeight() - 4, rail);

            if (!this.iconStack.isEmpty()) {
                Matrix3x2fStack matrices = drawContext.getMatrices();
                matrices.pushMatrix();
                matrices.translate(x + 9.0f, y + 4.5f);
                matrices.scale(1.08f, 1.08f);
                drawContext.drawItem(this.iconStack, 0, 0);
                matrices.popMatrix();
            }

            int statusW = 26;
            this.drawString(drawContext, x + 32, y + 6, TEXT, fit(this.displayString, Math.max(4, (this.getWidth() - 66) / 6)));
            this.drawString(drawContext, x + this.getWidth() - statusW - 7, y + 6, enabled ? SUCCESS : MUTED, enabled ? "ON" : "OFF");
        }
    }

    private record FilterLayout(int panelX, int panelY, int panelW, int panelH, int gridX, int gridY, int columns, int buttonW, int gap, int rowH) {
    }

    private record FooterLayout(int y, int defaultsX, int defaultsW, int clearX, int clearW, int backX, int backW) {
    }
}
