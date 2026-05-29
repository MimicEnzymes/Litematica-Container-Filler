package com.mimicenzymes.litematicafiller.gui;

import com.mimicenzymes.litematicafiller.Reference;
import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.config.Hotkeys;
import com.mimicenzymes.litematicafiller.core.ManualContainerOverrideManager;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigResettable;
import fi.dy.masa.malilib.config.IConfigStringList;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ConfigButtonStringList;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.interfaces.IConfigInfoProvider;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetKeybindSettings;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptionsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ConfigButtonKeybind;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import net.minecraft.text.Text;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import java.util.ArrayList;
import java.util.List;

public class GuiConfigs extends GuiConfigsBase {
    private static Tab tab = Tab.FEATURE;

    public GuiConfigs(Screen parent) {
        super(10, 74, Reference.MOD_ID, parent, "litematica_container_filler.gui.title.configs");
    }

    public GuiConfigs() {
        this(MinecraftClient.getInstance().currentScreen);
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

        if (tab == Tab.RENDER) {
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
            if (entry.getConfig() == Configs.MATERIAL_REPLACEMENTS) {
                return new GlobalMaterialReplacementConfigOption(x, y, this.browserEntryWidth, this.browserEntryHeight,
                        this.maxLabelWidth, this.configWidth, entry, listIndex, this.parent, this);
            }
            if (entry.getConfig() == Configs.CONTAINER_FILTER_LIST) {
                return new ContainerFilterListConfigOption(x, y, this.browserEntryWidth, this.browserEntryHeight,
                        this.maxLabelWidth, this.configWidth, entry, listIndex, this.parent, this);
            }

            return super.createListEntryWidget(x, y, listIndex, isOdd, entry);
        }
    }

    private static class ContainerFilterListConfigOption extends WidgetConfigOption {
        public ContainerFilterListConfigOption(int x, int y, int width, int height, int maxNameWidth, int configWidth,
                                               ConfigOptionWrapper wrapper, int listIndex, IKeybindConfigGui host,
                                               WidgetListConfigOptionsBase<?, ?> parent) {
            super(x, y, width, height, maxNameWidth, configWidth, wrapper, listIndex, host, parent);
        }

        @Override
        protected void addConfigOption(int x, int y, int labelWidth, int configWidth, IConfigBase config) {
            if (config != Configs.CONTAINER_FILTER_LIST) {
                super.addConfigOption(x, y, labelWidth, configWidth, config);
                return;
            }

            String displayName = config.getConfigGuiDisplayName();
            this.addLabel(x, y + 7, labelWidth, 8, -1, displayName);

            IConfigInfoProvider hoverInfoProvider = this.host.getHoverInfoProvider();
            String comment = hoverInfoProvider != null ? hoverInfoProvider.getHoverInfo(config) : config.getComment();
            if (comment != null) {
                this.addConfigComment(x, y + 5, labelWidth, 12, comment);
            }

            int buttonX = x + labelWidth + 10;
            int gap = 4;
            String visualText = fi.dy.masa.malilib.util.StringUtils.translate("litematica_container_filler.gui.button.container_filter_visual");
            int visualWidth = Math.max(68, this.getStringWidth(visualText) + 12);
            visualWidth = Math.min(visualWidth, Math.max(68, configWidth - 56));
            int rawWidth = Math.max(48, configWidth - visualWidth - gap);

            ButtonGeneric visualButton = new ButtonGeneric(buttonX, y, visualWidth, 20, visualText);
            visualButton.setHoverStrings(fi.dy.masa.malilib.util.StringUtils.translate("litematica_container_filler.gui.tooltip.container_filter_visual"));
            this.addButton(visualButton, (clickedButton, mouseButton) -> {
                Screen screen = this.host instanceof Screen hostScreen ? hostScreen : MinecraftClient.getInstance().currentScreen;
                GuiBase.openGui(new GuiContainerFilter(screen));
            });

            ConfigButtonStringList rawButton = new ConfigButtonStringList(
                    buttonX + visualWidth + gap,
                    y,
                    rawWidth,
                    20,
                    (IConfigStringList) config,
                    this.host,
                    this.host.getDialogHandler()
            );
            this.addConfigButtonEntry(buttonX + visualWidth + gap + rawWidth + 2, y, (IConfigResettable) config, rawButton);
        }
    }

    private static class GlobalMaterialReplacementConfigOption extends WidgetConfigOption {
        public GlobalMaterialReplacementConfigOption(int x, int y, int width, int height, int maxNameWidth, int configWidth,
                                                     ConfigOptionWrapper wrapper, int listIndex, IKeybindConfigGui host,
                                                     WidgetListConfigOptionsBase<?, ?> parent) {
            super(x, y, width, height, maxNameWidth, configWidth, wrapper, listIndex, host, parent);
        }

        @Override
        protected void addConfigOption(int x, int y, int labelWidth, int configWidth, IConfigBase config) {
            if (config != Configs.MATERIAL_REPLACEMENTS) {
                super.addConfigOption(x, y, labelWidth, configWidth, config);
                return;
            }

            String displayName = config.getConfigGuiDisplayName();
            this.addLabel(x, y + 7, labelWidth, 8, -1, displayName);

            IConfigInfoProvider hoverInfoProvider = this.host.getHoverInfoProvider();
            String comment = hoverInfoProvider != null ? hoverInfoProvider.getHoverInfo(config) : config.getComment();
            if (comment != null) {
                this.addConfigComment(x, y + 5, labelWidth, 12, comment);
            }

            int buttonX = x + labelWidth + 10;
            int gap = 4;
            String visualText = fi.dy.masa.malilib.util.StringUtils.translate("litematica_container_filler.gui.button.global_material_replace_visual");
            int visualWidth = Math.max(68, this.getStringWidth(visualText) + 12);
            visualWidth = Math.min(visualWidth, Math.max(68, configWidth - 56));
            int rawWidth = Math.max(48, configWidth - visualWidth - gap);

            ButtonGeneric visualButton = new ButtonGeneric(buttonX, y, visualWidth, 20, visualText);
            visualButton.setHoverStrings(fi.dy.masa.malilib.util.StringUtils.translate("litematica_container_filler.gui.tooltip.global_material_replace_visual"));
            this.addButton(visualButton, (clickedButton, mouseButton) -> {
                Screen screen = this.host instanceof Screen hostScreen ? hostScreen : MinecraftClient.getInstance().currentScreen;
                GuiBase.openGui(new GuiGlobalMaterialReplacementPicker(screen));
            });

            ConfigButtonStringList rawButton = new ConfigButtonStringList(
                    buttonX + visualWidth + gap,
                    y,
                    rawWidth,
                    20,
                    (IConfigStringList) config,
                    this.host,
                    this.host.getDialogHandler()
            );
            this.addConfigButtonEntry(buttonX + visualWidth + gap + rawWidth + 2, y, (IConfigResettable) config, rawButton);
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
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.sendMessage(Text.translatable("litematica_container_filler.message.manual_override_cleared", count), true);
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
