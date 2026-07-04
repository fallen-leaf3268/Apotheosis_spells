package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.api.SlotOnTakeState;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.gui.inscription_table.InscriptionTableMenu;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.Map;

/**
 * 拦截 Slot.onTake,在 InscriptionTableMenu 的 resultSlot 被取走时恢复剩余法术的 affix_data。
 *
 * 注:InscribeMixin 当前主流程已改为按 BOOK_AFFIXES 直接从书顶层读取,不再依赖本路径。
 * 保留本类作为旧路径兜底,若上层有调用 SlotOnTakeState.set 则继续生效。
 */
@Mixin(Slot.class)
public class SlotOnTakeMixin {

    @Inject(method = "onTake", at = @At("HEAD"), locals = LocalCapture.CAPTURE_FAILSOFT)
    private void onTakeHead(net.minecraft.world.entity.player.Player player, ItemStack taken, CallbackInfo ci) {
        Slot self = (Slot) (Object) this;
        if (!(self.container instanceof ResultContainer)) return;
        if (!SlotOnTakeState.isActive()) return;

        int removedIndex = SlotOnTakeState.getRemovedIndex();
        Map<Integer, CompoundTag> remainingData = SlotOnTakeState.getRemainingData();
        if (remainingData == null || remainingData.isEmpty()) {
            SlotOnTakeState.clear();
            return;
        }

        try {
            Slot spellBookSlot;
            try {
                spellBookSlot = ((InscriptionTableMenu) SlotOnTakeState.getMenu()).getSpellBookSlot();
            } catch (Exception e) {
                SlotOnTakeState.clear();
                return;
            }
            if (spellBookSlot == null) {
                SlotOnTakeState.clear();
                return;
            }

            ItemStack spellBookStack = spellBookSlot.getItem();
            if (spellBookStack.isEmpty() || !ISpellContainer.isSpellContainer(spellBookStack)) {
                SlotOnTakeState.clear();
                return;
            }

            CompoundTag bookNbt = spellBookStack.getOrCreateTag();
            CompoundTag containerNbt = bookNbt.getCompound(ISpellContainer.NBT);
            ListTag dataList = containerNbt.getList("data", 10);

            // 按法术槽的稳定 index 字段精确恢复,而不是按数组顺序,避免从中间取时错位
            for (int i = 0; i < dataList.size(); i++) {
                CompoundTag slotTag = dataList.getCompound(i);
                int idx = slotTag.getInt("index");
                if (idx == removedIndex) continue;

                CompoundTag toRestore = remainingData.get(idx);
                if (toRestore == null || toRestore.isEmpty()) continue;

                slotTag.put(ReforgeCache.SLOT_AFFIX_DATA, toRestore.copy());
                ReforgeCache.syncSlotTag(slotTag);
                dataList.set(i, slotTag);
            }

            containerNbt.put("data", dataList);
            bookNbt.put(ISpellContainer.NBT, containerNbt);
            spellBookStack.setTag(bookNbt);
        } finally {
            SlotOnTakeState.clear();
        }
    }
}
