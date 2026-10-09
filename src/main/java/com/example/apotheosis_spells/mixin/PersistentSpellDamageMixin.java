package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.PersistentSpellDamage;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.redspace.ironsspellbooks.entity.spells.EarthquakeAoe;
import io.redspace.ironsspellbooks.entity.spells.FireEruptionAoe;
import io.redspace.ironsspellbooks.entity.spells.dragon_breath.DragonBreathPool;
import io.redspace.ironsspellbooks.entity.spells.magma_ball.FireField;
import io.redspace.ironsspellbooks.entity.spells.poison_cloud.PoisonCloud;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = {FireField.class, PoisonCloud.class, EarthquakeAoe.class, FireEruptionAoe.class, DragonBreathPool.class}, remap = false)
public class PersistentSpellDamageMixin {
    @WrapOperation(method = "applyEffect", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z", remap = true))
    private boolean apoth_fieldDamage(LivingEntity target, DamageSource source, float amount, Operation<Boolean> original) {
        return PersistentSpellDamage.hurt((Entity) (Object) this, target, source, amount,
                (nativeSource, damage) -> original.call(target, nativeSource, damage));
    }
}
