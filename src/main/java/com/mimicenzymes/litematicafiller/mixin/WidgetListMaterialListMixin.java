package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.MaterialReplacementUi;
import com.mimicenzymes.litematicafiller.materials.FillMaterialCalculator;
import com.mimicenzymes.litematicafiller.render.HighlightScanner;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.gui.widgets.WidgetListMaterialList;
import fi.dy.masa.litematica.gui.widgets.WidgetMaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = WidgetListMaterialList.class, remap = false, priority = 900)
public abstract class WidgetListMaterialListMixin {
    @Shadow @Final private GuiMaterialList gui;

    @Inject(method = "createListEntryWidget(IIIZLfi/dy/masa/litematica/materials/MaterialListEntry;)Lfi/dy/masa/litematica/gui/widgets/WidgetMaterialListEntry;",
            at = @At("TAIL"),
            require = 0)
    private void lcf$addMaterialReplacementButton(int x, int y, int listIndex, boolean isOdd, MaterialListEntry materialEntry,
                                                  CallbackInfoReturnable<WidgetMaterialListEntry> cir) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        if (FillMaterialCalculator.listMode == 0) return;
        if (materialEntry == null || materialEntry.getStack().isEmpty()) return;
        if (!FillMaterialCalculator.isContainerMaterial(materialEntry.getStack())) return;

        WidgetMaterialListEntry entryWidget = cir.getReturnValue();
        if (entryWidget == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        ItemStack displayStack = materialEntry.getStack().copy();
        displayStack.setCount(1);

        String locateLabel = StringUtils.translate("litematica_container_filler.gui.button.material_locate");
        int locateWidth = Math.max(42, client.textRenderer.getWidth(locateLabel) + 8);
        int locateX = this.lcf$getAdaptiveButtonX(entryWidget, locateWidth);
        int buttonY = entryWidget.getY() + ((entryWidget.getHeight() - 20) >> 1);
        ButtonGeneric locateButton = new ButtonGeneric(locateX, buttonY, locateWidth, 20, locateLabel);
        locateButton.setHoverStrings(StringUtils.translate("litematica_container_filler.gui.tooltip.material_locate_button"));
        locateButton.setActionListener((clickedButton, mouseButton) -> {
            int count = HighlightScanner.focusMaterialContainers(displayStack, MinecraftClient.getInstance());
            if (MinecraftClient.getInstance().player != null) {
                String key = count > 0
                        ? "litematica_container_filler.message.material_locate_found"
                        : "litematica_container_filler.message.material_locate_none";
                MinecraftClient.getInstance().player.sendMessage(Text.translatable(key, count), true);
            }
        });
        ((WidgetContainerInvoker) entryWidget).lcf$addWidget(locateButton);

        ItemStack sourceStack = FillMaterialCalculator.getOriginalReplacementSource(materialEntry.getStack());
        if (sourceStack.isEmpty()) return;

        String label = StringUtils.translate("litematica_container_filler.gui.button.material_replace");
        int width = Math.max(42, client.textRenderer.getWidth(label) + 8);
        int buttonX = this.lcf$getAdaptiveButtonX(entryWidget, width);
        ButtonGeneric button = new ButtonGeneric(buttonX, buttonY, width, 20, label);
        button.setHoverStrings(StringUtils.translate("litematica_container_filler.gui.tooltip.material_replace_button"));
        button.setActionListener((clickedButton, mouseButton) -> MaterialReplacementUi.open(this.gui, sourceStack));
        ((WidgetContainerInvoker) entryWidget).lcf$addWidget(button);
    }

    private int lcf$getAdaptiveButtonX(WidgetMaterialListEntry entryWidget, int width) {
        int minRightButtonX = entryWidget.getX() + entryWidget.getWidth();

        try {
            List<WidgetBase> widgets = ((WidgetContainerInvoker) entryWidget).lcf$getSubWidgets();
            for (WidgetBase widget : widgets) {
                if (widget instanceof ButtonBase && widget.getY() < entryWidget.getY() + entryWidget.getHeight()) {
                    minRightButtonX = Math.min(minRightButtonX, widget.getX());
                }
            }
        } catch (Throwable ignored) {
        }

        int x = minRightButtonX - width - 4;
        int leftLimit = entryWidget.getX() + 180;
        if (x < leftLimit) {
            x = leftLimit;
        }
        return x;
    }
}
