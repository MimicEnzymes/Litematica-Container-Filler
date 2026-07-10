package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.render.ToolHudRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public class InGameHudMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void litematicaContainerFiller$renderToolHud(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (!Configs.ENABLE_MOD.getBooleanValue() ||
                client.gui.hud.isHidden() ||
                (!Configs.ENABLE_TOOL_HUD.getBooleanValue() && !ToolHudRenderer.hasActiveTextSwitchHud())) {
            return;
        }
        ToolHudRenderer.render(context);
    }
}
