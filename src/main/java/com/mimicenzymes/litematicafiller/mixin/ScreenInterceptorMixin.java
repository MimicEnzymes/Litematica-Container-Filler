package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.core.AutoFillerStateMachine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 拦截原版Minecraft客户端的屏幕渲染
@Mixin(Minecraft.class)
public class ScreenInterceptorMixin {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void interceptScreen(Screen screen, CallbackInfo ci) {
        if (screen instanceof AbstractContainerScreen && AutoFillerStateMachine.getInstance().isSilentlyExtracting()) {
            ci.cancel();
        }
    }
}