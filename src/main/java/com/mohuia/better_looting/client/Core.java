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
 * 客户端核心控制器 (单例模式)
 * <p>
 * 负责调度整个客户端的核心业务逻辑，基于 MVC 架构设计：
 * <ul>
 * <li><b>状态维护 (Model)</b>: 管理周围掉落物列表、选中索引、过滤模式和玩家背包缓存。</li>
 * <li><b>事件响应 (Controller)</b>: 监听游戏 Tick 和玩家输入，并委托给 {@link LootScanner} 和 {@link PickupHandler} 处理。</li>
 * </ul>
 * </p>
 *
 * @author Mohuia
 */
public class Core {
    /** 全局唯一实例 */
    public static final Core INSTANCE = new Core();

    /** 物品过滤模式枚举 */
    public enum FilterMode {
        ALL,        // 显示所有物品
        RARE_ONLY   // 仅显示稀有/特定物品
    }

    // =========================================
    //               助手模块与核心数据
    // =========================================

    private final PickupHandler pickupHandler = new PickupHandler();

    /** 当前扫描到的周围掉落物实体列表 */
    private List<VisualItemEntry> nearbyItems = new ArrayList<>();
    /** 玩家当前背包内的物品种类缓存（用于快速判断是否为已有物品） */
    private final Set<Item> cachedInventoryItems = new HashSet<>();

    // =========================================
    //               UI 与 控制状态
    // =========================================

    private int selectedIndex = 0;
    private int targetScrollOffset = 0;

    private FilterMode filterMode = FilterMode.ALL;
    private boolean isAutoMode = false;

    /** 游戏刻计数器，用于分散性能开销大的操作（如遍历背包） */
    private int tickCounter = 0;
    private int scrollKeyHoldTime = 0;

    /**
     * 私有构造函数，确保单例。
     * <p>
     * 注意：这里使用的是 {@code MinecraftForge.EVENT_BUS.register(this)}。
     * 因为我们需要在非静态环境（实例方法）中监听 Forge 事件（如 TickEvent），
     * 这与之前使用 {@code @Mod.EventBusSubscriber} 监听静态方法不同。
     * </p>
     */
    private Core() {
        MinecraftForge.EVENT_BUS.register(this);
        FilterWhitelist.INSTANCE.init();
    }

    // =========================================
    //               Public API (供 HUD 渲染)
    // =========================================

    public List<VisualItemEntry> getNearbyItems() { return nearbyItems; }
    public int getSelectedIndex() { return selectedIndex; }
    public int getTargetScrollOffset() { return targetScrollOffset; }
    public boolean hasItems() { return !nearbyItems.isEmpty(); }
    public FilterMode getFilterMode() { return filterMode; }
    public boolean isAutoMode() { return isAutoMode; }
    public boolean isItemInInventory(Item item) { return cachedInventoryItems.contains(item); }

    /** 获取当前拾取进度 (用于 UI 动画) */
    public float getPickupProgress() {
        return hasItems() ? pickupHandler.getProgress() : 0.0f;
    }

    /** 是否应该拦截原版交互逻辑（当模组正在处理拾取或列表不为空时） */
    public static boolean shouldIntercept() {
        return INSTANCE.hasItems() || INSTANCE.pickupHandler.isInteracting();
    }

    // =========================================
    //               事件循环核心逻辑
    // =========================================

    /**
     * 客户端主循环处理
     * @param event 客户端 Tick 事件
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        // 必须检查 Phase.END，否则每个游戏刻逻辑会执行两次 (START 和 END)
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            nearbyItems.clear();
            cachedInventoryItems.clear();
            return;
        }

        // 打开配置界面时暂停处理逻辑
        if (mc.screen instanceof ConfigScreen) return;

        // 1. 数据更新：利用计数器每 10 tick (0.5秒) 更新一次背包缓存，极大优化性能
        tickCounter++;
        if (tickCounter % 10 == 0) updateInventoryCache(mc);

        this.nearbyItems = LootScanner.scan(mc, this.filterMode);

        // 2. 自动拾取逻辑
        if (isAutoMode && !nearbyItems.isEmpty()) {
            if (pickupHandler.canAutoPickup()) {
                // 自动拾取所有合并后的物品
                List<ItemEntity> allEntities = new ArrayList<>();
                for (VisualItemEntry entry : nearbyItems) {
                    allEntities.addAll(entry.getSourceEntities());
                }
                sendBatchPickup(allEntities, true); // 这里复用原本的逻辑，只要传入实体列表即可
            }
        } else {
            pickupHandler.resetAutoCooldown();
        }

        validateSelection();
        handleInputLogic();
    }

    /** 处理功能键和拾取动作输入 */
    private void handleInputLogic() {
        while (KeyInit.TOGGLE_FILTER.consumeClick()) toggleFilterMode();
        while (KeyInit.OPEN_CONFIG.consumeClick()) Minecraft.getInstance().setScreen(new ConfigScreen());
        while (KeyInit.TOGGLE_AUTO.consumeClick()) toggleAutoMode();

        boolean isFKeyDown = KeyInit.PICKUP.isDown();
        boolean isShiftDown = net.minecraft.client.gui.screens.Screen.hasShiftDown();

        PickupHandler.PickupAction action = pickupHandler.tickInput(isFKeyDown, isShiftDown, !nearbyItems.isEmpty());

        switch (action) {
            case SINGLE:
                sendSinglePickup();
                break;
            case BATCH:
                // 批量拾取：解包当前列表所有条目的所有实体
                List<ItemEntity> allEntities = new ArrayList<>();
                for (VisualItemEntry entry : nearbyItems) {
                    allEntities.addAll(entry.getSourceEntities());
                }
                sendBatchPickup(allEntities, false);
                break;
            default:
                break;
        }

        handleKeyboardScroll();
    }

