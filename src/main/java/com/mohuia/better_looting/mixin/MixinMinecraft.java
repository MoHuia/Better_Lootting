package com.mohuia.better_looting.mixin;

import com.mohuia.better_looting.client.Core;
import com.mohuia.better_looting.client.KeyInit;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {

    @Shadow @Final public Options options;

    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void betterLooting$interceptKeybinds(CallbackInfo ci) {
        // 只有当周围有可拾取物品时才进行拦截
        // 只有当周围有可拾取物品时才进行拦截
        if (Core.shouldIntercept()) {

            // 1. 消耗掉模组自身的 PICKUP 按键点击
            // (防止原版其他可能绑定到同键位的功能触发，但 Core.java 依然可以通过 isDown() 读取状态)
            while (KeyInit.PICKUP.consumeClick()) {
                // Do nothing
            }

            // --- 冲突处理 ---
            // 检查副手交换键，如果它和 PICKUP 是同一个键 (默认都是 F)
            KeyMapping swapKey = this.options.keySwapOffhand;
            if (swapKey.same(KeyInit.PICKUP)) {

                // 只有当玩家没有按住 Shift 时，才拦截副手交换
                if (!Screen.hasShiftDown()) {
                    while (swapKey.consumeClick()) {
                        // 吃掉点击事件，阻止原版副手交换
                    }
                }
                // 如果按住了 Shift，则跳过上面的循环，原版逻辑会随后读取到 swapKey 的点击并执行交换
            }
        }
    }
}
