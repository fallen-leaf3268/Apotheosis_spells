package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.ApotheosisSpells.Diagnostics;
import dev.shadowsoffire.apotheosis.adventure.loot.LootController;
import io.redspace.ironsspellbooks.item.Scroll;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LootController.class, remap = false)
public class ReforgeMixin {
    @Inject(method = "createLootItem(Lnet/minecraft/world/item/ItemStack;Ldev/shadowsoffire/apotheosis/adventure/loot/LootRarity;Lnet/minecraft/util/RandomSource;)Lnet/minecraft/world/item/ItemStack;",
        at = @At("RETURN"), remap = false)
    private static void after(ItemStack stack, dev.shadowsoffire.apotheosis.adventure.loot.LootRarity rarity,
                               net.minecraft.util.RandomSource rand, CallbackInfoReturnable<ItemStack> cir) {
        if (cir.getReturnValue().getItem() instanceof Scroll) {
            ReforgeCache.sync(cir.getReturnValue());
            Diagnostics.log("REFORGE_OUTPUT", () -> Diagnostics.stack(cir.getReturnValue(), true)
                    + " calculated=" + ReforgeCache.getFromScroll(cir.getReturnValue()));
        }
    }
}
