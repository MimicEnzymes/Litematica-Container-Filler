package com.mimicenzymes.litematicafiller.gui;

import com.mimicenzymes.litematicafiller.core.MaterialReplacementUi;
import com.mimicenzymes.litematicafiller.core.MaterialReplacementScope;
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
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class GuiItemReplacementPicker extends GuiBase {
    private static final int PREFERRED_PANEL_WIDTH = 304;
    private static final int PREFERRED_PANEL_HEIGHT = 310;
    private static final int MIN_PANEL_WIDTH = 212;
    private static final int MIN_PANEL_HEIGHT = 188;
    private static final int CELL_SIZE = 24;
    private static final int ICON_SIZE = 18;
    private static final int MAX_GRID_COLUMNS = 11;
    private static final int MAX_GRID_ROWS = 7;
    private static final int TITLE_HEIGHT = 26;
    private static final int SEARCH_Y = 34;
    private static final int GRID_TOP_OFFSET = 62;
    private static final int GRID_SIDE_RESERVE = 36;
    private static final int GRID_BOTTOM_RESERVE = 68;

    private final Screen parent;
    private final ItemStack source;
    private final String schematicKey;
    private final List<Item> allItems = new ArrayList<>();
    private final List<Item> filteredItems = new ArrayList<>();

    private GuiTextFieldGeneric searchField;
    private GuiTextFieldGeneric nameField;
    private String searchText = "";
    private String targetName = "";
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
    private Item selectedItem;
    private ButtonGeneric applyButton;
    private ButtonGeneric ignoreButton;
    private ButtonGeneric resetButton;
    private ButtonGeneric cancelButton;
    private ButtonGeneric scopeButton;
    private MaterialReplacementScope scope = MaterialReplacementScope.SCHEMATIC;
    private boolean draggingPanel;
    private boolean draggingScrollbar;
    private int dragOffsetX;
    private int dragOffsetY;
    private int scrollbarDragOffsetY;

    public GuiItemReplacementPicker(Screen parent, ItemStack source, String schematicKey) {
        this.parent = parent;
        this.source = source.copy();
        this.schematicKey = schematicKey;
        this.source.setCount(1);
        this.title = "";
        this.useTitleHierarchy = false;

        for (Item item : Registries.ITEM) {
            if (item != Items.AIR) {
                this.allItems.add(item);
            }
        }

        this.sortItems("");
        if (this.schematicKey == null || this.schematicKey.isBlank()) {
            this.scope = MaterialReplacementScope.GLOBAL;
        }

        Optional<ItemStack> currentTarget = this.scope == MaterialReplacementScope.SCHEMATIC
                ? MaterialReplacer.getSchematicReplacementTarget(this.schematicKey, this.source).or(() -> MaterialReplacer.getGlobalReplacementTarget(this.source))
                : MaterialReplacer.getGlobalReplacementTarget(this.source);
        if (currentTarget.isPresent()) {
            ItemStack stack = currentTarget.get();
            if (!stack.isEmpty() && !stack.isOf(Items.AIR)) {
                this.selectedItem = stack.getItem();
                Text name = stack.get(DataComponentTypes.CUSTOM_NAME);
                if (name != null) this.targetName = name.getString();
            }
        }
        this.refreshFilter();
    }

    public Screen getParentScreen() {
        return this.parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.updateDimensions();

        if (this.panelX == 0 && this.panelY == 0) {
            if (this.parent instanceof GuiGlobalMaterialReplacementPicker picker) {
                this.panelX = picker.getPanelX();
                this.panelY = picker.getPanelY();
                this.clampPanel();
            } else {
                this.panelX = Math.max(4, (this.getScreenWidth() - this.panelWidth) >> 1);
                this.panelY = Math.max(4, (this.getScreenHeight() - this.panelHeight) >> 1);
            }
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

        int bottomY = this.panelY + this.panelHeight - 34;
        String applyText = StringUtils.translate("litematica_container_filler.gui.button.apply");
        String cancelText = StringUtils.translate("litematica_container_filler.gui.button.cancel");
        int availableW = this.panelWidth - 28;
        int applyWidth = this.getButtonWidth(applyText, 42, Math.max(42, availableW / 4));
        int cancelWidth = this.getButtonWidth(cancelText, 40, Math.max(40, availableW / 4));
        int cancelX = this.panelX + this.panelWidth - 14 - cancelWidth;
        int applyX = cancelX - 6 - applyWidth;
        int nameFieldX = this.panelX + 58;
        this.nameField = new GuiTextFieldGeneric(nameFieldX, bottomY, Math.max(36, applyX - nameFieldX - 8), 20, this.textRenderer);
        this.nameField.setTextWrapper(this.targetName);
        this.nameField.setMaxLengthWrapper(64);
        this.addTextField(this.nameField, field -> {
            this.targetName = field.getTextWrapper();
            return true;
        });

        this.applyButton = new ButtonGeneric(applyX, bottomY - 1, applyWidth, 22, applyText);
        this.addButton(this.applyButton, (button, mouseButton) -> this.applySelected());

        int buttonY = this.panelY + this.panelHeight - 66;
        String scopeText = this.getScopeButtonText();
        String ignoreText = StringUtils.translate("litematica_container_filler.gui.button.material_replace_ignore");
        String resetText = StringUtils.translate("litematica_container_filler.gui.button.material_replace_reset");
        int rowButtonMax = Math.max(42, (availableW - 12) / 3);
        int scopeWidth = this.getButtonWidth(scopeText, 42, rowButtonMax);
        int ignoreWidth = this.getButtonWidth(ignoreText, 48, rowButtonMax);
        int resetWidth = this.getButtonWidth(resetText, 48, rowButtonMax);
        int scopeX = this.panelX + 14;
        int ignoreX = scopeX + scopeWidth + 6;
        int resetX = this.panelX + this.panelWidth - 14 - resetWidth;

        this.scopeButton = new ButtonGeneric(scopeX, buttonY, scopeWidth, 22, scopeText);
        this.scopeButton.setEnabled(this.schematicKey != null && !this.schematicKey.isBlank());
        this.scopeButton.setHoverStrings(StringUtils.translate("litematica_container_filler.gui.tooltip.material_replace_scope"));
        this.addButton(this.scopeButton, (button, mouseButton) -> {
            if (this.schematicKey != null && !this.schematicKey.isBlank()) {
                this.scope = this.scope.next();
                this.updateScopeButtonTextAndLayout();
            }
        });

        this.ignoreButton = new ButtonGeneric(ignoreX, buttonY, ignoreWidth, 22, ignoreText);
        this.ignoreButton.setHoverStrings(List.of(StringUtils.translate("litematica_container_filler.gui.tooltip.material_replace_ignore")));
        this.addButton(this.ignoreButton, (button, mouseButton) -> {
            MaterialReplacementUi.addReplacementRule(this.source, new ItemStack(Items.AIR), this.scope, this.schematicKey);
            this.closeToParent();
        });

        this.resetButton = new ButtonGeneric(resetX, buttonY, resetWidth, 22, resetText);
        this.resetButton.setHoverStrings(StringUtils.translate("litematica_container_filler.gui.tooltip.material_replace_reset"));
        this.addButton(this.resetButton, (button, mouseButton) -> {
            MaterialReplacementUi.resetReplacementRule(this.source, this.scope, this.schematicKey);
            this.closeToParent();
        });

        this.cancelButton = new ButtonGeneric(cancelX, bottomY - 1, cancelWidth, 22, cancelText);
        this.addButton(this.cancelButton, (button, mouseButton) -> this.closeToParent());
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
            this.selectedItem = this.filteredItems.get(index);
            this.moveRowTo(index);
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

        String titleText = StringUtils.translate("litematica_container_filler.gui.title.material_replace", this.source.getName().getString());
        String clippedTitle = this.fitToWidth(titleText, this.panelWidth - 28);
        this.drawString(drawContext, clippedTitle, this.panelX + ((this.panelWidth - this.getStringWidth(clippedTitle)) >> 1), this.panelY + 8, 0xFFFFFFFF);
        this.drawString(drawContext, StringUtils.translate("litematica_container_filler.gui.label.material_replace_rename"),
                this.panelX + 14, this.panelY + this.panelHeight - 29, 0xFFC8D0DA);

        RenderUtils.drawRect(drawContext, this.gridX - 2, this.gridY - 2, this.gridColumns * CELL_SIZE + 4, this.gridRows * CELL_SIZE + 4, 0x6630353D);
        drawContext.enableScissor(this.gridX, this.gridY, this.gridX + this.gridColumns * CELL_SIZE, this.gridY + this.gridRows * CELL_SIZE);
        this.drawItems(drawContext, mouseX, mouseY);
        drawContext.disableScissor();
        this.drawScrollbar(drawContext);

        this.drawWidgets(drawContext, mouseX, mouseY);
        this.drawButtons(drawContext, mouseX, mouseY, partialTicks);
        this.searchField.renderWrapper(drawContext, mouseX, mouseY, partialTicks);
        this.nameField.renderWrapper(drawContext, mouseX, mouseY, partialTicks);

        int hoveredIndex = this.getHoveredIndex(mouseX, mouseY);
        if (hoveredIndex >= 0) {
            ItemStack hoveredStack = new ItemStack(this.filteredItems.get(hoveredIndex));
            Identifier id = Registries.ITEM.getId(hoveredStack.getItem());
            RenderUtils.drawHoverText(drawContext, mouseX, mouseY, List.of(
                    hoveredStack.getName().getString(),
                    GuiBase.TXT_DARK_GRAY + id
            ));
        }

        if (this.ignoreButton != null && this.ignoreButton.isMouseOver(mouseX, mouseY)) {
            RenderUtils.drawHoverText(drawContext, mouseX, mouseY,
                    List.of(StringUtils.translate("litematica_container_filler.gui.tooltip.material_replace_ignore")));
        }
    }

    private void drawParent(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        Screen background = this.parent instanceof GuiGlobalMaterialReplacementPicker picker
                ? picker.getParentScreen()
                : this.parent;
        if (background instanceof GuiBase guiBase) {
            guiBase.render(drawContext, mouseX, mouseY, partialTicks);
        } else if (background != null) {
            background.render(drawContext, mouseX, mouseY, partialTicks);
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
            boolean selected = item == this.selectedItem;

            RenderUtils.drawOutlinedBox(
                    drawContext,
                    x + 2, y + 2,
                    ICON_SIZE, ICON_SIZE,
                    selected ? 0x664AA3FF : hovered ? 0x44FFFFFF : 0x222A2F36,
                    selected ? 0xFF7DC7FF : hovered ? 0xCCFFFFFF : 0x44656D78
            );
            drawContext.drawItem(new ItemStack(item), x + 3, y + 3);
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

    private void applySelected() {
        if (this.selectedItem == null) return;

        ItemStack target = new ItemStack(this.selectedItem);
        String rename = this.targetName == null ? "" : this.targetName.trim();
        if (!rename.isEmpty()) {
            target.set(DataComponentTypes.CUSTOM_NAME, Text.literal(rename));
        }

        MaterialReplacementUi.addReplacementRule(this.source, target, this.scope, this.schematicKey);
        this.closeToParent();
    }

    private String getScopeButtonText() {
        String key = this.scope == MaterialReplacementScope.SCHEMATIC
                ? "litematica_container_filler.gui.button.material_replace_scope_schematic"
                : "litematica_container_filler.gui.button.material_replace_scope_global";
        return StringUtils.translate(key);
    }

    private int getButtonWidth(String text, int minWidth) {
        return this.getButtonWidth(text, minWidth, Integer.MAX_VALUE);
    }

    private int getButtonWidth(String text, int minWidth, int maxWidth) {
        return Math.min(Math.max(minWidth, this.getStringWidth(text) + 12), Math.max(minWidth, maxWidth));
    }

    private void updateScopeButtonTextAndLayout() {
        if (this.scopeButton == null) return;

        String text = this.getScopeButtonText();
        this.scopeButton.setDisplayString(text);
        this.repositionControls();
    }

    private void closeToParent() {
        MaterialReplacementUi.refreshParentList();
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
        if (this.selectedItem != null) {
            this.moveRowTo(this.filteredItems.indexOf(this.selectedItem));
        }
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

    private void moveRowTo(int index) {
        if (index < 0) return;

        while (index < this.rowIndex * this.gridColumns) {
            this.rowIndex--;
        }
        while (index >= (this.rowIndex + this.gridRows) * this.gridColumns) {
            this.rowIndex++;
        }
        this.rowIndex = MathHelper.clamp(this.rowIndex, 0, this.getMaxRowIndex());
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

        int availableW = this.panelWidth - 28;
        String applyText = StringUtils.translate("litematica_container_filler.gui.button.apply");
        String cancelText = StringUtils.translate("litematica_container_filler.gui.button.cancel");
        int applyWidth = this.getButtonWidth(applyText, 42, Math.max(42, availableW / 4));
        int cancelWidth = this.getButtonWidth(cancelText, 40, Math.max(40, availableW / 4));
        int cancelX = this.panelX + this.panelWidth - 14 - cancelWidth;
        int applyX = cancelX - 6 - applyWidth;
        int bottomY = this.panelY + this.panelHeight - 35;

        if (this.nameField != null) {
            this.nameField.setXWrapper(this.panelX + 58);
            this.nameField.setYWrapper(this.panelY + this.panelHeight - 34);
        }

        if (this.applyButton != null) {
            this.applyButton.setWidth(applyWidth);
            this.applyButton.setPosition(applyX, bottomY);
        }
        if (this.cancelButton != null) {
            this.cancelButton.setWidth(cancelWidth);
            this.cancelButton.setPosition(cancelX, bottomY);
        }

        int buttonY = this.panelY + this.panelHeight - 66;
        int scopeX = this.panelX + 14;
        int rowButtonMax = Math.max(42, (availableW - 12) / 3);
        int scopeWidth = this.getButtonWidth(this.getScopeButtonText(), 42, rowButtonMax);
        int ignoreWidth = this.getButtonWidth(StringUtils.translate("litematica_container_filler.gui.button.material_replace_ignore"), 48, rowButtonMax);
        int resetWidth = this.getButtonWidth(StringUtils.translate("litematica_container_filler.gui.button.material_replace_reset"), 48, rowButtonMax);
        int ignoreX = scopeX + scopeWidth + 6;
        int resetX = this.panelX + this.panelWidth - 14 - resetWidth;

        if (this.scopeButton != null) {
            this.scopeButton.setWidth(scopeWidth);
            this.scopeButton.setPosition(scopeX, buttonY);
        }
        if (this.ignoreButton != null) {
            this.ignoreButton.setWidth(ignoreWidth);
            this.ignoreButton.setPosition(ignoreX, buttonY);
        }
        if (this.resetButton != null) {
            this.resetButton.setWidth(resetWidth);
            this.resetButton.setPosition(resetX, buttonY);
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
