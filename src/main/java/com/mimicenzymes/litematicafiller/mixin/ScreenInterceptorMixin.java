package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.AutoFillerStateMachine;
import com.mimicenzymes.litematicafiller.tool.ContainerToolStateMachine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class ScreenInterceptorMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void interceptScreen(Screen screen, CallbackInfo ci) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) {
            return;
        }

        if (screen instanceof AbstractContainerScreen<?>) {
            Screen currentScreen = ((Gui)(Object)this).screen();
            boolean shouldHideProjectionFillGui = Configs.HIDE_PROJECTION_FILL_GUI.getBooleanValue() &&
                    AutoFillerStateMachine.getInstance().shouldBlockScreens();
            boolean shouldHideToolGui = ContainerToolStateMachine.getInstance().shouldBlockScreens();
            boolean shouldPreservePassiveScreen = currentScreen != null &&
                    !(currentScreen instanceof AbstractContainerScreen<?>) &&
                    (AutoFillerStateMachine.getInstance().isWorking() || ContainerToolStateMachine.getInstance().isWorking());
            if (shouldHideProjectionFillGui || shouldHideToolGui || shouldPreservePassiveScreen) {
                ci.cancel();
            }
        }
    }
}
