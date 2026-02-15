package com.mohuia.better_looting.client;

import com.mohuia.better_looting.client.filter.FilterWhitelist;
import com.mohuia.better_looting.client.Utils; // 假设你有这个工具类
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 物品扫描器.
 * <p>
 * 负责从世界中获取物品实体，应用过滤规则，并执行复杂的排序算法。
 * 该类不持有状态，只提供静态计算能力。
 */
public class LootScanner {

    private static final double EXPAND_XZ = 1.0;
    private static final double EXPAND_Y = 0.5;

    /**
     * 稳定的排序比较器.
     * 顺序：稀有度 > 附魔 > 名称 > ID
     */
    private static final Comparator<ItemEntity> STABLE_COMPARATOR = (e1, e2) -> {
        ItemStack s1 = e1.getItem();
        ItemStack s2 = e2.getItem();

        int rDiff = s2.getRarity().ordinal() - s1.getRarity().ordinal();
        if (rDiff != 0) return rDiff;

        boolean enc1 = s1.isEnchanted();
        boolean enc2 = s2.isEnchanted();
        if (enc1 != enc2) return enc1 ? -1 : 1;

        int nameDiff = s1.getHoverName().getString().compareTo(s2.getHoverName().getString());
        if (nameDiff != 0) return nameDiff;

        return Integer.compare(e1.getId(), e2.getId());
    };

    /**
     * 扫描并处理周围的物品.
     *
     * @param mc Minecraft 实例
     * @param filterMode 当前过滤模式
     * @return 排序且过滤后的实体列表
     */
    public static List<ItemEntity> scan(Minecraft mc, Core.FilterMode filterMode) {
        if (mc.player == null || mc.level == null) return new ArrayList<>();

        // 1. 获取范围内的实体
        AABB area = mc.player.getBoundingBox().inflate(EXPAND_XZ, EXPAND_Y, EXPAND_XZ);
        List<ItemEntity> found = mc.level.getEntitiesOfClass(ItemEntity.class, area, entity ->
                entity.isAlive() && !entity.getItem().isEmpty()
        );

        // 2. 过滤逻辑
        if (filterMode == Core.FilterMode.RARE_ONLY) {
            found.removeIf(entity -> shouldHide(entity.getItem()));
        }

        // 3. 排序
        found.sort(STABLE_COMPARATOR);
        return found;
    }

    /**
     * 判断物品在“仅稀有”模式下是否应该被隐藏.
     */
    private static boolean shouldHide(ItemStack stack) {
        // 白名单物品永远显示
        if (FilterWhitelist.INSTANCE.contains(stack)) return false;

        // 隐藏条件：普通品质 + 无附魔 + 无特殊Tooltip
        return stack.getRarity() == Rarity.COMMON
                && !stack.isEnchanted()
                && !Utils.shouldShowTooltip(stack);
    }
}
