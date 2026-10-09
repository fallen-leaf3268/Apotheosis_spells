package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.SiphoningBeamRange;
import com.example.apotheosis_spells.handler.SpellCastHooks;
import io.redspace.ironsspellbooks.capabilities.magic.SyncedSpellData;
import io.redspace.ironsspellbooks.spells.blood.RayOfSiphoningSpell;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SyncedSpellData.class, remap = false)
public class SiphoningRangeSyncMixin {
    @Shadow private LivingEntity livingEntity;

    @Inject(method = "setIsCasting", at = @At("HEAD"), require = 1)
    private void apoth_syncStartedRange(boolean casting, String spellId, int level, String slot, CallbackInfo ci) {
        if (!(livingEntity instanceof Player player) || player.level().isClientSide) return;
        float range = 0;
        if (casting && SiphoningBeamRange.SPELL_ID.equals(spellId)) {
            var snapshot = SpellCastHooks.currentSnapshot();
            try (var scope = SpellCastHooks.enter((SpellCastHooks.Context) null)) {
                range = RayOfSiphoningSpell.getRange(level);
            }
            if (snapshot != null && snapshot.owner().equals(player.getUUID()) && snapshot.spellId().equals(spellId)) {
                range *= snapshot.data().radius();
            }
        }
        ((SiphoningBeamRange.Data) player).apoth$setSiphoningRange(range);
    }
}
