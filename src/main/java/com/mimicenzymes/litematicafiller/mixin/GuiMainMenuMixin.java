package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.GuiConfigs;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.util.StringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.List;

@Mixin(value = GuiMainMenu.class, remap = false)
public abstract class GuiMainMenuMixin extends GuiBase {

    @Invoker(remap = false)
    public abstract int callGetButtonWidth();

    @Inject(method = "initGui", at = @At("RETURN"), remap = false)
    public void initGui(final CallbackInfo ci) {
        final int width = callGetButtonWidth();
        final int baseX = width + 12 + 20;
        int baseY = 30 + 22;

        // 持续向下移动直到没有冲突
        while (isPositionOccupied(baseX, baseY)) {
            baseY += 22;
        }

        String btnText = StringUtils.translate("litematica_container_filler.gui.title.configs");
        ButtonGeneric button = new ButtonGeneric(baseX, baseY, width, 20, btnText);
        button.setEnabled(true);

        // 内联监听器
        this.addButton(button, new IButtonActionListener() {
            @Override
            public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
                GuiBase.openGui(new GuiConfigs((GuiMainMenu)(Object)GuiMainMenuMixin.this));
            }
        });
    }

    /**
     * 检查位置是否被其他按钮占用
     */
    @Unique
    private boolean isPositionOccupied(int x, int y) {
        List<?> buttons = getButtons();
        if (buttons == null || buttons.isEmpty()) {
            return false;
        }

        for (Object button : buttons) {
            int btnX = getButtonX(button);
            int btnY = getButtonY(button);

            // 如果Y坐标相近（±15像素），且X坐标重叠（±50像素）
            if (Math.abs(btnY - y) < 15 && Math.abs(btnX - x) < 50) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取按钮的X坐标
     */
    @Unique
    private int getButtonX(Object button) {
        try {
            Method getX = button.getClass().getMethod("getX");
            return (int) getX.invoke(button);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 获取按钮的Y坐标
     */
    @Unique
    private int getButtonY(Object button) {
        try {
            Method getY = button.getClass().getMethod("getY");
            return (int) getY.invoke(button);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 从GuiBase获取所有按钮列表
     */
    @Unique
    private List<?> getButtons() {
        try {
            for (java.lang.reflect.Field field : GuiBase.class.getDeclaredFields()) {
                if (field.getType() == java.util.List.class &&
                        field.getName().toLowerCase().contains("button")) {
                    field.setAccessible(true);
                    return (List<?>) field.get(this);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}