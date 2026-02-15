package com.mohuia.better_looting.client.overlay;

import com.mohuia.better_looting.client.VisualItemEntry;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import java.util.*;
import java.util.stream.Collectors;

/**
 * HUD 动画状态容器.
 * <p>
 * 独立管理所有随时间变化的数值 (如弹窗弹出进度、滚动条位置、物品进入动画)。
 * 这里的计算与渲染帧率解耦 (Frame-Rate Independent)。
 */
public class OverlayState {
    /** 当前滚动条位置 (浮点数，用于平滑滚动) */
    public float currentScroll = 0f;
    /** 弹窗整体弹出进度 (0.0 = 隐藏, 1.0 = 完全显示) */
    public float popupProgress = 0f;

    // 缓存每个物品实体的进入动画进度 (EntityID -> Progress 0.0~1.0)
    private final Map<Integer, Float> itemEntryAnimations = new HashMap<>();

    // 时间追踪 (纳秒)
    private long lastFrameTime = -1;
    private float deltaTime = 0f;

    /**
     * 更新每一帧的状态.
     * * @param shouldShow 目标状态：是否应该显示
     * @param targetScroll 目标状态：Core 计算出的理想滚动位置
     */
    public void tick(boolean shouldShow, float targetScroll, int itemCount, float visibleRows) {
        // 计算 Delta Time (秒)
        long now = System.nanoTime();
        if (lastFrameTime == -1) {
            lastFrameTime = now;
        }

        // 限制最大 dt 为 0.1s，防止游戏卡顿后动画瞬间跳变
        this.deltaTime = (float) ((now - lastFrameTime) / 1_000_000_000.0);
        this.deltaTime = Math.min(this.deltaTime, 0.1f);
        this.lastFrameTime = now;

        // 1. 弹窗显示/隐藏动画 (弹簧阻尼效果)
        float targetPopup = shouldShow ? 1.0f : 0.0f;
        // speed = 10.0f，意味着响应较快
        this.popupProgress = damp(this.popupProgress, targetPopup, 10.0f, deltaTime);

        // 如果完全隐藏，清理缓存以节省内存
        if (!shouldShow && this.popupProgress < 0.001f) {
            this.popupProgress = 0f;
            this.itemEntryAnimations.clear();
        }

        // 2. 滚动条平滑跟随
        float maxScroll = Math.max(0, itemCount - visibleRows);
        float clampedTarget = Mth.clamp(targetScroll, 0, maxScroll);

        // 如果差异极小，直接吸附，避免微小抖动造成的模糊
        if (Math.abs(this.currentScroll - clampedTarget) < 0.001f) {
            this.currentScroll = clampedTarget;
        } else {
            // speed = 15.0f，滚动跟随比弹窗更灵敏
            this.currentScroll = damp(this.currentScroll, clampedTarget, 15.0f, deltaTime);
        }
    }

    /**
     * 获取单个物品的进入动画进度.
     * 如果是新物品，从 0 开始增加；如果是旧物品，保持 1.
     */
    public float getItemEntryProgress(int entityId) {
        return itemEntryAnimations.compute(entityId, (k, v) -> {
            float val = (v == null) ? 0f : v;
            if (val >= 1.0f) return 1.0f;

            // 线性增加：从 0 到 1 需要约 0.16 秒 (1.0 / 6.0)
            float next = val + (6.0f * deltaTime);
            return Math.min(1.0f, next);
        });
    }

    /** 清理已经不存在的实体的动画状态 */
    public void cleanupAnimations(List<VisualItemEntry> currentItems) {
        Set<Integer> currentIds = currentItems.stream()
                .map(VisualItemEntry::getPrimaryId)
                .collect(Collectors.toSet());
        itemEntryAnimations.keySet().retainAll(currentIds);
    }

    /**
     * 帧率独立的平滑阻尼函数 (Asymptotic Smoothing).
     * 公式: lerp(current, target, 1 - exp(-speed * dt))
     * * @param speed 响应速度，值越大越快
     */
    private float damp(float current, float target, float speed, float dt) {
        return Mth.lerp(1.0f - (float)Math.exp(-speed * dt), current, target);
    }
}
