package com.mimicenzymes.litematicafiller.gui;

import com.google.common.collect.ImmutableList;
import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.block.Block;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class GuiContainerFilter extends GuiBase {
    private static final int BUTTON_WIDTH = 184;
    private static final int BUTTON_HEIGHT = 28;
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

        int x = 22;
        int y = 62;
        int columns = Math.max(1, (this.getScreenWidth() - 44) / (BUTTON_WIDTH + 12));

        for (int i = 0; i < CONTAINERS.size(); i++) {
            ContainerEntry entry = CONTAINERS.get(i);
            int col = i % columns;
            int row = i / columns;
            int buttonX = x + col * (BUTTON_WIDTH + 12);
            int buttonY = y + row * 34;

            ButtonGeneric button = new ContainerButton(buttonX, buttonY, entry);
            button.setHoverStrings(entry.patterns.toArray(String[]::new));
            this.addButton(button, new ToggleListener(entry, this));
        }

        int bottomY = this.getScreenHeight() - 30;
        ButtonGeneric defaults = new ButtonGeneric(16, bottomY, 100, 20, StringUtils.translate("litematica_container_filler.gui.button.defaults"));
        this.addButton(defaults, (button, mouseButton) -> {
            Configs.CONTAINER_FILTER_LIST.setStrings(new ArrayList<>(Configs.CONTAINER_FILTER_LIST.getDefaultStrings()));
            Configs.saveToFile();
            this.initGui();
        });

        ButtonGeneric clear = new ButtonGeneric(122, bottomY, 80, 20, StringUtils.translate("litematica_container_filler.gui.button.clear"));
        this.addButton(clear, (button, mouseButton) -> {
            Configs.CONTAINER_FILTER_LIST.setStrings(List.of());
            Configs.saveToFile();
            this.initGui();
        });

        ButtonGeneric back = new ButtonGeneric(this.getScreenWidth() - 96, bottomY, 80, 20, StringUtils.translate("litematica_container_filler.gui.button.back"));
        this.addButton(back, (button, mouseButton) -> GuiBase.openGui(parent));
    }

    @Override
    protected void drawScreenBackground(DrawContext drawContext, int mouseX, int mouseY) {
        drawContext.fill(0, 0, this.getScreenWidth(), this.getScreenHeight(), 0xB0101217);
        drawContext.fill(12, 10, this.getScreenWidth() - 12, this.getScreenHeight() - 10, 0xE01B222A);
        drawContext.fill(12, 10, this.getScreenWidth() - 12, 44, 0xF023303A);
        drawContext.fill(12, 44, this.getScreenWidth() - 12, 46, 0xFF3A5567);
    }

    @Override
    protected void drawTitle(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
    }

    @Override
    protected void drawContents(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        int x = 16;
        int y = 18;
        super.drawContents(drawContext, mouseX, mouseY, partialTicks);
        this.drawString(drawContext, this.title, x, y, 0xFFFFFFFF);
        this.drawString(drawContext, StringUtils.translate("litematica_container_filler.gui.label.container_filter_picker"), x, y + 14, 0xFFB0B0B0);
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

    private static final class ContainerButton extends ButtonGeneric {
        private final ContainerEntry entry;
        private final ItemStack iconStack;

        private ContainerButton(int x, int y, ContainerEntry entry) {
            super(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, getButtonLabel(entry));
            this.entry = entry;
            this.iconStack = entry.getIconStack();
        }

        @Override
        public void render(DrawContext drawContext, int mouseX, int mouseY, boolean selected) {
            boolean enabled = isSelected(this.entry);
            boolean hovered = this.isMouseOver(mouseX, mouseY);
            int x = this.getX();
            int y = this.getY();
            int background = enabled ? 0xE02E4F3F : 0xD0202830;
            int border = enabled ? 0xFF58D68D : 0xFF4A5560;
            int hover = hovered ? 0x22FFFFFF : 0x00000000;

            drawContext.fill(x, y, x + this.getWidth(), y + this.getHeight(), 0xFF10151A);
            drawContext.fill(x + 1, y + 1, x + this.getWidth() - 1, y + this.getHeight() - 1, background);
            drawContext.fill(x + 1, y + 1, x + 4, y + this.getHeight() - 1, border);
            if (hovered) {
                drawContext.fill(x + 1, y + 1, x + this.getWidth() - 1, y + this.getHeight() - 1, hover);
            }

            if (!this.iconStack.isEmpty()) {
                drawContext.drawItem(this.iconStack, x + 9, y + 6);
            }

            this.drawString(drawContext, x + 31, y + 6, enabled ? 0xFFFFFFFF : 0xFFE1E7EE, this.displayString);
            this.drawString(drawContext, x + this.getWidth() - 34, y + 6, enabled ? 0xFF96FFC0 : 0xFF89939C, enabled ? "ON" : "OFF");
        }
    }
}
