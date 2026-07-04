package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.ApotheosisSpells;
import com.example.apotheosis_spells.api.ReforgeCache;
import dev.shadowsoffire.apotheosis.adventure.affix.Affix;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixInstance;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixRegistry;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry;
import dev.shadowsoffire.placebo.reload.DynamicHolder;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.gui.inscription_table.InscriptionTableMenu;
import io.redspace.ironsspellbooks.registries.ItemRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 铭刻台 NBT 同步修复
 *
 * 核心问题：
 *   1. ISpellContainer.set() 使用 SpellContainer.CODEC 序列化，只保留 (id, level, locked, index)
 *   2. 在 RETURN 处用 ReforgeCache.putSlotTag 写入的 affix_data，会在下一次 ISpellContainer.get() 时
 *      被 CODEC 解码丢弃（因为 CODEC 不识别 affix_data 字段）
 *
 * 解决方案：
 *   1. 改用直接 NBT 操作：直接读取/写入 spellBookItemStack 的 NBT，不经过 ISpellContainer.get/set
 *   2. 在 setupResultSlot RETURN 处，从书顶层并行存储按物理下标读取 affix_data 重建卷轴
 */
@Mixin(value = InscriptionTableMenu.class, remap = false)
public class InscribeMixin {

    @Shadow private int selectedSpellIndex;

    @Shadow public Slot getScrollSlot() { return null; }
    @Shadow public Slot getSpellBookSlot() { return null; }
    @Shadow public Slot getResultSlot() { return null; }

    @Unique private static CompoundTag cachedScrollSlotNbt = null;
    @Unique private static CompoundTag cachedScrollTopAffixData = null;
    @Unique private static List<CompoundTag> cachedAllBookSlots = null;

    @Inject(method = "doInscription", at = @At("HEAD"))
    private void beforeDoInscription(int selectedIndex, CallbackInfo ci) {
        InscriptionTableMenu self = (InscriptionTableMenu) (Object) this;
        ItemStack scrollStack = self.getScrollSlot().getItem();
        cachedScrollSlotNbt = ReforgeCache.getSlotTag(scrollStack, 0);
        cachedScrollTopAffixData = scrollStack.getTagElement(AffixHelper.AFFIX_DATA);

        ItemStack bookStack = self.getSpellBookSlot().getItem();
        cachedAllBookSlots = new ArrayList<>();
        if (!bookStack.isEmpty() && ISpellContainer.isSpellContainer(bookStack)) {
            CompoundTag root = bookStack.getTagElement(ISpellContainer.NBT);
            if (root == null) root = bookStack.getTagElement(ISpellContainer.LEGACY_NBT);
            if (root != null) {
                ListTag dataList = root.getList("data", 10);
                for (int i = 0; i < dataList.size(); i++) {
                    cachedAllBookSlots.add(dataList.getCompound(i).copy());
                }
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

        CompoundTag scrollAffixData = null;
        if (cachedScrollSlotNbt != null) {
            scrollAffixData = cachedScrollSlotNbt.getCompound(ReforgeCache.SLOT_AFFIX_DATA);
            if (scrollAffixData == null || scrollAffixData.isEmpty()) {
                scrollAffixData = cachedScrollTopAffixData;
            }
        } else {
            scrollAffixData = cachedScrollTopAffixData;
        }

        CompoundTag bookNbt = bookStack.getOrCreateTag();
        CompoundTag containerNbt = bookNbt.getCompound(ISpellContainer.NBT);
        ListTag dataList = containerNbt.getList("data", 10);

        for (int i = 0; i < dataList.size(); i++) {
            CompoundTag currentSlot = dataList.getCompound(i);
            int idx = currentSlot.getInt("index");

            CompoundTag cachedSlot = null;
            if (cachedAllBookSlots != null) {
                for (CompoundTag cached : cachedAllBookSlots) {
                    if (cached != null && cached.getInt("index") == idx) {
                        cachedSlot = cached;
                        break;
                    }
                }
            }

            if (cachedSlot != null) {
                dataList.set(i, cachedSlot.copy());
            } else if (idx == selectedIndex && scrollAffixData != null) {
                currentSlot.put(ReforgeCache.SLOT_AFFIX_DATA, scrollAffixData.copy());
                ReforgeCache.Data data = ReforgeCache.computeData(scrollAffixData);
                if (!data.isDefault()) {
                    currentSlot.put(ReforgeCache.KEY, data.write());
                }
                dataList.set(i, currentSlot);
            }
        }

        containerNbt.put("data", dataList);
        bookNbt.put(ISpellContainer.NBT, containerNbt);
        bookStack.setTag(bookNbt);

        ReforgeCache.setBookAffix(bookStack, selectedIndex, scrollAffixData);
        self.getSpellBookSlot().setChanged();
    }

    @Inject(method = "doInscription", at = @At("RETURN"))
    private void afterDoInscription(int selectedIndex, CallbackInfo ci) {
        cachedScrollSlotNbt = null;
        cachedScrollTopAffixData = null;
        cachedAllBookSlots = null;
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

        int targetIndex = selectedSpellIndex;

        CompoundTag affixData = ReforgeCache.getBookAffix(bookStack, targetIndex);
        if (affixData == null || affixData.isEmpty()) return;

        CompoundTag affixesTag = affixData.getCompound(AffixHelper.AFFIXES);
        try {
            ItemStack newScroll = new ItemStack(ItemRegistry.SCROLL.get());
            ISpellContainer.createScrollContainer(spellData.getSpell(), spellData.getLevel(), newScroll);

            Map<DynamicHolder<? extends Affix>, AffixInstance> affixMap = new LinkedHashMap<>();
            DynamicHolder<LootRarity> rarityHolder = AffixHelper.getRarity(affixData);
            LootRarity rarity = rarityHolder.isBound() ? rarityHolder.get() : RarityRegistry.getMinRarity().get();

            for (String key : affixesTag.getAllKeys()) {
                DynamicHolder<Affix> holder = AffixRegistry.INSTANCE.holder(new ResourceLocation(key));
                if (!holder.isBound()) continue;
                float lvl = affixesTag.getFloat(key);
                affixMap.put(holder, new AffixInstance(holder, newScroll, rarityHolder, lvl));
            }

            if (!affixMap.isEmpty()) {
                AffixHelper.setAffixes(newScroll, affixMap);
            }
            if (rarityHolder.isBound()) {
                AffixHelper.setRarity(newScroll, rarity);
            }

            String customName = affixData.getString(AffixHelper.NAME);
            if (customName != null && !customName.isEmpty()) {
                try {
                    net.minecraft.network.chat.Component name = net.minecraft.network.chat.Component.Serializer.fromJson(customName);
                    if (name != null) {
                        AffixHelper.setName(newScroll, name);
                    }
                } catch (Exception e) {
                    ApotheosisSpells.LOGGER.warn("[InscribeMixin] failed to parse custom name");
                }
            }

            ReforgeCache.sync(newScroll);
            // 直接复制完整 affix_data（含 gems），覆盖之前 setAffixes 写入的简化版
            ReforgeCache.rebuildAffixesToScroll(newScroll, affixData);

            self.getResultSlot().set(ItemStack.EMPTY);
            self.getResultSlot().set(newScroll);
        } catch (Exception e) {
            ApotheosisSpells.LOGGER.error("[InscribeMixin] afterSetupResultSlot failed: {}", e.toString());
        }
    }
}
