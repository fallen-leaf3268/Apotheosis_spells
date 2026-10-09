package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.SpellCastHooks;
import io.redspace.ironsspellbooks.entity.spells.black_hole.BlackHole;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BlackHole.class, remap = false)
public class BlackHoleDurationMixin {
    @Inject(method = "<init>(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)V", at = @At("RETURN"), require = 1)
    private void apoth_duration(Level level, LivingEntity caster, CallbackInfo ci) {
        var ctx = SpellCastHooks.get();
        if (ctx == null || !ctx.castContext() || ctx.spellData() == null
                || !ctx.spellData().getSpell().getSpellId().equals("irons_spellbooks:black_hole")
                || !SpellCastHooks.matches(ctx.spellData().getSpell(), caster)) return;
        float multiplier = ctx.data().duration();
        if (multiplier != 1) {
            var hole = (BlackHole) (Object) this;
            hole.setDuration(Math.max(0, Math.round(hole.getDuration() * multiplier)));
        }
    }
}
