package com.mohuia.better_looting.client;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

/**
 * 视觉上的物品条目.
 * <p>
 * 将地面上多个相同的 ItemEntity 合并为一个条目用于显示。
 * </p>
 */
public class VisualItemEntry {
    private final List<ItemEntity> sourceEntities = new ArrayList<>();
    private final ItemStack representativeStack;
    private int totalCount = 0;

    /** 标准构造函数：用于游戏内扫描到的实体 */
    public VisualItemEntry(ItemEntity firstEntity) {
        this.sourceEntities.add(firstEntity);
        this.representativeStack = firstEntity.getItem().copy();
        this.totalCount = this.representativeStack.getCount();
    }

    /** 仅预览构造函数：用于 ConfigScreen */
    public VisualItemEntry(ItemStack stack) {
        this.representativeStack = stack.copy();
        this.totalCount = stack.getCount();
    }

    /** * 尝试合并另一个实体
     * @return 如果合并成功返回 true，否则返回 false
     */
    public boolean tryMerge(ItemEntity entity) {
        ItemStack otherStack = entity.getItem();

        // --- 修改开始 ---

        // 1. 检查物品本身是否允许堆叠
        // 如果物品不可堆叠 (MaxStackSize == 1)，如剑、工具、盔甲，
        // 即使它们完全相同（包括耐久度），我们也强制不合并，让它们在列表中分开显示。
        if (!this.representativeStack.isStackable()) {
            return false;
        }

        // --- 修改结束 ---

        // 2. 原有逻辑：判断物品类型和 NBT 是否相同
        if (ItemStack.isSameItemSameTags(this.representativeStack, otherStack)) {
            this.sourceEntities.add(entity);
            this.totalCount += otherStack.getCount();
            return true;
        }

        return false;
    }

    public ItemStack getItem() { return representativeStack; }
    public int getCount() { return totalCount; }
    public List<ItemEntity> getSourceEntities() { return sourceEntities; }

    public int getPrimaryId() {
        return sourceEntities.isEmpty() ? -1 : sourceEntities.get(0).getId();
    }
}
