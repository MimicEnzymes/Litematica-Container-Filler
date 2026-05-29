package com.mimicenzymes.litematicafiller.config;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

public enum CarpetLargeBarrelMode implements IConfigOptionListEntry {
    OFF("off", "litematica_container_filler.config.option.carpetLargeBarrelMode.off"),
    ON("on", "litematica_container_filler.config.option.carpetLargeBarrelMode.on");

    private final String configString;
    private final String translationKey;

    CarpetLargeBarrelMode(String configString, String translationKey) {
        this.configString = configString;
        this.translationKey = translationKey;
    }

    @Override
    public String getStringValue() {
        return this.configString;
    }

    @Override
    public String getDisplayName() {
        return StringUtils.translate(this.translationKey);
    }

    @Override
    public IConfigOptionListEntry cycle(boolean forward) {
        CarpetLargeBarrelMode[] values = values();
        int index = this.ordinal() + (forward ? 1 : -1);
        if (index < 0) {
            index = values.length - 1;
        } else if (index >= values.length) {
            index = 0;
        }
        return values[index];
    }

    @Override
    public IConfigOptionListEntry fromString(String value) {
        for (CarpetLargeBarrelMode mode : values()) {
            if (mode.configString.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return OFF;
    }
}

