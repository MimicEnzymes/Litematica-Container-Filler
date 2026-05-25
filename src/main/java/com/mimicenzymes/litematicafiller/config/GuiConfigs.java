package com.mimicenzymes.litematicafiller.config;

import com.mimicenzymes.litematicafiller.Reference;
import net.minecraft.client.Minecraft;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

public class GuiConfigs extends GuiConfigsBase {
    private static Tab tab = Tab.FEATURE;

    public GuiConfigs(Screen parent) {
        super(10, 50, Reference.MOD_ID, parent, "litematica_container_filler.gui.title.configs");
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
            this.addButton(botton, new ButtonListener(tab, this));
            x += botton.getWidth() + 2;
        }
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<ConfigOptionWrapper> list = new ArrayList<>();
        if (tab == Tab.FEATURE) {
            Configs.OPTIONS.forEach(c -> list.add(new ConfigOptionWrapper(c)));
        } else {
            Hotkeys.HOTKEY_LIST.forEach(h -> list.add(new ConfigOptionWrapper(h)));
        }
        return list;
    }

    public enum Tab { FEATURE, HOTKEYS }

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
