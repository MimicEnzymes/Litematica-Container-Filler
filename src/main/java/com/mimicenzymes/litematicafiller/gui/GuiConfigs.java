package com.mimicenzymes.litematicafiller.gui;

import com.mimicenzymes.litematicafiller.Reference;
import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.config.Hotkeys;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
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

