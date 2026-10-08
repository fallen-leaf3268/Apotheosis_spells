package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.ApotheosisSpells;
import com.example.apotheosis_spells.ApotheosisSpells.Diagnostics;
import com.example.apotheosis_spells.api.ReforgeCache;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.gui.inscription_table.InscriptionTableMenu;
import io.redspace.ironsspellbooks.registries.ItemRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 铭刻台 NBT 同步修复
 *
 * 核心问题：
 *   1. ISpellContainer.set() 使用 SpellContainer.CODEC 序列化，只保留 (id, level, locked, index)
 *   2. 在 RETURN 处用 ReforgeCache.putSlotTag 写入的 affix_data，会在下一次 ISpellContainer.get() 时
 *      被 CODEC 解码丢弃（因为 CODEC 不识别 affix_data 字段）
 *
 * 解决方案：先把旧槽数据迁移到带 spellId 绑定的书顶层映射，铭刻后只写目标映射；
 * 取出预览从映射恢复完整 affix_data，不覆盖 Iron's 新序列化的槽定义。
 */
@Mixin(value = InscriptionTableMenu.class, remap = false)
public class InscribeMixin {

    @Shadow private int selectedSpellIndex;

    @Shadow public Slot getScrollSlot() { return null; }
    @Shadow public Slot getSpellBookSlot() { return null; }
    @Shadow public Slot getResultSlot() { return null; }

    @Unique private CompoundTag apothSpells$scrollAffixData;
    @Unique private String apothSpells$scrollSpellId;

    @Inject(method = "doInscription", at = @At("HEAD"))
    private void beforeDoInscription(int selectedIndex, CallbackInfo ci) {
        InscriptionTableMenu self = (InscriptionTableMenu) (Object) this;
        ItemStack scrollStack = self.getScrollSlot().getItem();
        Diagnostics.log("INSCRIBE_INPUT", () -> "slot=" + selectedIndex + " scroll=" + Diagnostics.stack(scrollStack, true)
                + " book=" + Diagnostics.stack(self.getSpellBookSlot().getItem(), true));
        CompoundTag affixData = ReforgeCache.scrollAffixData(scrollStack);
        apothSpells$scrollAffixData = affixData == null ? null : affixData.copy();
        apothSpells$scrollSpellId = null;
        if (!scrollStack.isEmpty() && ISpellContainer.isSpellContainer(scrollStack)) {
            SpellData spellData = ISpellContainer.get(scrollStack).getSpellAtIndex(0);
            if (spellData != null && spellData != SpellData.EMPTY && spellData.getSpell() != null) {
                apothSpells$scrollSpellId = spellData.getSpell().getSpellId();
            }
        }
    }

    @Inject(method = "doInscription", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/ISpellContainer;set(Lnet/minecraft/world/item/ItemStack;Lio/redspace/ironsspellbooks/api/spells/ISpellContainer;)V",
            ordinal = 0, shift = At.Shift.AFTER))
    private void afterSetSpellBook(int selectedIndex, CallbackInfo ci) {
        InscriptionTableMenu self = (InscriptionTableMenu) (Object) this;
        ItemStack bookStack = self.getSpellBookSlot().getItem();
        if (bookStack.isEmpty()) return;

        CompoundTag insertedSlot = ReforgeCache.getSlotTag(bookStack, selectedIndex);
        if (insertedSlot == null || apothSpells$scrollSpellId == null
            || !apothSpells$scrollSpellId.equals(insertedSlot.getString("id"))) return;
        ReforgeCache.setBookAffix(bookStack, selectedIndex, apothSpells$scrollAffixData);
        Diagnostics.log("INSCRIBE_STORED", () -> "slot=" + selectedIndex + " book=" + Diagnostics.stack(bookStack, true));
        self.getSpellBookSlot().setChanged();
    }

    @Inject(method = "doInscription", at = @At("RETURN"))
    private void afterDoInscription(int selectedIndex, CallbackInfo ci) {
        apothSpells$scrollAffixData = null;
        apothSpells$scrollSpellId = null;
    }

    @Inject(method = "setupResultSlot", at = @At("RETURN"))
    private void afterSetupResultSlot(CallbackInfo ci) {
        InscriptionTableMenu self = (InscriptionTableMenu) (Object) this;
        ItemStack resultStack = self.getResultSlot().getItem();
        if (resultStack.isEmpty() || !resultStack.is(ItemRegistry.SCROLL.get())) return;

        ItemStack bookStack = self.getSpellBookSlot().getItem();
        if (bookStack.isEmpty() || !(bookStack.getItem() instanceof io.redspace.ironsspellbooks.item.SpellBook)) return;
        if (selectedSpellIndex < 0) return;

        var spellList = ISpellContainer.get(bookStack);
        // selectedSpellIndex 是物理槽位下标：与抄入时 setBookAffix 的键、Iron 的 removeSpellAtIndex 一致。
        // 旧代码用 activeSpells.get(selectedSpellIndex) 会在书有空槽时取错。
        SpellData spellData = spellList.getSpellAtIndex(selectedSpellIndex);
        if (spellData == null || spellData == SpellData.EMPTY || !spellData.canRemove()) return;

        try {
            ItemStack newScroll = ReforgeCache.restoreBookSpell(bookStack, selectedSpellIndex);
            if (newScroll.isEmpty()) return;
            if (!ItemStack.matches(newScroll, resultStack)) {
                self.getResultSlot().set(newScroll);
                Diagnostics.log("EXTRACT_PREVIEW", () -> "slot=" + selectedSpellIndex + " scroll=" + Diagnostics.stack(newScroll, true));
            }
        } catch (Exception e) {
            ApotheosisSpells.LOGGER.error("[InscribeMixin] failed to restore scroll affixes", e);
        }
    }

    @Pseudo
    @Mixin(targets = "net.kayn.fallen_gems_affixes.event.SoulboundEventHandler", remap = false)
    public static class Soulbound {
        @Shadow
        public static boolean hasSoulboundAffix(ItemStack stack) { throw new AssertionError(); }

        @Inject(method = "hasSoulboundAffix", at = @At("RETURN"), cancellable = true, require = 1)
        private static void apoth_bookSoulbound(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
            if (cir.getReturnValueZ() || !(stack.getItem() instanceof io.redspace.ironsspellbooks.item.SpellBook)
                    || !ISpellContainer.isSpellContainer(stack)) return;
            for (var slot : ISpellContainer.get(stack).getActiveSpells()) {
                ItemStack scroll = ReforgeCache.restoreBookSpell(stack, slot.index());
                if (!scroll.isEmpty() && hasSoulboundAffix(scroll)) {
                    cir.setReturnValue(true);
                    return;
                }
            }
        }
    }
}
