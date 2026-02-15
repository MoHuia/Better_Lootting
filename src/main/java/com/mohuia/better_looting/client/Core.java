package com.mohuia.better_looting.client;

import com.mohuia.better_looting.client.filter.FilterWhitelist;
import com.mohuia.better_looting.config.Config;
import com.mohuia.better_looting.config.ConfigScreen;
import com.mohuia.better_looting.network.NetworkHandler;
import com.mohuia.better_looting.network.C2S.PacketBatchPickup;
import com.mohuia.better_looting.network.C2S.PacketPickupItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 客户端核心控制器 (Refactored).
 * <p>
 * 作为 MVC 模式中的 Controller/Model 混合体：
 * 1. 维护状态 (Model): 物品列表、选中索引、过滤模式。
 * 2. 响应事件 (Controller): 委托 LootScanner 进行计算，委托 PickupHandler 处理输入。
 */
public class Core {
    public static final Core INSTANCE = new Core();

    public enum FilterMode { ALL, RARE_ONLY }

    // --- 助手模块 ---
    private final PickupHandler pickupHandler = new PickupHandler();

    // --- 核心数据 ---
    private List<ItemEntity> nearbyItems = new ArrayList<>();
    private final Set<Item> cachedInventoryItems = new HashSet<>();

    // --- UI 状态 ---
    private int selectedIndex = 0;
    private int targetScrollOffset = 0;

    // --- 配置与控制 ---
    private FilterMode filterMode = FilterMode.ALL;
    private boolean isAutoMode = false;
    private int tickCounter = 0;
    private int scrollKeyHoldTime = 0; // 简单的辅助滚动计时，保留在此处即可

    private Core() {
        MinecraftForge.EVENT_BUS.register(this);
        FilterWhitelist.INSTANCE.init();
    }

    // --- Public API (供 HUD 渲染调用) ---

    public List<ItemEntity> getNearbyItems() { return nearbyItems; }
    public int getSelectedIndex() { return selectedIndex; }
    public int getTargetScrollOffset() { return targetScrollOffset; }
    public boolean hasItems() { return !nearbyItems.isEmpty(); }
    public FilterMode getFilterMode() { return filterMode; }
    public boolean isAutoMode() { return isAutoMode; }
    public boolean isItemInInventory(Item item) { return cachedInventoryItems.contains(item); }

    public float getPickupProgress() {
        return hasItems() ? pickupHandler.getProgress() : 0.0f;
    }

    public static boolean shouldIntercept() {
        return INSTANCE.hasItems() || INSTANCE.pickupHandler.isInteracting();
    }