    // =========================================
    //               滚动与视图逻辑
    // =========================================

    /**
     * 拦截鼠标滚轮事件，用于控制物品列表滚动。
     */
    @SubscribeEvent
    public void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (shouldIgnoreScroll()) return;

        double scrollDelta = event.getScrollDelta();
        if (scrollDelta != 0) {
            performScroll(scrollDelta);
            // 关键：取消事件，防止在滚动模组列表时玩家快捷栏也跟着滚动
            event.setCanceled(true);
        }
    }

    /** 检查是否应忽略当前的滚动事件，交还给原版处理 */
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
            // 简单的移动判定：比较当前坐标与上一刻(xo, zo)坐标的位移平方
            double dx = mc.player.getX() - mc.player.xo;
            double dz = mc.player.getZ() - mc.player.zo;
            return (dx * dx + dz * dz) >= 0.0001;
        }
        return true;
    }

    private void performScroll(double delta) {
        if (nearbyItems.size() <= 1) return;
        selectedIndex += (delta > 0) ? -1 : 1;
        validateSelection();
    }

    /** 处理通过按键触发的滚动 (支持长按连发) */
    private void handleKeyboardScroll() {
        boolean up = KeyInit.SCROLL_UP.isDown();
        boolean down = KeyInit.SCROLL_DOWN.isDown();
        if (up || down) {
            scrollKeyHoldTime++;
            // 首次按下立即触发，之后每 3 tick 触发一次以实现快速滚动
            if (scrollKeyHoldTime == 1 || (scrollKeyHoldTime > 10 && scrollKeyHoldTime % 3 == 0)) {
                performScroll(up ? 1.0 : -1.0);
            }
        } else {
            scrollKeyHoldTime = 0;
        }
    }

    /** * 统一处理索引越界、循环滚动和视口偏移量 (ScrollOffset) 的计算。
     */
    private void validateSelection() {
        if (nearbyItems.isEmpty()) {
            selectedIndex = 0;
            targetScrollOffset = 0;
            return;
        }

        // 1. 循环滚动边界处理
        if (selectedIndex < 0) selectedIndex = nearbyItems.size() - 1;
        if (selectedIndex >= nearbyItems.size()) selectedIndex = 0;

        double visibleRows = Math.max(1.0, Config.CLIENT.visibleRows.get());

        // 如果物品总数小于等于可见行数，无需计算视口偏移
        if (nearbyItems.size() <= visibleRows) {
            targetScrollOffset = 0;
            return;
        }

        // 2. 向下滚动视口推移
        // 逻辑：如果选中项超出了视口底部，则推移视口让选中项显示在最底部
        if (selectedIndex + 1 > targetScrollOffset + visibleRows) {
            targetScrollOffset = (int) Math.ceil(selectedIndex - visibleRows + 1);
        }

        // 3. 向上滚动视口推移
        // 逻辑：如果选中项超出了视口顶部，则推移视口让选中项显示在最顶部
        if (selectedIndex < targetScrollOffset) {
            targetScrollOffset = selectedIndex;
        }

        // 4. 边界钳制 (Clamp)
        // 限制最大偏移量，防止列表向上滚动过度导致底部留白
        int maxOffset = (int) Math.ceil(Math.max(0, nearbyItems.size() - visibleRows));
        targetScrollOffset = Math.max(0, Math.min(targetScrollOffset, maxOffset));
    }

    // =========================================
    //               网络发包与状态切换
    // =========================================

    /** 向服务端发送单次拾取请求 (限制最大 64 个) */
    private void sendSinglePickup() {
        if (selectedIndex >= 0 && selectedIndex < nearbyItems.size()) {
            VisualItemEntry entry = nearbyItems.get(selectedIndex);

            // 1. 获取所有源实体并复制一份用于排序
            List<ItemEntity> candidates = new ArrayList<>(entry.getSourceEntities());

            // 2. 按距离排序：优先拾取离玩家最近的物品
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                candidates.sort(Comparator.comparingDouble(e -> e.distanceToSqr(mc.player)));
            }

            // 3. 贪婪选择：凑够一组 (64个)
            List<ItemEntity> targets = new ArrayList<>();
            int currentTotal = 0;

            for (ItemEntity entity : candidates) {
                // 如果当前累计数量已经达到或超过 64，停止选取
                if (currentTotal >= 64) break;

                targets.add(entity);
                currentTotal += entity.getItem().getCount();
            }

            // 发送筛选后的实体列表
            if (!targets.isEmpty()) {
                sendBatchPickup(targets, false);
            }
        }
    }

    /** * 向服务端发送批量拾取请求
     * @param entities 目标实体列表
     * @param isAuto   是否是由自动拾取触发
     */
    private void sendBatchPickup(List<ItemEntity> entities, boolean isAuto) {
        // 原本的逻辑保持不变，它接收 List<ItemEntity>
        List<Integer> ids = entities.stream()
                .filter(ItemEntity::isAlive)
                .map(ItemEntity::getId)
                .collect(Collectors.toList());

        if (!ids.isEmpty()) {
            NetworkHandler.sendToServer(new PacketBatchPickup(ids, isAuto));
        }
    }

    /** 更新背包缓存数据 */
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
            // 在玩家聊天栏上方发送带颜色的提示信息 (Action Bar 消息可能更好，但这里尊重原逻辑)
            Component msg = isAutoMode
                    ? Component.translatable("message.better_looting.auto_on").withStyle(ChatFormatting.GREEN)
                    : Component.translatable("message.better_looting.auto_off").withStyle(ChatFormatting.RED);
            mc.player.displayClientMessage(msg, true);
        }
    }
}
