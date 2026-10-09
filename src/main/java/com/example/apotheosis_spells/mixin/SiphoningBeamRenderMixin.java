package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.SiphoningBeamRange;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.redspace.ironsspellbooks.render.SpellRenderingHelper;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = SpellRenderingHelper.class, remap = false)
public class SiphoningBeamRenderMixin {
    @WrapOperation(method = "renderRayOfSiphoning", require = 1, at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/spells/blood/RayOfSiphoningSpell;getRange(I)F"))
    private static float apoth_startedRange(int level, Operation<Float> original,
                                            @Local(argsOnly = true) LivingEntity caster) {
        return SiphoningBeamRange.renderedRange(caster, original.call(level));
    }
}
