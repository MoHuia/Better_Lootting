package com.mohuia.better_looting.config;

import com.mohuia.better_looting.BetterLooting;
import com.mohuia.better_looting.client.Constants;
import com.mohuia.better_looting.client.VisualItemEntry; // [新增 Import]
import com.mohuia.better_looting.client.overlay.OverlayRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.client.gui.widget.ForgeSlider;

import java.util.ArrayList;
import java.util.List;

/**
 * 可视化配置编辑屏幕。
 */
public class ConfigScreen extends Screen {

    private final ConfigViewModel viewModel;
    private final DragController dragController;
    private OverlayRenderer renderer;

    // [修改] 类型改为 List<VisualItemEntry>
    private final List<VisualItemEntry> previewItems = new ArrayList<>();

    // 当前预览框的边界（用于鼠标交互检测）
    private float boxLeft, boxTop, boxRight, boxBottom;

    public ConfigScreen() {
        this(new ConfigViewModel());
    }

    public ConfigScreen(ConfigViewModel existingModel) {
        super(Component.translatable("gui." + BetterLooting.MODID + ".config.title"));
        this.viewModel = existingModel;
        this.dragController = new DragController();

        // [修改] 使用 VisualItemEntry 初始化预览数据
        previewItems.add(new VisualItemEntry(new ItemStack(Items.DIAMOND, 1)));
        previewItems.add(new VisualItemEntry(new ItemStack(Items.GOLDEN_APPLE, 1)));
        previewItems.add(new VisualItemEntry(new ItemStack(Items.IRON_SWORD, 1)));
        previewItems.add(new VisualItemEntry(new ItemStack(Items.EMERALD, 64)));
        previewItems.add(new VisualItemEntry(new ItemStack(Items.BOOK, 1)));
    }

