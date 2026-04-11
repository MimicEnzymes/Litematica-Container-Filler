package com.mimicenzymes.litematicafiller.config;

import com.mimicenzymes.litematicafiller.LitematicafillerClient;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;

public class GuiConfigs extends GuiConfigsBase {
    private ConfigGuiTab tab = ConfigGuiTab.FEATURE;

    public GuiConfigs(Screen parent) {
        super(10, 50, LitematicafillerClient.MOD_ID, parent, "litematica_container_filler.gui.title.configs");
    }

    @Override
    public void initGui() {
        super.initGui();
        this.clearOptions();
        int x = 10;
        for (ConfigGuiTab t : ConfigGuiTab.values()) {
            String tabName = fi.dy.masa.malilib.util.StringUtils.translate("litematica_container_filler.gui.button." + t.name().toLowerCase());
            ButtonGeneric b = new ButtonGeneric(x, 26, -1, 20, tabName);
            b.setEnabled(this.tab != t);
            this.addButton(b, (button, mouseButton) -> { this.tab = t; this.initGui(); });
            x += b.getWidth() + 2;
        }
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<ConfigOptionWrapper> list = new ArrayList<>();
        if (this.tab == ConfigGuiTab.FEATURE) {
            Configs.OPTIONS.forEach(c -> list.add(new ConfigOptionWrapper(c)));
        } else {
            Hotkeys.HOTKEY_LIST.forEach(h -> list.add(new ConfigOptionWrapper((IHotkey) h)));
        }
        return list;
    }

    public enum ConfigGuiTab { FEATURE, HOTKEYS }
}