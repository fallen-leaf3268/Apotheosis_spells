package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.SpellCastHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.redspace.ironsspellbooks.api.magic.SpellSelectionManager;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.util.Utils;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = Utils.class, remap = false)
public class CastSourceMixin {
    @Mixin(value = io.redspace.ironsspellbooks.item.CastingItem.class, remap = false)
    public static class CastingItem {
        @WrapOperation(method = "use", remap = true, at = @At(value = "INVOKE", remap = false,
                target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;attemptInitiateCast(Lnet/minecraft/world/item/ItemStack;ILnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lio/redspace/ironsspellbooks/api/spells/CastSource;ZLjava/lang/String;)Z"))
        private boolean apoth_implementSlot(AbstractSpell spell, ItemStack stack, int level, Level world, Player player,
                                            CastSource source, boolean cooldown, String equipmentSlot,
                                            Operation<Boolean> original,
                                            @Local SpellSelectionManager.SelectionOption selection) {
            try (var scope = SpellCastHooks.enterSource(player, selection, stack, equipmentSlot)) {
                return original.call(spell, stack, level, world, player, source, cooldown, equipmentSlot);
            }
        }
    }

    @Mixin(value = io.redspace.ironsspellbooks.player.ServerPlayerEvents.class, remap = false)
    public static class MarkedImplement {
        @WrapOperation(method = "onUseItem", at = @At(value = "INVOKE",
                target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;attemptInitiateCast(Lnet/minecraft/world/item/ItemStack;ILnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lio/redspace/ironsspellbooks/api/spells/CastSource;ZLjava/lang/String;)Z"))
        private static boolean apoth_implementSlot(AbstractSpell spell, ItemStack stack, int level, Level world, Player player,
                                                    CastSource source, boolean cooldown, String equipmentSlot,
                                                    Operation<Boolean> original,
                                                    @Local SpellSelectionManager.SelectionOption selection) {
            try (var scope = SpellCastHooks.enterSource(player, selection, stack, equipmentSlot)) {
                return original.call(spell, stack, level, world, player, source, cooldown, equipmentSlot);
            }
        }
    }

    @WrapOperation(method = {"serverSideInitiateCast", "serverSideInitiateQuickCast"},
            at = @At(value = "INVOKE", target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;attemptInitiateCast(Lnet/minecraft/world/item/ItemStack;ILnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lio/redspace/ironsspellbooks/api/spells/CastSource;ZLjava/lang/String;)Z"))
    private static boolean apoth_requestedSlot(AbstractSpell spell, ItemStack stack, int level, Level world, Player player,
                                                CastSource source, boolean cooldown, String equipmentSlot,
                                                Operation<Boolean> original,
                                                @Local SpellSelectionManager.SelectionOption selection) {
        try (var scope = SpellCastHooks.enterSource(player, selection)) {
            return original.call(spell, stack, level, world, player, source, cooldown, equipmentSlot);
        }
    }
}