    @Override
    protected void init() {
        if (this.renderer == null) this.renderer = new OverlayRenderer(this.minecraft);

        int cx = this.width / 2;
        int bottomBase = this.height - 30;

        // 1. 设置按钮
        this.addRenderableWidget(Button.builder(Component.literal("⚙"), b ->
                        this.minecraft.setScreen(new ConditionsScreen(this, this.viewModel)))
                .bounds(this.width - 30, 10, 20, 20)
                .tooltip(Tooltip.create(Component.translatable("gui." + BetterLooting.MODID + ".config.tooltip.settings")))
                .build());

        // 2. 透明度滑块
        this.addRenderableWidget(new ForgeSlider(
                cx - 100, bottomBase - 25, 200, 20,
                Component.translatable("gui." + BetterLooting.MODID + ".config.opacity").append(": "), Component.empty(),
                0.1, 1.0, viewModel.globalAlpha, 0.05, 1, true
        ) {
            @Override protected void applyValue() { viewModel.globalAlpha = this.getValue(); }
        });

        // 3. 重置按钮
        this.addRenderableWidget(Button.builder(Component.translatable("gui." + BetterLooting.MODID + ".config.reset"), b -> {
            viewModel.resetToDefault();
            this.rebuildWidgets();
        }).bounds(cx - 105, bottomBase, 100, 20).build());

        // 4. 保存按钮
        this.addRenderableWidget(Button.builder(Component.translatable("gui." + BetterLooting.MODID + ".config.save"), b -> {
            viewModel.saveToConfig();
            this.onClose();
        }).bounds(cx + 5, bottomBase, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gui);
        renderGrid(gui);
        renderPreview(gui, mouseX, mouseY);
        renderInfoOverlay(gui);
        super.render(gui, mouseX, mouseY, partialTick);
    }

    private void renderInfoOverlay(GuiGraphics gui) {
        gui.fill(5, 5, 130, 80, 0x80000000);
        int startX = 10, startY = 10, lineHeight = 10;
        int color = 0xFFAAAAAA;

        gui.drawString(this.font,
                Component.translatable("gui." + BetterLooting.MODID + ".config.info.pos",
                        (int)viewModel.xOffset, (int)viewModel.yOffset),
                startX, startY, color, false);

        gui.drawString(this.font,
                Component.translatable("gui." + BetterLooting.MODID + ".config.info.size",
                        viewModel.panelWidth, String.format("%.1f", viewModel.visibleRows)),
                startX, (int)(startY + lineHeight * 1.5f), color, false);

        gui.drawString(this.font,
                Component.translatable("gui." + BetterLooting.MODID + ".config.info.scale",
                        String.format("%.2f", viewModel.uiScale)),
                startX, (int)(startY + lineHeight * 3.0f), color, false);

        Component label = Component.translatable("gui." + BetterLooting.MODID + ".config.header_condition");
        Component modeName = Component.translatable("gui." + BetterLooting.MODID + ".config.mode." + viewModel.activationMode.name().toLowerCase());
        gui.drawString(this.font, label.copy().append(": ").append(modeName),
                startX, (int)(startY + lineHeight * 4.5f), 0xFFDDDDDD, false);
    }

    private void renderPreview(GuiGraphics gui, int mouseX, int mouseY) {
        var bounds = viewModel.calculatePreviewBounds(this.width, this.height);
        this.boxLeft = bounds.left();
        this.boxTop = bounds.top();
        this.boxRight = bounds.right();
        this.boxBottom = bounds.bottom();

        dragController.updateBounds(boxLeft, boxTop, boxRight, boxBottom);

        float baseX = (float) (this.width / 2.0f + viewModel.xOffset);
        float baseY = (float) (this.height / 2.0f + viewModel.yOffset);
        float scale = (float) viewModel.uiScale;

        PoseStack pose = gui.pose();
        pose.pushPose();
        pose.translate(baseX, baseY, 0);
        pose.scale(scale, scale, 1.0f);

        int alphaInt = (int) (viewModel.globalAlpha * 255);
        int renderAlpha = Math.max(alphaInt, 20);
        int headerColor = (0x00FFAA00 & 0x00FFFFFF) | (renderAlpha << 24);
        int lineColor = (0x00AAAAAA & 0x00FFFFFF) | ((int)(renderAlpha * 0.5f) << 24);

        float localMinY = -(Constants.ITEM_HEIGHT / 2.0f) - 14;
        pose.pushPose();
        pose.translate(Constants.LIST_X, localMinY, 0);
        pose.scale(0.75f, 0.75f, 1.0f);
        gui.drawString(this.font, Component.translatable("gui." + BetterLooting.MODID + ".config.preview_title"), 0, 0, headerColor, true);
        pose.popPose();

        gui.fill(Constants.LIST_X, (int)localMinY + 10, Constants.LIST_X + viewModel.panelWidth, (int)localMinY + 11, lineColor);

        gui.enableScissor(0, (int)Math.max(0, boxTop), this.width, (int)Math.min(this.height, boxBottom));

        int startY = -(Constants.ITEM_HEIGHT / 2);
        for (int i = 0; i < previewItems.size(); i++) {
            int y = startY + (i * (Constants.ITEM_HEIGHT + 2));
            // 这里的 previewItems.get(i) 现在是 VisualItemEntry 类型，匹配参数签名
            renderer.renderItemRow(gui, Constants.LIST_X, y, viewModel.panelWidth, previewItems.get(i), i==0, (float)viewModel.globalAlpha, 1.0f, i==1);
        }

        gui.disableScissor();
        pose.popPose();

        drawControlHandles(gui, mouseX, mouseY);
    }

    private void drawControlHandles(GuiGraphics gui, int mouseX, int mouseY) {
        DragController.DragMode mode = dragController.getCurrentDragMode();
        int borderCol = (mode == DragController.DragMode.MOVE) ? 0xFFFFFFFF : 0x60FFFFFF;
        gui.renderOutline((int)boxLeft, (int)boxTop, (int)(boxRight-boxLeft), (int)(boxBottom-boxTop), borderCol);

        int cR = dragController.isOverRight(mouseX, mouseY) ? 0xFF55FF55 : 0x8055FF55;
        gui.fill((int)boxRight, (int)boxTop, (int)boxRight+4, (int)boxBottom, cR);

        int cB = dragController.isOverBottom(mouseX, mouseY) ? 0xFF5555FF : 0x805555FF;
        gui.fill((int)boxLeft, (int)boxBottom, (int)boxRight, (int)boxBottom+4, cB);

        int cC = dragController.isOverCorner(mouseX, mouseY) ? 0xFFFF5555 : 0x80FF5555;
        gui.fill((int)boxRight-2, (int)boxBottom-2, (int)boxRight+6, (int)boxBottom+6, cC);
    }

    private void renderGrid(GuiGraphics gui) {
        int color = 0x20FFFFFF;
        for (int x = 0; x < this.width; x += 20) gui.fill(x, 0, x + 1, this.height, color);
        for (int y = 0; y < this.height; y += 20) gui.fill(0, y, this.width, y + 1, color);
        gui.fill(this.width / 2, 0, this.width / 2 + 1, this.height, 0x40FF0000);
        gui.fill(0, this.height / 2, this.width, this.height / 2 + 1, 0x40FF0000);
    }

    @Override public boolean mouseClicked(double x, double y, int btn) {
        return (btn == 0 && dragController.onMouseClicked(x, y, viewModel)) || super.mouseClicked(x, y, btn);
    }
    @Override public boolean mouseReleased(double x, double y, int btn) {
        return dragController.onMouseReleased() || super.mouseReleased(x, y, btn);
    }
    @Override public boolean mouseDragged(double x, double y, int btn, double dx, double dy) {
        dragController.onMouseDragged(x, y, viewModel);
        return super.mouseDragged(x, y, btn, dx, dy);
    }
}