    // --- 事件循环 ---

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // 1. 基础检查
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            nearbyItems.clear();
            cachedInventoryItems.clear();
            return;
        }
        if (mc.screen instanceof ConfigScreen) return;

        // 2. 更新数据 (利用 LootScanner)
        tickCounter++;
        if (tickCounter % 10 == 0) updateInventoryCache(mc);

        this.nearbyItems = LootScanner.scan(mc, this.filterMode);

        // 3. 处理自动拾取
        if (isAutoMode && !nearbyItems.isEmpty()) {
            if (pickupHandler.canAutoPickup()) {
                sendBatchPickup(nearbyItems, true);
            }
        } else {
            pickupHandler.resetAutoCooldown();
        }

        // 4. 更新 UI 索引 (保证索引不越界)
        validateSelection();

        // 5. 处理玩家输入 (利用 PickupHandler)
        handleInputLogic();
    }

    private void handleInputLogic() {
        // A. 功能键处理
        while (KeyInit.TOGGLE_FILTER.consumeClick()) toggleFilterMode();
        while (KeyInit.OPEN_CONFIG.consumeClick()) Minecraft.getInstance().setScreen(new ConfigScreen());
        while (KeyInit.TOGGLE_AUTO.consumeClick()) toggleAutoMode();

        // B. 拾取键处理 (委托给 Handler)
        PickupHandler.PickupAction action = pickupHandler.tickInput(KeyInit.PICKUP.isDown(), !nearbyItems.isEmpty());
        switch (action) {
            case SINGLE:
                sendSinglePickup();
                break;
            case BATCH:
                sendBatchPickup(nearbyItems, false);
                break;
            default:
                break;
        }

        // C. 键盘滚动处理
        handleKeyboardScroll();
    }

    // --- 滚动与选择逻辑 ---

    @SubscribeEvent
    public void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (shouldIgnoreScroll()) return;

        double scrollDelta = event.getScrollDelta();
        if (scrollDelta != 0) {
            performScroll(scrollDelta);
            event.setCanceled(true);
        }
    }

    private boolean shouldIgnoreScroll() {
        if (Minecraft.getInstance().screen instanceof ConfigScreen) return true;
        if (nearbyItems.size() <= 1) return true;
        if (Screen.hasShiftDown()) return true;

        Config.ScrollMode mode = Config.CLIENT.scrollMode.get();
        if (mode == Config.ScrollMode.ALWAYS) return false;
        if (mode == Config.ScrollMode.KEY_BIND) return !KeyInit.SCROLL_MODIFIER.isDown();
        if (mode == Config.ScrollMode.STAND_STILL) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return true;
            double dx = mc.player.getX() - mc.player.xo;
            double dz = mc.player.getZ() - mc.player.zo;
            return (dx * dx + dz * dz) >= 0.0001;
        }
        return true;
    }

    private void performScroll(double delta) {
        if (nearbyItems.size() <= 1) return;
        selectedIndex += (delta > 0) ? -1 : 1;
        validateSelection(); // 所有的循环和越界逻辑都在这里统一处理
    }

    private void handleKeyboardScroll() {
        boolean up = KeyInit.SCROLL_UP.isDown();
        boolean down = KeyInit.SCROLL_DOWN.isDown();
        if (up || down) {
            scrollKeyHoldTime++;
            if (scrollKeyHoldTime == 1 || (scrollKeyHoldTime > 10 && scrollKeyHoldTime % 3 == 0)) {
                performScroll(up ? 1.0 : -1.0);
            }
        } else {
            scrollKeyHoldTime = 0;
        }
    }

    /** 统一处理索引越界、循环滚动和 ScrollOffset 计算 */
    private void validateSelection() {
        if (nearbyItems.isEmpty()) {
            selectedIndex = 0;
            targetScrollOffset = 0;
            return;
        }

        // 循环滚动逻辑
        if (selectedIndex < 0) selectedIndex = nearbyItems.size() - 1;
        if (selectedIndex >= nearbyItems.size()) selectedIndex = 0;

        // 计算 Offset
        // 1. 获取 double 类型的值，保留小数 (例如 3.5)
        double visibleRows = Config.CLIENT.visibleRows.get();
        if (visibleRows < 1.0) visibleRows = 1.0;

        // 如果物品总数比可见行数还少，不需要滚动
        if (nearbyItems.size() <= visibleRows) {
            targetScrollOffset = 0;
            return;
        }

        // 2. 向下滚动判断
        // 如果选中的索引超出了当前 "Offset + 可见行数" 的范围
        if (selectedIndex >= targetScrollOffset + visibleRows) {
            // 计算新的 Offset，通过 (int) 强制转换去掉小数部分
            // 逻辑：让选中的物品出现在列表的最底部
            targetScrollOffset = (int) (selectedIndex - visibleRows + 1);
        }

        // 3. 向上滚动判断 (逻辑不变，依然是比较整数)
        if (selectedIndex < targetScrollOffset) {
            targetScrollOffset = selectedIndex;
        }

        // 4. 边界钳制 (Clamp)
        // 计算最大允许的滚动偏移量，防止底部留白太多
        int maxOffset = (int) Math.max(0, nearbyItems.size() - visibleRows);
        targetScrollOffset = Math.max(0, Math.min(targetScrollOffset, maxOffset));
    }

    // --- 网络与辅助 ---

    private void sendSinglePickup() {
        if (selectedIndex >= 0 && selectedIndex < nearbyItems.size()) {
            ItemEntity target = nearbyItems.get(selectedIndex);
            if (target.isAlive()) {
                NetworkHandler.sendToServer(new PacketPickupItem(target.getId()));
            }
        }
    }

    private void sendBatchPickup(List<ItemEntity> entities, boolean isAuto) {
        List<Integer> ids = entities.stream()
                .filter(ItemEntity::isAlive)
                .map(ItemEntity::getId)
                .collect(Collectors.toList());
        if (!ids.isEmpty()) {
            NetworkHandler.sendToServer(new PacketBatchPickup(ids, isAuto));
        }
    }

    private void updateInventoryCache(Minecraft mc) {
        cachedInventoryItems.clear();
        if (mc.player == null) return;
        mc.player.getInventory().items.forEach(s -> { if(!s.isEmpty()) cachedInventoryItems.add(s.getItem()); });
        mc.player.getInventory().offhand.forEach(s -> { if(!s.isEmpty()) cachedInventoryItems.add(s.getItem()); });
        mc.player.getInventory().armor.forEach(s -> { if(!s.isEmpty()) cachedInventoryItems.add(s.getItem()); });
    }

    public void toggleFilterMode() {
        filterMode = (filterMode == FilterMode.ALL) ? FilterMode.RARE_ONLY : FilterMode.ALL;
        validateSelection();
    }

    public void toggleAutoMode() {
        isAutoMode = !isAutoMode;
        pickupHandler.resetAutoCooldown();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            Component msg = isAutoMode
                    ? Component.translatable("message.better_looting.auto_on").withStyle(ChatFormatting.GREEN)
                    : Component.translatable("message.better_looting.auto_off").withStyle(ChatFormatting.RED);
            mc.player.displayClientMessage(msg, true);
        }
    }
}
