package com.mimicenzymes.litematicafiller.mixin;

import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.gui.widgets.WidgetContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(value = WidgetContainer.class, remap = false)
public interface WidgetContainerInvoker {
    @Invoker("addWidget")
    <T extends WidgetBase> T lcf$addWidget(T widget);

    @Accessor("subWidgets")
    List<WidgetBase> lcf$getSubWidgets();
}
