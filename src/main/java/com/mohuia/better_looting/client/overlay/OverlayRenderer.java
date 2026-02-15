package com.mohuia.better_looting.client.overlay;

import com.mohuia.better_looting.client.Constants;
import com.mohuia.better_looting.client.Core;
import com.mohuia.better_looting.client.Utils;
import com.mohuia.better_looting.client.VisualItemEntry;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Optional;

/**
 * 渲染器实现类.
 * <p>
 * 处理底层的绘制操作，包括物品行、滚动条、Tooltip 以及复杂的自定义几何图形（如圆角进度条）。
 */
public class OverlayRenderer {
    private final Minecraft mc;

    public OverlayRenderer(Minecraft mc) { this.mc = mc; }

    // --- 1. 顶部过滤器标签 ---

    public void renderFilterTabs(GuiGraphics gui, int x, int y) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        var mode = Core.INSTANCE.getFilterMode();
        // 绘制两个小方块表示 Tab 状态
        drawTab(gui, x, y, mode == Core.FilterMode.ALL, 0xFFFFFFFF);
        drawTab(gui, x + 9, y, mode == Core.FilterMode.RARE_ONLY, 0xFFFFD700);
    }

    private void drawTab(GuiGraphics gui, int x, int y, boolean active, int color) {
        int bg = active ? (color & 0x00FFFFFF) | 0x80000000 : 0x40000000; // 激活时半透明背景，否则更淡
        int border = active ? color : Utils.colorWithAlpha(color, 136);
        renderRoundedRect(gui, x, y - 8, 6, 6, bg);
        gui.renderOutline(x, y - 8, 6, 6, border);
    }

    // --- 2. 物品行渲染 ---

    public void renderItemRow(GuiGraphics gui, int x, int y, int width, VisualItemEntry entry, boolean selected, float bgAlpha, float textAlpha, boolean isNew) {
        ItemStack stack = entry.getItem(); // 获取用于显示的物品栈
        int count = entry.getCount();      // 获取真实的总数量

        int bgColor = selected ? Constants.COLOR_BG_SELECTED : Constants.COLOR_BG_NORMAL;
        renderRoundedRect(gui, x, y, width, Constants.ITEM_HEIGHT, Utils.applyAlpha(bgColor, bgAlpha));

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        int alpha255 = (int)(textAlpha * 255);

        // 绘制稀有度竖条 (左侧)
        gui.fill(x + 20, y + 3, x + 21, y + Constants.ITEM_HEIGHT - 3,
                Utils.colorWithAlpha(Utils.getItemStackDisplayColor(stack), alpha255));

        // 绘制物品图标
        gui.renderItem(stack, x + 3, y + 3);
        // 如果数量为 1，不显示文字
        // 如果数量 > 1，显示自定义文字（即支持 > 64 的数字）
        String countText = (count > 1) ? compactCount(count) : null;

        // 使用该重载方法，传入自定义 String 替代原版数量绘制
        gui.renderItemDecorations(mc.font, stack, x + 3, y + 3, countText);

        if (alpha255 <= 5) return; // 透明度过低不绘制文字

        var pose = gui.pose();
        int textColor = Utils.colorWithAlpha(selected ? Constants.COLOR_TEXT_WHITE : Constants.COLOR_TEXT_DIM, alpha255);

        // 绘制物品名称
        pose.pushPose();
        pose.translate(x + 26, y + 8, 0);
        pose.scale(0.75f, 0.75f, 1.0f);
        gui.drawString(mc.font, stack.getHoverName(), 0, 0, textColor, false);
        pose.popPose();

        // 绘制 "NEW" 标签 (针对背包中没有的物品)
        if (isNew) {
            pose.pushPose();
            pose.translate(x + width - 22, y + 8, 0);
            pose.scale(0.75f, 0.75f, 1.0f);
            gui.drawString(mc.font, "NEW", 0, 0, Utils.colorWithAlpha(Constants.COLOR_NEW_LABEL, alpha255), true);
            pose.popPose();
        }
    }

    /** 简单的数字格式化 */
    private String compactCount(int count) {
        if (count >= 10000) return (count / 1000) + "k"; // 10k+
        return String.valueOf(count);
    }

    // --- 3. 滚动条渲染 ---

    public void renderScrollBar(GuiGraphics gui, int total, float maxVis, int x, int y, int h, float alpha, float scroll) {
        // 背景轨道
        gui.fill(x, y, x + 2, y + h, Utils.applyAlpha(Constants.COLOR_SCROLL_TRACK, alpha));

        float ratio = maxVis / total;
        int thumbH = Math.max(10, (int) (h * ratio));
        // 计算滑块位置
        float progress = (total - maxVis > 0) ? Mth.clamp(scroll / (total - maxVis), 0f, 1f) : 0f;

        renderRoundedRect(gui, x, y + (int)((h - thumbH) * progress), 2, thumbH,
                Utils.applyAlpha(Constants.COLOR_SCROLL_THUMB, alpha));
    }

    // --- 4. 交互提示 (F键) ---

    public void renderKeyPrompt(GuiGraphics gui, int x, int startY, int itemHeight, int selIndex, float scroll, float visibleRows, float bgAlpha) {
        float relSel = selIndex - scroll;
        // 如果选中项在可视范围外，不绘制
        if (relSel <= -1.0f || relSel >= visibleRows + 0.5f) return;

        // 计算屏幕 Y 坐标
        int y = startY + (int) (relSel * itemHeight) + (itemHeight - 14) / 2;

        // 动态透明度：接近列表边缘时淡出
        float animAlpha = (relSel < 0 ? (1f + relSel) : Mth.clamp((visibleRows + 0.5f) - relSel, 0f, 1f));
        float finalAlpha = bgAlpha * animAlpha;

        if (finalAlpha <= 0.05f) return;

        int boxX = x - 21;
        int boxY = y;

        // 4.1 背景方块
        renderRoundedRect(gui, boxX, boxY, 14, 14, Utils.applyAlpha(Constants.COLOR_KEY_BG, finalAlpha));

        // 4.2 长按进度条 (圆角矩形描边)
        float pickupProgress = Core.INSTANCE.getPickupProgress();
        if (pickupProgress > 0.0f && pickupProgress < 1.0f) {
            int ringColor = Utils.colorWithAlpha(Constants.COLOR_TEXT_WHITE & 0x00FFFFFF, (int)(finalAlpha * 255));
            float inset = 2.0f;
            drawSmoothRoundedRectProgress(gui, boxX + inset, boxY + inset, 14.0f - inset * 2, 14.0f - inset * 2,
                    2.0f, 0.8f, pickupProgress, ringColor);
        }

        // 4.3 文字 "F"
        String text = "F";
        int tx = boxX + (14 - mc.font.width(text)) / 2;
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        gui.drawString(mc.font, text, tx, boxY + 3, Utils.colorWithAlpha(Constants.COLOR_TEXT_WHITE, (int)(finalAlpha * 255)), false);
    }

    // --- 5. 几何绘图核心 (Triangle Strip) ---

    /**
     * 绘制平滑圆角矩形进度条.
     * <p>
     * 原理：使用 TRIANGLE_STRIP 构建带宽度的线条（Ribbon）。
     * 需要计算每个顶点的法向量来挤出厚度。
     * * @param thickness 线条厚度
     * @param progress 0.0 - 1.0
     */
    private void drawSmoothRoundedRectProgress(GuiGraphics gui, float x, float y, float w, float h, float r, float thickness, float progress, int color) {
        if (progress <= 0.0f) return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        Matrix4f matrix = gui.pose().last().pose();

        buffer.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);

        // 颜色分解
        int a = (color >> 24) & 255;
        int red = (color >> 16) & 255;
        int g = (color >> 8) & 255;
        int b = (color & 255);

        float halfThick = thickness / 2.0f;
        r = Math.min(r, Math.min(w / 2f, h / 2f)); // 限制半径

        // 计算各段长度
        float straightW = w - 2 * r;
        float straightH = h - 2 * r;
        float arcLen = (float) (Math.PI * r / 2.0); // 90度圆弧长
        float totalLen = 2 * straightW + 2 * straightH + 4 * arcLen;
        float targetLen = totalLen * progress;

        float currentLen = 0;
        float centerX = x + w / 2.0f;

        // 按顺时针顺序绘制：上 -> 右上弯 -> 右 -> 右下弯 -> 下 -> 左下弯 -> 左 -> 左上弯 -> 闭合

        // 1. Top
        currentLen = drawStraightSegment(buffer, matrix, centerX, y, centerX + straightW / 2.0f, y, halfThick, currentLen, targetLen, red, g, b, a);
        // 2. Top-Right Corner
        if (currentLen < targetLen) currentLen = drawArcSegment(buffer, matrix, x + w - r, y + r, r, -90, 0, halfThick, currentLen, targetLen, red, g, b, a);
        // 3. Right
        if (currentLen < targetLen) currentLen = drawStraightSegment(buffer, matrix, x + w, y + r, x + w, y + r + straightH, halfThick, currentLen, targetLen, red, g, b, a);
        // 4. Bottom-Right Corner
        if (currentLen < targetLen) currentLen = drawArcSegment(buffer, matrix, x + w - r, y + h - r, r, 0, 90, halfThick, currentLen, targetLen, red, g, b, a);
        // 5. Bottom
        if (currentLen < targetLen) currentLen = drawStraightSegment(buffer, matrix, x + w - r, y + h, x + r, y + h, halfThick, currentLen, targetLen, red, g, b, a);
        // 6. Bottom-Left Corner
        if (currentLen < targetLen) currentLen = drawArcSegment(buffer, matrix, x + r, y + h - r, r, 90, 180, halfThick, currentLen, targetLen, red, g, b, a);
        // 7. Left
        if (currentLen < targetLen) currentLen = drawStraightSegment(buffer, matrix, x, y + h - r, x, y + r, halfThick, currentLen, targetLen, red, g, b, a);
        // 8. Top-Left Corner
        if (currentLen < targetLen) currentLen = drawArcSegment(buffer, matrix, x + r, y + r, r, 180, 270, halfThick, currentLen, targetLen, red, g, b, a);
        // 9. Top Closure (回到底部中心)
        if (currentLen < targetLen) drawStraightSegment(buffer, matrix, x + r, y, centerX, y, halfThick, currentLen, targetLen, red, g, b, a);

        BufferUploader.drawWithShader(buffer.end());
        RenderSystem.disableBlend();
    }

    /** 绘制直线段，自动处理厚度挤出 */
    private float drawStraightSegment(BufferBuilder buffer, Matrix4f matrix, float x1, float y1, float x2, float y2, float halfThick, float currentLen, float targetLen, int r, int g, int b, int a) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= 0.0001f) return currentLen;

        // 如果超出目标长度，截断
        float drawLen = len;
        if (currentLen + len > targetLen) drawLen = targetLen - currentLen;

        float ratio = drawLen / len;
        float endX = x1 + dx * ratio;
        float endY = y1 + dy * ratio;

        // 计算法向量 (垂直于路径方向)，用于生成宽度
        // 假设顺时针绘制，内侧向量为 (-dy, dx) 的归一化
        float nxIn = -dy / len;
        float nyIn = dx / len;

        // 如果是整个路径的起点，需要先推入两个顶点来启动 Strip
        if (currentLen == 0) {
            // Vertex Order: [Outer, Inner]
            buffer.vertex(matrix, x1 - nxIn * halfThick, y1 - nyIn * halfThick, 0f).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, x1 + nxIn * halfThick, y1 + nyIn * halfThick, 0f).color(r, g, b, a).endVertex();
        }

        // 添加终点的两个顶点
        buffer.vertex(matrix, endX - nxIn * halfThick, endY - nyIn * halfThick, 0f).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, endX + nxIn * halfThick, endY + nyIn * halfThick, 0f).color(r, g, b, a).endVertex();

        return currentLen + drawLen;
    }

    /** 绘制圆弧段 */
    private float drawArcSegment(BufferBuilder buffer, Matrix4f matrix, float cx, float cy, float r, float startAngleDeg, float endAngleDeg, float halfThick, float currentLen, float targetLen, int red, int green, int blue, int a) {
        float startRad = (float) Math.toRadians(startAngleDeg);
        float endRad = (float) Math.toRadians(endAngleDeg);
        float totalRad = Math.abs(endRad - startRad);
        float arcLen = totalRad * r;

        float drawArcLen = arcLen;
        if (currentLen + arcLen > targetLen) drawArcLen = targetLen - currentLen;

        // 根据弧长动态决定分段数，保证平滑
        int segments = Math.max(4, (int) (drawArcLen / 2.0f));
        float actualEndRad = startRad + (endRad - startRad) * (drawArcLen / arcLen);

        for (int i = 1; i <= segments; i++) {
            float ratio = (float) i / segments;
            float rad = startRad + (actualEndRad - startRad) * ratio;
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);

            // Outer Vertex
            buffer.vertex(matrix, cx + cos * (r + halfThick), cy + sin * (r + halfThick), 0f).color(red, green, blue, a).endVertex();
            // Inner Vertex
            buffer.vertex(matrix, cx + cos * (r - halfThick), cy + sin * (r - halfThick), 0f).color(red, green, blue, a).endVertex();
        }
        return currentLen + drawArcLen;
    }

    // --- Tooltip 渲染 ---

    public void renderTooltip(GuiGraphics gui, ItemStack stack, int screenW, int screenH, OverlayLayout layout, float currentScroll, int selIndex) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        // 获取 Tooltip 文本行
        List<Component> lines = stack.getTooltipLines(mc.player, mc.options.advancedItemTooltips ? TooltipFlag.Default.ADVANCED : TooltipFlag.Default.NORMAL);
        if (lines.isEmpty()) return;

        // 简单的智能定位逻辑：优先显示在列表右侧，不够位置则显示在左侧
        // ... (省略复杂的计算逻辑注释，直接保留功能) ...
        int maxTextWidth = 0;
        for (Component line : lines) {
            int w = mc.font.width(line);
            if (w > maxTextWidth) maxTextWidth = w;
        }

        // 估算宽高
        int tooltipWidthEst = maxTextWidth + 24;
        int tooltipHeightEst = lines.size() * 10 + 12;

        // 计算物品在屏幕上的 Y 坐标
        int listRightEdge = (int) (layout.baseX + layout.slideOffset + (layout.panelWidth + Constants.LIST_X) * layout.finalScale);
        int listLeftEdge = (int) (layout.baseX + layout.slideOffset + Constants.LIST_X * layout.finalScale);
        float itemRelativeY = selIndex - currentScroll;
        int localItemY = layout.startY + (int) (itemRelativeY * layout.itemHeightTotal);
        int screenItemY = (int) (layout.baseY + (localItemY + layout.itemHeightTotal / 2.0f) * layout.finalScale);

        // 限制垂直位置不超出屏幕
        int desiredTop = screenItemY - (tooltipHeightEst / 2);
        if (desiredTop < 4) desiredTop = 4;
        else if (desiredTop + tooltipHeightEst > screenH - 4) desiredTop = screenH - 4 - tooltipHeightEst;

        // 决定左右位置
        int gap = 12;
        int desiredLeft;
        if (listRightEdge + gap + tooltipWidthEst < screenW - 4) {
            desiredLeft = listRightEdge + gap; // 右侧
        } else {
            desiredLeft = listLeftEdge - gap - tooltipWidthEst; // 左侧
            if (desiredLeft < 4) desiredLeft = 4;
        }

        // 渲染原版 Tooltip
        gui.renderTooltip(mc.font, lines, Optional.empty(), desiredLeft - 12, desiredTop + 12); // -12/+12 是为了抵消 renderTooltip 内部的鼠标偏移假设
    }

    private void renderRoundedRect(GuiGraphics gui, int x, int y, int w, int h, int color) {
        gui.fill(x + 1, y, x + w - 1, y + h, color);
        gui.fill(x, y + 1, x + w, y + h - 1, color);
    }
}
