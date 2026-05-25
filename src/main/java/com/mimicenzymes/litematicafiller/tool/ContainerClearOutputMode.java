package com.mimicenzymes.litematicafiller.tool;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

public enum ContainerClearOutputMode implements IConfigOptionListEntry {
    DROP("drop", "litematica_container_filler.config.option.containerClearOutputMode.drop"),
    INVENTORY("inventory", "litematica_container_filler.config.option.containerClearOutputMode.inventory");

    private final String configString;
    private final String translationKey;

    ContainerClearOutputMode(String configString, String translationKey) {
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
        ContainerClearOutputMode[] values = values();
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
        for (ContainerClearOutputMode mode : values()) {
            if (mode.configString.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return DROP;
    }
}
