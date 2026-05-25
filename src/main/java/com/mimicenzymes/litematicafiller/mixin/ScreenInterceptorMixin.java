package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.AutoFillerStateMachine;
import com.mimicenzymes.litematicafiller.core.ContainerToolStateMachine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 拦截原版Minecraft客户端的屏幕渲染
@Mixin(MinecraftClient.class)
public class ScreenInterceptorMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void interceptScreen(Screen screen, CallbackInfo ci) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        if (screen instanceof HandledScreen) {
            boolean shouldHideProjectionFillGui = Configs.HIDE_PROJECTION_FILL_GUI.getBooleanValue() &&
                    AutoFillerStateMachine.getInstance().shouldBlockScreens();
            boolean shouldHideToolGui = ContainerToolStateMachine.getInstance().shouldBlockScreens();
            if (shouldHideProjectionFillGui || shouldHideToolGui) {
                ci.cancel();
            }
        }
    }
}
