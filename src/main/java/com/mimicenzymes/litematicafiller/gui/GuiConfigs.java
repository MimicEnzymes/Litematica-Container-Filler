package com.mimicenzymes.litematicafiller.gui;

import com.mimicenzymes.litematicafiller.Reference;
import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.config.Hotkeys;
import com.mimicenzymes.litematicafiller.core.ManualContainerOverrideManager;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetKeybindSettings;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptionsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ConfigButtonKeybind;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

public class GuiConfigs extends GuiConfigsBase {
    private static Tab tab = Tab.FEATURE;

    public GuiConfigs(Screen parent) {
        super(10, 74, Reference.MOD_ID, parent, "litematica_container_filler.gui.title.configs");
    }

    public GuiConfigs() {
        this(Minecraft.getInstance().screen);
    }

    @Override
    public void initGui() {
        super.initGui();
        this.clearOptions();
        int x = 10;
        int y = 26;
        for (Tab tab : Tab.values()) {
            String tabName = fi.dy.masa.malilib.util.StringUtils.translate("litematica_container_filler.gui.button." + tab.name().toLowerCase());
            ButtonGeneric botton = new ButtonGeneric(x, y, -1, 20, tabName);
            botton.setEnabled(GuiConfigs.tab != tab);
            this.addButton(botton, new ButtonListener(tab, this));
            x += botton.getWidth() + 2;
        }

        if (tab == Tab.FILTER) {
            String label = fi.dy.masa.malilib.util.StringUtils.translate("litematica_container_filler.gui.button.container_filter_picker");
            ButtonGeneric button = new ButtonGeneric(10, 50, 180, 20, label);
            this.addButton(button, (clickedButton, mouseButton) -> GuiBase.openGui(new GuiContainerFilter(this)));
        } else if (tab == Tab.RENDER) {
            String label = fi.dy.masa.malilib.util.StringUtils.translate("litematica_container_filler.gui.button.render_editor");
            ButtonGeneric button = new ButtonGeneric(10, 50, 180, 20, label);
            this.addButton(button, (clickedButton, mouseButton) -> GuiBase.openGui(new GuiRenderEditor(this)));
        }
    }

    @Override
    protected WidgetListConfigOptions createListWidget(int listX, int listY) {
        return new ConfigListWidget(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), this.getConfigWidth(), 0.75f, true, this);
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<ConfigOptionWrapper> list = new ArrayList<>();
        switch (tab) {
            case FEATURE -> {
                Configs.CORE_OPTIONS.forEach(c -> list.add(new ConfigOptionWrapper(c)));
            }
            case DATA -> Configs.DATA_OPTIONS.forEach(c -> list.add(new ConfigOptionWrapper(c)));
            case LOGISTICS -> Configs.LOGISTICS_OPTIONS.forEach(c -> list.add(new ConfigOptionWrapper(c)));
            case FILTER -> {
                list.add(new ConfigOptionWrapper(Configs.CONTAINER_FILTER_MODE));
                list.add(new ConfigOptionWrapper(Configs.CONTAINER_FILTER_SCOPE));
                list.add(new ConfigOptionWrapper(Configs.CONTAINER_FILTER_LIST));
            }
            case TOOLS -> Configs.TOOL_OPTIONS.forEach(c -> list.add(new ConfigOptionWrapper(c)));
            case RENDER -> Configs.RENDER_OPTIONS.forEach(c -> list.add(new ConfigOptionWrapper(c)));
            case HOTKEYS -> Hotkeys.HOTKEY_LIST.forEach(h -> list.add(new ConfigOptionWrapper(h)));
        }
        return list;
    }

    public enum Tab { FEATURE, LOGISTICS, DATA, FILTER, TOOLS, RENDER, HOTKEYS }

    private static class ConfigListWidget extends WidgetListConfigOptions {
        private final GuiConfigs parent;

        public ConfigListWidget(int x, int y, int width, int height, int configWidth, float zLevel, boolean useKeybindSearch, GuiConfigs parent) {
            super(x, y, width, height, configWidth, zLevel, useKeybindSearch, parent);
            this.parent = parent;
        }

        @Override
        protected WidgetConfigOption createListEntryWidget(int x, int y, int listIndex, boolean isOdd, ConfigOptionWrapper entry) {
            if (entry.getConfig() == Hotkeys.CLEAR_MANUAL_OVERRIDES) {
                return new TriggerHotkeyConfigOption(x, y, this.browserEntryWidth, this.browserEntryHeight,
                        this.maxLabelWidth, this.configWidth, entry, listIndex, this.parent, this);
            }

            return super.createListEntryWidget(x, y, listIndex, isOdd, entry);
        }
    }

    private static class TriggerHotkeyConfigOption extends WidgetConfigOption {
        public TriggerHotkeyConfigOption(int x, int y, int width, int height, int maxNameWidth, int configWidth,
                                         ConfigOptionWrapper wrapper, int listIndex, IKeybindConfigGui host,
                                         WidgetListConfigOptionsBase<?, ?> parent) {
            super(x, y, width, height, maxNameWidth, configWidth, wrapper, listIndex, host, parent);
        }

        @Override
        protected void addHotkeyConfigElements(int x, int y, int width, String configName, fi.dy.masa.malilib.hotkeys.IHotkey hotkey) {
            fi.dy.masa.malilib.hotkeys.IKeybind keybind = hotkey.getKeybind();
            int configX = x;
            int configWidth = width;
            int resetX = configX + configWidth + 2;
            int settingsX = resetX - 22;
            int triggerWidth = (configWidth - 24) / 2;
            String label = fi.dy.masa.malilib.util.StringUtils.translate("litematica_container_filler.gui.button.trigger");
            ButtonGeneric trigger = new ButtonGeneric(x, y, triggerWidth, 20, label);
            this.addButton(trigger, (clickedButton, mouseButton) -> {
                int count = ManualContainerOverrideManager.clearAll();
                com.mimicenzymes.litematicafiller.render.HighlightScanner.onManualOverridesCleared();
                Minecraft client = Minecraft.getInstance();
                if (client.player != null) {
                    client.player.sendOverlayMessage(Component.translatable("litematica_container_filler.message.manual_override_cleared", count));
                }
            });

            x += triggerWidth + 2;
            width = settingsX - x - 2;

            ConfigButtonKeybind keyButton = new ConfigButtonKeybind(x, y, width, 20, keybind, this.host);

            this.addWidget(new WidgetKeybindSettings(settingsX, y, 20, 20, keybind, hotkey.getName(), this.parent, this.host.getDialogHandler()));

            this.addButton(keyButton, this.host.getButtonPressListener());
            this.addKeybindResetButton(resetX, y, keybind, keyButton);
        }
    }

    private record ButtonListener(Tab tab, GuiConfigs parent) implements IButtonActionListener {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            GuiConfigs.tab = this.tab;

            this.parent.reCreateListWidget();
            if (this.parent.getListWidget() != null) {
                this.parent.getListWidget().resetScrollbarPosition();
            }
            this.parent.initGui();
        }
    }
}
