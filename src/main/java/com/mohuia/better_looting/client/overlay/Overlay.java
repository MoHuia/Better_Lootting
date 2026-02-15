package com.mohuia.better_looting.client.overlay;

import com.mohuia.better_looting.client.*;
import com.mohuia.better_looting.config.Config;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * HUD 覆盖层主控制器.
 * <p>
 * 负责协调 HUD 的显示逻辑、生命周期管理以及渲染调度。
 * 它是 Model (OverlayState), View (OverlayRenderer) 和 Layout 的粘合剂。
 */
public class Overlay {

    private final OverlayState state = new OverlayState();
    private OverlayRenderer renderer;

    /** 仅在 KEY_TOGGLE 模式下使用，记录当前的开关状态 */
    private boolean isOverlayToggled = false;

    public Overlay() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * 客户端逻辑 Tick.
     * 主要用于处理按键的 "Toggle" (切换) 逻辑，确保单次按下只触发一次状态翻转。
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (Config.CLIENT.activationMode.get() == Config.ActivationMode.KEY_TOGGLE) {
            while (KeyInit.SHOW_OVERLAY.consumeClick()) {
                isOverlayToggled = !isOverlayToggled;
            }
        }
    }

    /**
     * HUD 渲染事件入口.
     * 所有的绘制逻辑由此开始。
     */
    @SubscribeEvent
    public void onRenderGui(RenderGuiOverlayEvent.Post event) {
        // 仅在原版快捷栏渲染后绘制，保证层级正确
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return; // 打开 GUI 时不显示

        Core core = Core.INSTANCE;
        var nearbyItems = core.getNearbyItems();
        if (nearbyItems == null) return;

        if (this.renderer == null) this.renderer = new OverlayRenderer(mc);

        // --- 1. 激活条件判断 ---
        boolean conditionMet = checkActivationCondition(mc);

        // 综合判断：有物品 + 非自动模式 + 满足激活条件
        boolean shouldShow = !nearbyItems.isEmpty() && !core.isAutoMode() && conditionMet;

        // --- 2. 状态更新与布局计算 ---

        // 即使不显示，也需要更新状态(tick)以便处理淡出动画
        // 预计算布局参数 (Layout)
        OverlayLayout layout = new OverlayLayout(mc, state.popupProgress);

        // 更新动画状态 (弹窗进度、滚动位置)
        state.tick(shouldShow, core.getTargetScrollOffset(), nearbyItems.size(), layout.visibleRows);

        // 性能优化：如果完全透明且无需显示，直接跳过渲染
        if (state.popupProgress < 0.001f) return;

        // 定期清理未使用的动画缓存 (每秒一次)
        if (mc.level != null && mc.level.getGameTime() % 20 == 0) {
            state.cleanupAnimations(nearbyItems);
        }

        // --- 3. 执行渲染 ---

        GuiGraphics gui = event.getGuiGraphics();
        var pose = gui.pose();

        pose.pushPose();
        // 应用整体位移 (包含滑入滑出动画 offset) 和缩放
        pose.translate(layout.baseX + layout.slideOffset, layout.baseY, 0);
        pose.scale(layout.finalScale, layout.finalScale, 1.0f);

        // 3.1 绘制标题栏
        if (state.popupProgress > 0.1f) {
            drawHeader(gui, layout);
        }

        // 3.2 绘制物品列表
        // 计算渲染范围，只渲染可见区域内的物品 (Culling)
        int startIdx = Mth.floor(state.currentScroll);
        int endIdx = Mth.ceil(state.currentScroll + layout.visibleRows);

        boolean renderPrompt = false;
        float selectedBgAlpha = 0f;

        // 开启严格裁剪 (防止物品溢出列表框)
        layout.applyStrictScissor();

        for (int i = 0; i < nearbyItems.size(); i++) {
            // 视锥剔除：跳过不可见的行
            if (i < startIdx - 1 || i > endIdx + 1) continue;

            VisualItemEntry entry = nearbyItems.get(i); // 获取 VisualItemEntry
            boolean isSelected = (i == core.getSelectedIndex());

            // 获取单个物品的进入动画进度
            float entryProgress = state.getItemEntryProgress(entry.getPrimaryId());
            // 计算滑入位移 (0 -> 1 的过程对应 50 -> 0 的像素位移)
            float entryOffset = (1.0f - Utils.easeOutCubic(entryProgress)) * 50.0f;

            // 计算列表两端的淡出 Alpha
            float itemAlpha = calculateListEdgeAlpha(i - state.currentScroll, layout.visibleRows);

            if (itemAlpha * state.popupProgress <= 0.05f) continue;

            pose.pushPose();
            pose.translate(entryOffset, 0, 0);

            float finalBgAlpha = itemAlpha * state.popupProgress * layout.globalAlpha;
            float finalTextAlpha = itemAlpha * state.popupProgress;

            // 计算Y坐标：相对于列表顶部的偏移
            int y = layout.startY + (int) ((i - state.currentScroll) * layout.itemHeightTotal);

            // 绘制行
            renderer.renderItemRow(gui, Constants.LIST_X, y, layout.panelWidth, entry,
                    isSelected, finalBgAlpha, finalTextAlpha, !core.isItemInInventory(entry.getItem().getItem()));

            if (isSelected) {
                renderPrompt = true;
                selectedBgAlpha = finalBgAlpha;
            }
            pose.popPose();
        }

        // 3.3 绘制交互提示 (F键)
        // 使用宽松裁剪 (允许进度条稍微超出一点格子)
        if (renderPrompt) {
            layout.applyLooseScissor();
            renderer.renderKeyPrompt(gui, Constants.LIST_X, layout.startY, layout.itemHeightTotal,
                    core.getSelectedIndex(), state.currentScroll, layout.visibleRows, selectedBgAlpha);
        }

        RenderSystem.disableScissor();

        // 3.4 绘制滚动条
        if (nearbyItems.size() > layout.visibleRows) {
            int totalVisualH = (int) (layout.visibleRows * layout.itemHeightTotal);
            renderer.renderScrollBar(gui, nearbyItems.size(), layout.visibleRows,
                    Constants.LIST_X - 6, layout.startY, totalVisualH,
                    state.popupProgress * layout.globalAlpha, state.currentScroll);
        }

        pose.popPose();

        // 3.5 绘制 Tooltip (悬浮提示)
        // Tooltip 必须在 popPose 之后绘制，以免被缩放或裁剪影响
        if (state.popupProgress > 0.9f && !nearbyItems.isEmpty()) {
            int sel = core.getSelectedIndex();
            if (sel >= 0 && sel < nearbyItems.size()) {
                var stack = nearbyItems.get(sel).getItem();
                if (Utils.shouldShowTooltip(stack)) {
                    renderer.renderTooltip(gui, stack, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(),
                            layout, state.currentScroll, sel);
                }
            }
        }
    }

    /** 检查是否满足 Config 中定义的激活模式 */
    private boolean checkActivationCondition(Minecraft mc) {
        var mode = Config.CLIENT.activationMode.get();
        switch (mode) {
            case LOOK_DOWN:
                return mc.player != null && mc.player.getXRot() > Config.CLIENT.lookDownAngle.get();
            case STAND_STILL:
                if (mc.player == null) return false;
                double dx = mc.player.getX() - mc.player.xo;
                double dz = mc.player.getZ() - mc.player.zo;
                return (dx * dx + dz * dz) < 0.0001;
            case KEY_HOLD:
                return !KeyInit.SHOW_OVERLAY.isUnbound() && KeyInit.SHOW_OVERLAY.isDown();
            case KEY_TOGGLE:
                return isOverlayToggled;
            case ALWAYS:
            default:
                return true;
        }
    }

    private void drawHeader(GuiGraphics gui, OverlayLayout layout) {
        int headerY = layout.startY - 14;
        int titleAlpha = (int)(state.popupProgress * layout.globalAlpha * 255);

        var pose = gui.pose();
        pose.pushPose();
        pose.translate(Constants.LIST_X, headerY, 0);
        pose.scale(0.75f, 0.75f, 1.0f);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        gui.drawString(Minecraft.getInstance().font, "LOOT DETECTED", 0, 0, Utils.colorWithAlpha(0xFFFFD700, titleAlpha), true);
        pose.popPose();

        // 分割线
        int lineColor = Utils.colorWithAlpha(0xFFAAAAAA, (int)(titleAlpha * 0.5));
        gui.fill(Constants.LIST_X, headerY + 10, Constants.LIST_X + layout.panelWidth, headerY + 11, lineColor);

        // 过滤模式指示器 (Tab)
        renderer.renderFilterTabs(gui, Constants.LIST_X + layout.panelWidth - 20, headerY + 10);
    }

    /** 计算列表顶部和底部的边缘淡出效果 (Fade out) */
    private float calculateListEdgeAlpha(float relativeIndex, float visibleRows) {
        // 顶部边缘淡出
        if (relativeIndex < 0) return Mth.clamp(1.0f + (relativeIndex * 1.5f), 0f, 1f);
        // 底部边缘淡出
        if (relativeIndex > visibleRows - 1.0f) return Mth.clamp(1.0f - (relativeIndex - (visibleRows - 1.0f)), 0f, 1f);
        return 1.0f;
    }
}
