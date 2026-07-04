package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.api.ReforgeCache;
import dev.shadowsoffire.apotheosis.adventure.affix.Affix;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixInstance;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import dev.shadowsoffire.placebo.reload.DynamicHolder;
import io.redspace.ironsspellbooks.item.Scroll;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(value = AffixHelper.class, remap = false)
public class SyncMixin {

    /**
     * setAffixes 后重算 Scroll 的 ReforgeCache 缓存。
     * SpellBook 整体不在这里重铸 —— 属性路径走 BookAttributeHandler (每 10 tick 实时重建)。
     */
    @Inject(method = "setAffixes", at = @At("RETURN"))
    private static void onSetAffixes(ItemStack stack, Map<DynamicHolder<? extends Affix>, AffixInstance> affixes, CallbackInfo ci) {
        if (stack.isEmpty()) return;
        if (stack.getItem() instanceof Scroll) {
            ReforgeCache.sync(stack);
        }
    }

    /**
     * setRarity 后重算（强化台改 rarity 时触发）。
     */
    @Inject(method = "setRarity", at = @At("RETURN"))
    private static void onSetRarity(ItemStack stack, LootRarity rarity, CallbackInfo ci) {
        if (stack.isEmpty()) return;
        if (stack.getItem() instanceof Scroll) {
            ReforgeCache.sync(stack);
        }
    }
}
