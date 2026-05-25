package com.mimicenzymes.litematicafiller.config;

import com.mimicenzymes.litematicafiller.gui.GuiConfigs;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return GuiConfigs::new;
    }
}
