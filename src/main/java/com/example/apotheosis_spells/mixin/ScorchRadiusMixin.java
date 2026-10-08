package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.SpellCastHooks;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.spells.fire.ScorchSpell;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ScorchSpell.class, remap = false)
public class ScorchRadiusMixin {
    @Inject(method = "getRadius", at = @At("RETURN"), cancellable = true)
    private void apoth_radius(LivingEntity caster, CallbackInfoReturnable<Float> cir) {
        if (!SpellCastHooks.matches((AbstractSpell) (Object) this, caster)) return;
        float multiplier = SpellCastHooks.get().data().radius();
        if (multiplier != 1f) cir.setReturnValue(Mth.clamp(cir.getReturnValueF() * multiplier, 0, 32));
    }
}
