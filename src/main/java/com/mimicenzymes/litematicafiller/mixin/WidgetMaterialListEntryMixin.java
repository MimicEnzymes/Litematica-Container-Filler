package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.materials.FillMaterialCalculator;
import fi.dy.masa.litematica.gui.widgets.WidgetMaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = WidgetMaterialListEntry.class, remap = false)
public abstract class WidgetMaterialListEntryMixin {
    @Shadow @Final private MaterialListEntry entry;

    @Shadow protected abstract int getColumnPosX(int column);

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void lcf$layoutReplacementButtons(DrawContext drawContext, int mouseX, int mouseY, boolean selected, CallbackInfo ci) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        if (this.entry == null) return;

        WidgetMaterialListEntry self = (WidgetMaterialListEntry) (Object) this;
        this.lcf$layoutMaterialListActionButtons(self);
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void lcf$addIgnoreButtonTooltip(int x, int y, int width, int height, boolean isOdd,
                                            fi.dy.masa.litematica.materials.MaterialListBase materialList,
                                            fi.dy.masa.litematica.materials.MaterialListEntry entry,
                                            int listIndex,
                                            fi.dy.masa.litematica.gui.widgets.WidgetListMaterialList listWidget,
                                            CallbackInfo ci) {
        if (entry == null) return;

        try {
            for (fi.dy.masa.malilib.gui.widgets.WidgetBase widget : ((WidgetContainerInvoker) this).lcf$getSubWidgets()) {
                if (widget instanceof ButtonBase button) {
                    String label = ((ButtonBaseAccessor) button).lcf$getDisplayString();
                    String ignore = StringUtils.translate("litematica.gui.button.material_list.ignore");
                    if (ignore.equals(label)) {
                        button.setHoverStrings(StringUtils.translate("litematica_container_filler.gui.tooltip.material_list_ignore"));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void lcf$drawReplacementMarker(DrawContext drawContext, int mouseX, int mouseY, boolean selected, CallbackInfo ci) {
        if (this.entry == null || this.entry.getStack().isEmpty()) return;
        if (!FillMaterialCalculator.isReplacementDisplay(this.entry.getStack())) return;

        WidgetMaterialListEntry self = (WidgetMaterialListEntry) (Object) this;
        int markerX = this.lcf$getReplacementMarkerX(self);
        int markerY = this.lcf$getReplacementMarkerY(self);

        RenderUtils.drawRect(drawContext, markerX - 2, markerY - 1, 12, 11, 0xAA245C87);
        RenderUtils.drawRect(drawContext, markerX - 1, markerY, 10, 9, 0xCC36A3FF);
        drawContext.drawText(MinecraftClient.getInstance().textRenderer, "R", markerX + 1, markerY, 0xFFFFFFFF, false);
    }

    @Inject(method = "postRenderHovered", at = @At("TAIL"), require = 0)
    private void lcf$drawReplacementMarkerTooltip(DrawContext drawContext, int mouseX, int mouseY, boolean selected, CallbackInfo ci) {
        if (this.entry == null || this.entry.getStack().isEmpty()) return;
        if (!FillMaterialCalculator.isReplacementDisplay(this.entry.getStack())) return;

        WidgetMaterialListEntry self = (WidgetMaterialListEntry) (Object) this;
        if (!this.lcf$isMouseOverReplacementMarker(self, mouseX, mouseY)) return;

        List<String> lines = new ArrayList<>();
        lines.add(StringUtils.translate("litematica_container_filler.gui.tooltip.material_replace_marker"));

        ItemStack original = FillMaterialCalculator.getOriginalReplacementSource(this.entry.getStack());
        if (!original.isEmpty()) {
            lines.add(StringUtils.translate("litematica_container_filler.gui.tooltip.material_replace_marker_source",
                    original.getName().getString()));
        }

        this.lcf$drawReplacementInfoBelowMaterialTooltip(drawContext, mouseX, mouseY, lines);
    }

    private int lcf$getReplacementMarkerX(WidgetMaterialListEntry self) {
        return Math.min(this.getColumnPosX(4) - 16, self.getX() + self.getWidth() - 72);
    }

    private int lcf$getReplacementMarkerY(WidgetMaterialListEntry self) {
        return self.getY() + ((self.getHeight() - 11) >> 1);
    }

    private boolean lcf$isMouseOverReplacementMarker(WidgetMaterialListEntry self, int mouseX, int mouseY) {
        int markerX = this.lcf$getReplacementMarkerX(self);
        int markerY = this.lcf$getReplacementMarkerY(self);
        return mouseX >= markerX - 2 && mouseX < markerX + 10 && mouseY >= markerY - 1 && mouseY < markerY + 10;
    }

    private void lcf$layoutMaterialListActionButtons(WidgetMaterialListEntry self) {
        ButtonBase ignoreButton = null;
        ButtonBase ownReplaceButton = null;
        List<ButtonBase> externalReplaceButtons = new ArrayList<>();

        try {
            for (fi.dy.masa.malilib.gui.widgets.WidgetBase widget : ((WidgetContainerInvoker) self).lcf$getSubWidgets()) {
                if (!(widget instanceof ButtonBase button)) continue;
                if (button.getY() >= self.getY() + self.getHeight()) continue;

                String label = ((ButtonBaseAccessor) button).lcf$getDisplayString();
                if (this.lcf$isIgnoreButton(label)) {
                    ignoreButton = button;
                } else if (this.lcf$isOwnReplaceButton(button, label)) {
                    ownReplaceButton = button;
                } else if (this.lcf$isReplacementLikeButton(label)) {
                    externalReplaceButtons.add(button);
                }
            }
        } catch (Throwable ignored) {
            return;
        }

        if (ignoreButton == null || (ownReplaceButton == null && externalReplaceButtons.isEmpty())) return;

        int y = self.getY() + ((self.getHeight() - 20) >> 1);
        ignoreButton.setY(y);

        List<ButtonBase> orderedButtons = new ArrayList<>();
        if (ownReplaceButton != null) {
            orderedButtons.add(ownReplaceButton);
        }
        externalReplaceButtons.sort((a, b) -> Integer.compare(a.getX(), b.getX()));
        orderedButtons.addAll(externalReplaceButtons);
        if (orderedButtons.isEmpty()) return;

        int totalWidth = -4;
        for (ButtonBase button : orderedButtons) {
            totalWidth += button.getWidth() + 4;
        }

        // Left to right: this mod's Replace, SchematicPreview's Replace, Litematica's Ignore.
        int x = Math.max(self.getX() + 180, ignoreButton.getX() - 4 - totalWidth);
        for (ButtonBase button : orderedButtons) {
            button.setPosition(x, y);
            x += button.getWidth() + 4;
        }
    }

    private boolean lcf$isIgnoreButton(String label) {
        return StringUtils.translate("litematica.gui.button.material_list.ignore").equals(label);
    }

    private boolean lcf$isOwnReplaceButton(ButtonBase button, String label) {
        if (!StringUtils.translate("litematica_container_filler.gui.button.material_replace").equals(label)) return false;

        String marker = StringUtils.translate("litematica_container_filler.gui.tooltip.material_replace_button");
        return button.getHoverStrings().contains(marker);
    }

    private boolean lcf$isReplacementLikeButton(String label) {
        if (label == null) return false;

        String own = StringUtils.translate("litematica_container_filler.gui.button.material_replace");
        return own.equals(label) || "Replace".equalsIgnoreCase(label) || "替换".equals(label);
    }

    private void lcf$drawReplacementInfoBelowMaterialTooltip(DrawContext drawContext, int mouseX, int mouseY, List<String> lines) {
        MinecraftClient client = MinecraftClient.getInstance();
        int textWidth = 0;
        for (String line : lines) {
            textWidth = Math.max(textWidth, client.textRenderer.getWidth(line));
        }

        int boxWidth = textWidth + 12;
        int boxHeight = lines.size() * 11 + 9;
        int boxX = mouseX + 10;
        int boxY = mouseY + 54;
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        if (boxX + boxWidth > screenWidth - 4) {
            boxX = Math.max(4, screenWidth - boxWidth - 4);
        }
        if (boxY + boxHeight > screenHeight - 4) {
            boxY = Math.max(4, mouseY - 10 - boxHeight - 4);
        }

        RenderUtils.drawOutlinedBox(drawContext, boxX, boxY, boxWidth, boxHeight, 0xF0100018, 0xFF7F4BCB);

        int textY = boxY + 5;
        for (String line : lines) {
            drawContext.drawText(client.textRenderer, line, boxX + 6, textY, 0xFFE8D7FF, false);
            textY += 11;
        }
    }
}
