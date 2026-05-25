package com.mimicenzymes.litematicafiller.config;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

public enum ToolHudStyle implements IConfigOptionListEntry {
    FIXED_CARD("fixed_card", "litematica_container_filler.config.option.toolHudStyle.fixedCard"),
    ANCHORED_CARD("anchored_card", "litematica_container_filler.config.option.toolHudStyle.anchoredCard");

    private final String configString;
    private final String translationKey;

    ToolHudStyle(String configString, String translationKey) {
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
        ToolHudStyle[] values = values();
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
        for (ToolHudStyle style : values()) {
            if (style.configString.equalsIgnoreCase(value)) {
                return style;
            }
        }
        return FIXED_CARD;
    }
}
