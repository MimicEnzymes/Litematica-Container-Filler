package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.render.ToolHudRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void litematicaContainerFiller$renderToolHud(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        ToolHudRenderer.render(context);
    }
}
