package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.SpellCastHooks;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = {
        io.redspace.ironsspellbooks.spells.eldritch.PocketDimensionSpell.class,
        io.redspace.ironsspellbooks.spells.ender.RecallSpell.class,
        io.redspace.ironsspellbooks.spells.evocation.ThrowSpell.class,
        io.redspace.ironsspellbooks.spells.fire.FlamingStrikeSpell.class,
        io.redspace.ironsspellbooks.spells.fire.RaiseHellSpell.class,
        io.redspace.ironsspellbooks.spells.holy.DivineSmiteSpell.class,
        io.redspace.ironsspellbooks.spells.nature.StompSpell.class
}, remap = false)
public class SpellCastTimeMixin {
    @Inject(method = "getEffectiveCastTime", at = @At("RETURN"), cancellable = true)
    private void apoth_castTime(int level, LivingEntity caster, CallbackInfoReturnable<Integer> cir) {
        var context = SpellCastHooks.get();
        if (context == null || !context.castContext()
                || !SpellCastHooks.matches((AbstractSpell) (Object) this, caster)) return;
        cir.setReturnValue(Math.max(0, Math.round(cir.getReturnValueI() * context.data().cast())));
    }
}
