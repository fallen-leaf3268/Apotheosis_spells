package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.SpellCastHooks;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = {
        io.redspace.ironsspellbooks.spells.nature.RootSpell.class,
        io.redspace.ironsspellbooks.spells.nature.PoisonSplashSpell.class,
        io.redspace.ironsspellbooks.spells.holy.HealingCircleSpell.class
}, remap = false)
public class SpellDurationMixin {
    @Inject(method = "getDuration", at = @At("RETURN"), cancellable = true)
    private void apoth_duration(int level, LivingEntity caster, CallbackInfoReturnable<Integer> cir) {
        if (!SpellCastHooks.matches((AbstractSpell) (Object) this, caster)) return;
        float multiplier = SpellCastHooks.get().data().duration();
        if (multiplier != 1f) cir.setReturnValue(Math.max(0, Math.round(cir.getReturnValueI() * multiplier)));
    }
}
