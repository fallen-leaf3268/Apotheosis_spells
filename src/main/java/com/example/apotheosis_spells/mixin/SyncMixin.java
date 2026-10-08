package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.api.ReforgeCache;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.serialization.Codec;
import io.redspace.ironsspellbooks.api.backwards_compat.CodecHelper;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CodecHelper.class, remap = false)
public class SyncMixin {

    @Inject(method = "getWithLegacy", at = @At("HEAD"), require = 1)
    private static <T> void apothSpells$preserveLegacyAffixes(Codec<T> codec, ItemStack stack, String key,
            String legacyKey, Codec<T> legacyCodec, CallbackInfoReturnable<T> cir) {
        if (ISpellContainer.NBT.equals(key) && ISpellContainer.LEGACY_NBT.equals(legacyKey)) {
            ReforgeCache.preserveLegacyAffixes(stack);
        }
    }

    @WrapMethod(method = "set(Lnet/minecraft/world/item/ItemStack;Ljava/lang/String;Lcom/mojang/serialization/Codec;Ljava/lang/Object;)V")
    private static <T> void apothSpells$preserveSpellAffixes(ItemStack stack, String key, Codec<T> codec, T value,
                                                             Operation<Void> original) {
        boolean spellContainer = ISpellContainer.NBT.equals(key);
        if (spellContainer) ReforgeCache.sync(stack);
        try {
            original.call(stack, key, codec, value);
        } finally {
            if (spellContainer) ReforgeCache.sync(stack);
        }
    }
}
