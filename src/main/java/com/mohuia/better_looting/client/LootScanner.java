package com.mohuia.better_looting.client;

import com.mohuia.better_looting.client.filter.FilterWhitelist;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 物品扫描器
 * 负责从世界中获取物品实体，执行过滤，并将相同的物品合并为 VisualItemEntry。
 */
public class LootScanner {

    private static final double EXPAND_XZ = 1.0;
    private static final double EXPAND_Y = 0.5;

    /**
     * 针对 VisualItemEntry 的排序比较器.
     * 顺序：稀有度 > 数量(新增) > 附魔 > 名称 > ID
     */
    private static final Comparator<VisualItemEntry> VISUAL_COMPARATOR = (e1, e2) -> {
        ItemStack s1 = e1.getItem();
        ItemStack s2 = e2.getItem();

        // 1. 稀有度 (倒序，EPIC 在前)
        int rDiff = s2.getRarity().ordinal() - s1.getRarity().ordinal();
        if (rDiff != 0) return rDiff;

        // 2. 数量 (数量多的优先显示，视觉上更好看)
        int cDiff = Integer.compare(e2.getCount(), e1.getCount());
        if (cDiff != 0) return cDiff;

        // 3. 附魔
        boolean enc1 = s1.isEnchanted();
        boolean enc2 = s2.isEnchanted();
        if (enc1 != enc2) return enc1 ? -1 : 1;

        // 4. 名称
        int nameDiff = s1.getHoverName().getString().compareTo(s2.getHoverName().getString());
        if (nameDiff != 0) return nameDiff;

        // 5. ID (使用代表实体的 ID 保持稳定)
        return Integer.compare(e1.getPrimaryId(), e2.getPrimaryId());
    };

    /**
     * 扫描、过滤并合并物品.
     */
    public static List<VisualItemEntry> scan(Minecraft mc, Core.FilterMode filterMode) {
        if (mc.player == null || mc.level == null) return new ArrayList<>();

        AABB area = mc.player.getBoundingBox().inflate(1.0, 0.5, 1.0); // 这里的范围逻辑不变
        List<ItemEntity> rawEntities = mc.level.getEntitiesOfClass(ItemEntity.class, area, entity ->
                entity.isAlive() && !entity.getItem().isEmpty()
        );

        // --- 新增：合并逻辑 ---
        List<VisualItemEntry> mergedList = new ArrayList<>();

        for (ItemEntity entity : rawEntities) {
            // 过滤逻辑前置：如果本身就需要过滤，直接不参与合并
            if (filterMode == Core.FilterMode.RARE_ONLY && shouldHide(entity.getItem())) {
                continue;
            }

            boolean merged = false;
            // 尝试合并到已有的条目中
            for (VisualItemEntry entry : mergedList) {
                if (entry.tryMerge(entity)) {
                    merged = true;
                    break;
                }
            }
            // 如果没合并，创建新条目
            if (!merged) {
                mergedList.add(new VisualItemEntry(entity));
            }
        }

        // 排序
        mergedList.sort(VISUAL_COMPARATOR);
        return mergedList;
    }

    private static boolean shouldHide(ItemStack stack) {
        if (FilterWhitelist.INSTANCE.contains(stack)) return false;
        return stack.getRarity() == Rarity.COMMON
                && !stack.isEnchanted()
                && !Utils.shouldShowTooltip(stack);
    }
}
