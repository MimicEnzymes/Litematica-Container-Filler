package com.mimicenzymes.litematicafiller.gui;

import com.mimicenzymes.litematicafiller.core.MaterialReplacementUi;
import com.mimicenzymes.litematicafiller.core.MaterialReplacer;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class GuiGlobalMaterialReplacementPicker extends GuiBase {
    private static final int PREFERRED_PANEL_WIDTH = 304;
    private static final int PREFERRED_PANEL_HEIGHT = 310;
    private static final int MIN_PANEL_WIDTH = 212;
    private static final int MIN_PANEL_HEIGHT = 170;
    private static final int CELL_SIZE = 24;
    private static final int ICON_SIZE = 18;
    private static final int MAX_GRID_COLUMNS = 11;
    private static final int MAX_GRID_ROWS = 9;
    private static final int TITLE_HEIGHT = 26;
    private static final int SEARCH_Y = 34;
    private static final int GRID_TOP_OFFSET = 62;
    private static final int GRID_SIDE_RESERVE = 36;
    private static final int GRID_BOTTOM_RESERVE = 30;

    private final Screen parent;
    private final List<Item> allItems = new ArrayList<>();
    private final List<Item> filteredItems = new ArrayList<>();
    private GuiTextFieldGeneric searchField;
    private ButtonGeneric closeButton;
    private String searchText = "";
    private int panelX;
    private int panelY;
    private int panelWidth = PREFERRED_PANEL_WIDTH;
    private int panelHeight = PREFERRED_PANEL_HEIGHT;
    private int gridX;
    private int gridY;
    private int gridColumns = MAX_GRID_COLUMNS;
    private int gridRows = MAX_GRID_ROWS;
    private int rowIndex;
    private double smoothRowIndex = 0.0D;
    private long lastAnimationNanos = 0L;
    private boolean smoothScrollInitialized = false;
    private boolean draggingPanel;
    private boolean draggingScrollbar;
    private int dragOffsetX;
    private int dragOffsetY;
    private int scrollbarDragOffsetY;

    public GuiGlobalMaterialReplacementPicker(Screen parent) {
        this.parent = parent;
        this.title = "";
        this.useTitleHierarchy = false;

        for (Item item : Registries.ITEM) {
            if (item != Items.AIR) {
                this.allItems.add(item);
            }
        }

        this.sortItems("");
        this.refreshFilter();
    }

    public Screen getParentScreen() {
        return this.parent;
    }

    int getPanelX() {
        return this.panelX;
    }

    int getPanelY() {
        return this.panelY;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.updateDimensions();

        if (this.panelX == 0 && this.panelY == 0) {
            this.panelX = Math.max(4, (this.getScreenWidth() - this.panelWidth) >> 1);
            this.panelY = Math.max(4, (this.getScreenHeight() - this.panelHeight) >> 1);
        } else {
            this.clampPanel();
        }

        this.reflow();

        this.searchField = new GuiTextFieldGeneric(this.panelX + 14, this.panelY + SEARCH_Y, this.panelWidth - 28, 20, this.textRenderer);
        this.searchField.setTextWrapper(this.searchText);
        this.searchField.setMaxLengthWrapper(80);
        this.addTextField(this.searchField, field -> {
            this.searchText = field.getTextWrapper();
            this.rowIndex = 0;
            this.refreshFilter();
            return true;
        });

        String closeText = StringUtils.translate("litematica_container_filler.gui.button.close");
        int closeWidth = Math.min(Math.max(52, this.getStringWidth(closeText) + 12), Math.max(52, this.panelWidth - 28));
        this.closeButton = new ButtonGeneric(this.panelX + this.panelWidth - 14 - closeWidth, this.panelY + this.panelHeight - 26, closeWidth, 20, closeText);
        this.addButton(this.closeButton, (button, mouseButton) -> this.closeToParent());
    }

    @Override
    public boolean onMouseClicked(Click click, boolean doubleClick) {
        int mouseX = (int) click.x();
        int mouseY = (int) click.y();

        if (this.isMouseOverScrollbarThumb(mouseX, mouseY)) {
            this.draggingScrollbar = true;
            this.scrollbarDragOffsetY = mouseY - this.getScrollbarThumbY();
            this.setDragging(true);
            return true;
        }

        if (this.isMouseOverScrollbar(mouseX, mouseY)) {
            this.draggingScrollbar = true;
            this.scrollbarDragOffsetY = this.getScrollbarThumbHeight() >> 1;
            this.updateScrollFromMouse(mouseY);
            this.setDragging(true);
            return true;
        }

        if (this.isMouseOverTitle(mouseX, mouseY)) {
            this.draggingPanel = true;
            this.dragOffsetX = mouseX - this.panelX;
            this.dragOffsetY = mouseY - this.panelY;
            this.setDragging(true);
            return true;
        }

        if (super.onMouseClicked(click, doubleClick)) {
            return true;
        }

        int index = this.getHoveredIndex(mouseX, mouseY);
        if (index >= 0) {
            MaterialReplacementUi.open(this, new ItemStack(this.filteredItems.get(index)));
            return true;
        }

        return this.isMouseOverPanel(mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        int mouseX = (int) click.x();
        int mouseY = (int) click.y();

        if (this.draggingPanel) {
            this.panelX = mouseX - this.dragOffsetX;
            this.panelY = mouseY - this.dragOffsetY;
            this.clampPanel();
            this.repositionControls();
            return true;
        }

        if (this.draggingScrollbar) {
            this.updateScrollFromMouse(mouseY);
            return true;
        }

        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean onMouseReleased(Click click) {
        this.draggingPanel = false;
        this.draggingScrollbar = false;
        this.setDragging(false);
        return super.onMouseReleased(click);
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.isMouseOverPanel((int) mouseX, (int) mouseY)) {
            this.scrollRows(verticalAmount < 0.0 ? 1 : -1);
            return true;
        }

        return super.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean onKeyTyped(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.closeToParent();
            return true;
        }
        return super.onKeyTyped(input);
    }

    @Override
    public void drawContents(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        this.updateScrollAnimation();
        this.drawParent(drawContext, mouseX, mouseY, partialTicks);
        RenderUtils.drawRect(drawContext, 0, 0, this.getScreenWidth(), this.getScreenHeight(), 0x66000000);

        RenderUtils.drawOutlinedBox(drawContext, this.panelX, this.panelY, this.panelWidth, this.panelHeight, 0xEF11151B, 0xFF98A7B8);
        RenderUtils.drawRect(drawContext, this.panelX + 1, this.panelY + 1, this.panelWidth - 2, TITLE_HEIGHT, 0xAA1B2028);

        String titleText = StringUtils.translate("litematica_container_filler.gui.title.global_material_replace");
        String clippedTitle = this.fitToWidth(titleText, this.panelWidth - 28);
        this.drawString(drawContext, clippedTitle, this.panelX + ((this.panelWidth - this.getStringWidth(clippedTitle)) >> 1), this.panelY + 8, 0xFFFFFFFF);

        RenderUtils.drawRect(drawContext, this.gridX - 2, this.gridY - 2, this.gridColumns * CELL_SIZE + 4, this.gridRows * CELL_SIZE + 4, 0x6630353D);
        this.drawItems(drawContext, mouseX, mouseY);
        this.drawScrollbar(drawContext);

        this.drawWidgets(drawContext, mouseX, mouseY);
        this.drawButtons(drawContext, mouseX, mouseY, partialTicks);
        this.searchField.renderWrapper(drawContext, mouseX, mouseY, partialTicks);

        int hoveredIndex = this.getHoveredIndex(mouseX, mouseY);
        if (hoveredIndex >= 0) {
            ItemStack hoveredStack = new ItemStack(this.filteredItems.get(hoveredIndex));
            Identifier id = Registries.ITEM.getId(hoveredStack.getItem());
            List<String> lines = new ArrayList<>();
            lines.add(hoveredStack.getName().getString());
            lines.add(GuiBase.TXT_DARK_GRAY + id);
            Optional<ItemStack> target = MaterialReplacer.getGlobalReplacementTarget(hoveredStack);
            target.ifPresent(stack -> lines.add(StringUtils.translate(
                    "litematica_container_filler.gui.tooltip.global_material_replace_target",
                    stack.isEmpty() ? StringUtils.translate("litematica_container_filler.gui.label.material_replace_ignored") : stack.getName().getString()
            )));
            RenderUtils.drawHoverText(drawContext, mouseX, mouseY, lines);
        }
    }

    private void drawParent(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        if (this.parent instanceof GuiBase guiBase) {
            guiBase.render(drawContext, mouseX, mouseY, partialTicks);
        } else if (this.parent != null) {
            this.parent.render(drawContext, mouseX, mouseY, partialTicks);
        }
    }

    private void drawItems(DrawContext drawContext, int mouseX, int mouseY) {
        int firstRow = Math.max(0, (int)Math.floor(this.smoothRowIndex));
        double rowFraction = MathHelper.clamp(this.smoothRowIndex - firstRow, 0.0D, 0.999D);
        int firstIndex = firstRow * this.gridColumns;
        int endIndex = Math.min(firstIndex + (this.gridRows + 1) * this.gridColumns, this.filteredItems.size());
        int scrollOffset = (int)Math.round(rowFraction * CELL_SIZE);
        int hoveredIndex = this.getHoveredIndex(mouseX, mouseY);

        for (int index = firstIndex; index < endIndex; index++) {
            Item item = this.filteredItems.get(index);
            int local = index - firstIndex;
            int x = this.gridX + (local % this.gridColumns) * CELL_SIZE;
            int y = this.gridY + (local / this.gridColumns) * CELL_SIZE - scrollOffset;
            boolean hovered = index == hoveredIndex;
            boolean replaced = MaterialReplacer.getGlobalReplacementTarget(new ItemStack(item)).isPresent();

            RenderUtils.drawOutlinedBox(
                    drawContext,
                    x + 2, y + 2,
                    ICON_SIZE, ICON_SIZE,
                    replaced ? 0x664AA3FF : hovered ? 0x44FFFFFF : 0x222A2F36,
                    replaced ? 0xFF7DC7FF : hovered ? 0xCCFFFFFF : 0x44656D78
            );
            drawContext.drawItem(new ItemStack(item), x + 3, y + 3);

            if (replaced) {
                RenderUtils.drawRect(drawContext, x + 14, y + 3, 6, 6, 0xDD36A3FF);
            }
        }
    }

    private void drawScrollbar(DrawContext drawContext) {
        int barX = this.getScrollbarX();
        int barY = this.gridY;
        int barHeight = this.gridRows * CELL_SIZE;
        RenderUtils.drawRect(drawContext, barX, barY, 5, barHeight, 0x88485058);

        int thumbY = this.getScrollbarThumbY(this.smoothRowIndex);
        int thumbHeight = this.getScrollbarThumbHeight();
        RenderUtils.drawRect(drawContext, barX, thumbY, 5, thumbHeight, 0xFFE4E9F1);
    }

    private void closeToParent() {
        GuiBase.openGui(this.parent);
    }

    private void refreshFilter() {
        String needle = this.searchText == null ? "" : this.searchText.trim().toLowerCase(Locale.ROOT);
        this.filteredItems.clear();

        for (Item item : this.allItems) {
            ItemStack stack = new ItemStack(item);
            Identifier id = Registries.ITEM.getId(item);
            String idText = id == null ? "" : id.toString().toLowerCase(Locale.ROOT);
            String nameText = stack.getName().getString().toLowerCase(Locale.ROOT);
            if (needle.isEmpty() || idText.contains(needle) || nameText.contains(needle)) {
                this.filteredItems.add(item);
            }
        }

        this.sortItems(needle);
        this.rowIndex = MathHelper.clamp(this.rowIndex, 0, this.getMaxRowIndex());
        this.snapScrollAnimation();
    }

    private void sortItems(String needle) {
        String query = needle == null ? "" : needle.toLowerCase(Locale.ROOT);
        Comparator<Item> comparator = Comparator
                .comparingInt((Item item) -> this.matchRank(item, query))
                .thenComparingInt(Registries.ITEM::getRawId)
                .thenComparing(item -> Registries.ITEM.getId(item).toString());

        this.allItems.sort(comparator);
        this.filteredItems.sort(comparator);
    }

    private int matchRank(Item item, String query) {
        if (query.isBlank()) return 0;

        String name = new ItemStack(item).getName().getString().toLowerCase(Locale.ROOT);
        String id = Registries.ITEM.getId(item).toString().toLowerCase(Locale.ROOT);
        if (name.equals(query) || id.equals(query)) return 0;
        if (name.startsWith(query) || id.endsWith(":" + query)) return 1;
        if (name.contains(query) || id.contains(query)) return 2;
        return 3;
    }

    private int getHoveredIndex(int mouseX, int mouseY) {
        if (!this.isMouseOverGrid(mouseX, mouseY)) return -1;

        int col = (mouseX - this.gridX) / CELL_SIZE;
        int row = (mouseY - this.gridY) / CELL_SIZE;
        int index = (this.rowIndex + row) * this.gridColumns + col;
        return index >= 0 && index < this.filteredItems.size() ? index : -1;
    }

    private boolean isMouseOverGrid(int mouseX, int mouseY) {
        return mouseX >= this.gridX
                && mouseY >= this.gridY
                && mouseX < this.gridX + this.gridColumns * CELL_SIZE
                && mouseY < this.gridY + this.gridRows * CELL_SIZE;
    }

    private boolean isMouseOverPanel(int mouseX, int mouseY) {
        return mouseX >= this.panelX && mouseY >= this.panelY
                && mouseX < this.panelX + this.panelWidth && mouseY < this.panelY + this.panelHeight;
    }

    private boolean isMouseOverTitle(int mouseX, int mouseY) {
        return mouseX >= this.panelX && mouseY >= this.panelY
                && mouseX < this.panelX + this.panelWidth && mouseY < this.panelY + TITLE_HEIGHT;
    }

    private boolean isMouseOverScrollbar(int mouseX, int mouseY) {
        int barX = this.getScrollbarX();
        return mouseX >= barX - 2 && mouseX < barX + 8
                && mouseY >= this.gridY && mouseY < this.gridY + this.gridRows * CELL_SIZE;
    }

    private boolean isMouseOverScrollbarThumb(int mouseX, int mouseY) {
        int barX = this.getScrollbarX();
        int thumbY = this.getScrollbarThumbY();
        int thumbHeight = this.getScrollbarThumbHeight();
        return mouseX >= barX - 3 && mouseX < barX + 9
                && mouseY >= thumbY && mouseY < thumbY + thumbHeight;
    }

    private void scrollRows(int delta) {
        this.rowIndex = MathHelper.clamp(this.rowIndex + delta, 0, this.getMaxRowIndex());
    }

    private void updateScrollFromMouse(int mouseY) {
        int totalRows = this.getTotalRows();
        if (totalRows <= this.gridRows) {
            this.rowIndex = 0;
            return;
        }

        int barHeight = this.gridRows * CELL_SIZE;
        int thumbHeight = this.getScrollbarThumbHeight();
        int maxOffset = Math.max(1, barHeight - thumbHeight);
        int offset = MathHelper.clamp(mouseY - this.gridY - this.scrollbarDragOffsetY, 0, maxOffset);
        this.rowIndex = MathHelper.clamp((int) Math.round((double) offset * this.getMaxRowIndex() / maxOffset), 0, this.getMaxRowIndex());
    }

    private int getScrollbarX() {
        return this.gridX + this.gridColumns * CELL_SIZE + 8;
    }

    private int getScrollbarThumbHeight() {
        int barHeight = this.gridRows * CELL_SIZE;
        int totalRows = this.getTotalRows();
        return totalRows > this.gridRows ? Math.max(14, barHeight * this.gridRows / totalRows) : barHeight;
    }

    private int getScrollbarThumbY() {
        return this.getScrollbarThumbY(this.rowIndex);
    }

    private int getScrollbarThumbY(double rowIndexValue) {
        int barHeight = this.gridRows * CELL_SIZE;
        int thumbHeight = this.getScrollbarThumbHeight();
        int totalRows = this.getTotalRows();
        if (totalRows <= this.gridRows) return this.gridY;

        int maxOffset = barHeight - thumbHeight;
        return this.gridY + MathHelper.clamp((int)Math.round(rowIndexValue * maxOffset / (totalRows - this.gridRows)), 0, maxOffset);
    }

    private int getTotalRows() {
        return Math.max(1, (this.filteredItems.size() + this.gridColumns - 1) / this.gridColumns);
    }

    private int getMaxRowIndex() {
        return Math.max(0, this.getTotalRows() - this.gridRows);
    }

    private void reflow() {
        this.updateDimensions();
        int gridWidth = this.gridColumns * CELL_SIZE;
        this.gridX = this.panelX + Math.max(14, (this.panelWidth - gridWidth - 13) / 2);
        this.gridY = this.panelY + GRID_TOP_OFFSET;
        this.rowIndex = MathHelper.clamp(this.rowIndex, 0, this.getMaxRowIndex());
        this.smoothRowIndex = MathHelper.clamp(this.smoothRowIndex, 0.0D, this.getMaxRowIndex());
    }

    private void repositionControls() {
        this.reflow();

        if (this.searchField != null) {
            this.searchField.setXWrapper(this.panelX + 14);
            this.searchField.setYWrapper(this.panelY + SEARCH_Y);
        }

        if (this.closeButton != null) {
            int closeWidth = Math.min(this.closeButton.getWidth(), Math.max(52, this.panelWidth - 28));
            this.closeButton.setWidth(closeWidth);
            this.closeButton.setPosition(this.panelX + this.panelWidth - 14 - closeWidth, this.panelY + this.panelHeight - 26);
        }
    }

    private void clampPanel() {
        this.updateDimensions();
        this.panelX = MathHelper.clamp(this.panelX, 4, Math.max(4, this.getScreenWidth() - this.panelWidth - 4));
        this.panelY = MathHelper.clamp(this.panelY, 4, Math.max(4, this.getScreenHeight() - this.panelHeight - 4));
    }

    private void updateDimensions() {
        this.panelWidth = MathHelper.clamp(PREFERRED_PANEL_WIDTH, MIN_PANEL_WIDTH, Math.max(MIN_PANEL_WIDTH, this.getScreenWidth() - 8));
        this.panelHeight = MathHelper.clamp(PREFERRED_PANEL_HEIGHT, MIN_PANEL_HEIGHT, Math.max(MIN_PANEL_HEIGHT, this.getScreenHeight() - 8));
        this.gridColumns = MathHelper.clamp((this.panelWidth - GRID_SIDE_RESERVE) / CELL_SIZE, 4, MAX_GRID_COLUMNS);
        int availableGridHeight = Math.max(CELL_SIZE, this.panelHeight - GRID_TOP_OFFSET - GRID_BOTTOM_RESERVE);
        this.gridRows = MathHelper.clamp(availableGridHeight / CELL_SIZE, 1, MAX_GRID_ROWS);
    }

    private void updateScrollAnimation() {
        long now = System.nanoTime();
        if (!this.smoothScrollInitialized || this.lastAnimationNanos == 0L) {
            this.snapScrollAnimation();
            this.lastAnimationNanos = now;
            return;
        }

        double dt = Math.min(0.05D, (now - this.lastAnimationNanos) / 1_000_000_000.0D);
        this.lastAnimationNanos = now;
        double speed = this.draggingScrollbar ? 28.0D : 16.0D;
        double blend = 1.0D - Math.exp(-dt * speed);
        this.smoothRowIndex += (this.rowIndex - this.smoothRowIndex) * blend;
        if (Math.abs(this.smoothRowIndex - this.rowIndex) < 0.002D) {
            this.smoothRowIndex = this.rowIndex;
        }
    }

    private void snapScrollAnimation() {
        this.smoothRowIndex = this.rowIndex;
        this.smoothScrollInitialized = true;
        this.lastAnimationNanos = System.nanoTime();
    }

    private String fitToWidth(String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (this.getStringWidth(text) <= maxWidth) {
            return text;
        }
        String suffix = "...";
        int suffixWidth = this.getStringWidth(suffix);
        int end = text.length();
        while (end > 0 && this.getStringWidth(text.substring(0, end)) + suffixWidth > maxWidth) {
            end--;
        }
        return end <= 0 ? suffix : text.substring(0, end) + suffix;
    }
}
