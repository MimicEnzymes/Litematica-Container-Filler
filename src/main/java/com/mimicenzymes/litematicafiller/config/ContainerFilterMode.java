package com.mimicenzymes.litematicafiller.config;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

public enum ContainerFilterMode implements IConfigOptionListEntry {
    DISABLED("disabled", "litematica_container_filler.config.option.containerFilterMode.disabled"),
    WHITELIST("whitelist", "litematica_container_filler.config.option.containerFilterMode.whitelist"),
    BLACKLIST("blacklist", "litematica_container_filler.config.option.containerFilterMode.blacklist");

    private final String configString;
    private final String translationKey;

    ContainerFilterMode(String configString, String translationKey) {
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
        ContainerFilterMode[] values = values();
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
        for (ContainerFilterMode mode : values()) {
            if (mode.configString.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return DISABLED;
    }
}
