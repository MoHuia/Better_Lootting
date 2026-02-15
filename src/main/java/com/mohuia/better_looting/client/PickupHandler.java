package com.mohuia.better_looting.client;

import net.minecraft.util.Mth;

/**
 * 拾取逻辑处理器.
 * <p>
 * 管理拾取操作的状态机，包括长按判定、自动拾取冷却以及
 * 区分“单次点击”与“长按批量”操作。
 */
public class PickupHandler {
    private static final int LONG_PRESS_THRESHOLD = 15;
    private static final int AUTO_COOLDOWN_MAX = 5;

    // --- 状态 ---
    private int pickupHoldTicks = 0;
    private boolean hasTriggeredBatch = false;
    private int autoPickupCooldown = 0;

    /**
     * 每 tick 更新按键状态.
     *
     * @param isKeyDown 拾取键是否被按下
     * @param hasItems 当前是否有可拾取的物品
     * @return 应该触发的操作类型 (NONE, SINGLE, BATCH)
     */
    public PickupAction tickInput(boolean isKeyDown, boolean isShiftDown, boolean hasItems) {
        // Shift 强制打断
        // 只要按下了 Shift 键，立即清空长按进度，并且绝对不返回任何拾取动作
        if (isShiftDown) {
            pickupHoldTicks = 0;
            hasTriggeredBatch = false;
            return PickupAction.NONE;
        }


        if (isKeyDown) {
            // 只有当有物品，或者已经处于批量模式时，才累加计时
            if (hasItems || hasTriggeredBatch) {
                pickupHoldTicks++;
            }

            // 达到长按阈值，且未触发过 -> 触发批量
            if (pickupHoldTicks >= LONG_PRESS_THRESHOLD && !hasTriggeredBatch) {
                if (hasItems) {
                    hasTriggeredBatch = true; // 锁定，防止重复触发
                    return PickupAction.BATCH;
                }
            }
        } else {
            // 按键松开时刻的判定
            PickupAction action = PickupAction.NONE;

            // 如果松开前有计时，且未达到长按标准，且未触发过批量 -> 视为单次点击
            if (pickupHoldTicks > 0 && pickupHoldTicks < LONG_PRESS_THRESHOLD && !hasTriggeredBatch && hasItems) {
                action = PickupAction.SINGLE;
            }


            // 重置状态
            pickupHoldTicks = 0;
            hasTriggeredBatch = false;
            return action;
        }
        return PickupAction.NONE;
    }

    /**
     * 检查是否可以执行自动拾取 (处理冷却).
     * @return true 如果可以执行拾取
     */
    public boolean canAutoPickup() {
        if (autoPickupCooldown > 0) {
            autoPickupCooldown--;
            return false;
        } else {
            autoPickupCooldown = AUTO_COOLDOWN_MAX;
            return true;
        }
    }

    /** 重置自动拾取冷却 */
    public void resetAutoCooldown() {
        this.autoPickupCooldown = 0;
    }

    /** 获取长按进度 (0.0 - 1.0) 用于渲染 */
    public float getProgress() {
        if (hasTriggeredBatch) return 0.0f;
        return Mth.clamp((float) pickupHoldTicks / LONG_PRESS_THRESHOLD, 0.0f, 1.0f);
    }

    /** 是否正在执行长按逻辑 (用于拦截) */
    public boolean isInteracting() {
        return hasTriggeredBatch || pickupHoldTicks > 0;
    }

    public enum PickupAction {
        NONE, SINGLE, BATCH
    }
}
