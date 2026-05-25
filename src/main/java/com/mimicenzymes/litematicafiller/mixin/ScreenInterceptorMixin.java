package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.AutoFillerStateMachine;
import com.mimicenzymes.litematicafiller.tool.ContainerToolStateMachine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 鎷︽埅鍘熺増Minecraft瀹㈡埛绔殑灞忓箷娓叉煋
@Mixin(Minecraft.class)
public class ScreenInterceptorMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void interceptScreen(Screen screen, CallbackInfo ci) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        if (screen instanceof AbstractContainerScreen) {
            boolean shouldHideProjectionFillGui = Configs.HIDE_PROJECTION_FILL_GUI.getBooleanValue() &&
                    AutoFillerStateMachine.getInstance().shouldBlockScreens();
            boolean shouldHideToolGui = ContainerToolStateMachine.getInstance().shouldBlockScreens();
            if (shouldHideProjectionFillGui || shouldHideToolGui) {
                ci.cancel();
            }
        }
    }
}
