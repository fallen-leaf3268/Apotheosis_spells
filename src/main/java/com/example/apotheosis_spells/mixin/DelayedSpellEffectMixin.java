package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.DelayedSpellEffectHandler;
import com.example.apotheosis_spells.handler.SpellCastHooks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import io.redspace.ironsspellbooks.effect.EchoingStrikesEffect;
import io.redspace.ironsspellbooks.effect.FrostbiteEffect;
import io.redspace.ironsspellbooks.entity.mobs.frozen_humanoid.FrozenHumanoid;
import io.redspace.ironsspellbooks.entity.spells.EchoingStrikeEntity;
import io.redspace.ironsspellbooks.spells.ender.EchoingStrikesSpell;
import io.redspace.ironsspellbooks.spells.ice.FrostbiteSpell;
import io.redspace.ironsspellbooks.spells.lightning.ThunderstormSpell;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

public final class DelayedSpellEffectMixin {
    @Mixin(value = {FrostbiteSpell.class, EchoingStrikesSpell.class, ThunderstormSpell.class}, remap = false)
    public static class Application {
        @WrapOperation(method = "onCast", at = @At(value = "INVOKE", remap = true,
                target = "Lnet/minecraft/world/entity/LivingEntity;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;)Z"))
        private boolean apoth_captureSource(LivingEntity caster, MobEffectInstance incoming, Operation<Boolean> original) {
            if (!caster.level().isClientSide) DelayedSpellEffectHandler.associate(incoming, caster);
            return original.call(caster, incoming);
        }
    }

    @Mixin(value = FrostbiteEffect.class, remap = false)
    public static class Frostbite {
        @WrapOperation(method = "handleFrostbiteDeathEffects", at = @At(value = "INVOKE",
                target = "Lio/redspace/ironsspellbooks/effect/FrostbiteEffect;getDamageForAmplifier(ILnet/minecraft/world/entity/LivingEntity;)F"))
        private static float apoth_shatterPower(int amplifier, LivingEntity caster, Operation<Float> original,
                                                @Local MobEffectInstance effect,
                                                @Share("apoth_effect_source") LocalRef<SpellCastHooks.Snapshot> source) {
            source.set(DelayedSpellEffectHandler.snapshot(effect, caster));
            try (var scope = DelayedSpellEffectHandler.enter(source.get(), caster)) {
                return original.call(amplifier, caster);
            }
        }

        @WrapOperation(method = "handleFrostbiteDeathEffects", at = @At(value = "INVOKE", remap = true,
                target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
        private static boolean apoth_frozenSource(Level level, Entity entity, Operation<Boolean> original,
                                                  @Share("apoth_effect_source") LocalRef<SpellCastHooks.Snapshot> source) {
            try (var scope = DelayedSpellEffectHandler.enter(source.get(), ((FrozenHumanoid) entity).getSummoner())) {
                return original.call(level, entity);
            }
        }
    }

    @Mixin(value = EchoingStrikesEffect.class, remap = false)
    public static class Echoing {
        @WrapOperation(method = "createEcho", at = @At(value = "INVOKE",
                target = "Lio/redspace/ironsspellbooks/effect/EchoingStrikesEffect;getDamageModifier(ILnet/minecraft/world/entity/LivingEntity;)F"))
        private static float apoth_echoPower(int amplifier, LivingEntity caster, Operation<Float> original,
                                             @Local MobEffectInstance effect,
                                             @Share("apoth_effect_source") LocalRef<SpellCastHooks.Snapshot> source) {
            source.set(DelayedSpellEffectHandler.snapshot(effect, caster));
            try (var scope = DelayedSpellEffectHandler.enter(source.get(), caster)) {
                return original.call(amplifier, caster);
            }
        }

        @WrapOperation(method = "createEcho", at = @At(value = "NEW",
                target = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;FF)Lio/redspace/ironsspellbooks/entity/spells/EchoingStrikeEntity;"), require = 1)
        private static EchoingStrikeEntity apoth_echoRadius(Level level, LivingEntity caster, float damage, float radius,
                                                           Operation<EchoingStrikeEntity> original,
                                                           @Share("apoth_effect_source") LocalRef<SpellCastHooks.Snapshot> source) {
            try (var scope = DelayedSpellEffectHandler.enter(source.get(), caster)) {
                float multiplier = source.get() == null ? 1 : source.get().data().radius();
                return original.call(level, caster, damage, multiplier == 1 ? radius : Mth.clamp(radius * multiplier, 0, 32));
            }
        }

        @WrapOperation(method = "createEcho", at = @At(value = "INVOKE", remap = true,
                target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
        private static boolean apoth_echoSource(Level level, Entity entity, Operation<Boolean> original,
                                                @Share("apoth_effect_source") LocalRef<SpellCastHooks.Snapshot> source) {
            try (var scope = DelayedSpellEffectHandler.enter(source.get(), (LivingEntity) ((EchoingStrikeEntity) entity).getOwner())) {
                return original.call(level, entity);
            }
        }
    }

    @Mixin(value = EchoingStrikesSpell.class, remap = false)
    public static class EchoingRadiusPreview {
        @ModifyConstant(method = "getUniqueInfo", constant = @Constant(floatValue = 2), require = 1)
        private float apoth_displayedRadius(float radius, @Local(argsOnly = true) LivingEntity caster) {
            if (!SpellCastHooks.matches((EchoingStrikesSpell) (Object) this, caster)) return radius;
            float multiplier = SpellCastHooks.get().data().radius();
            return multiplier == 1 ? radius : Mth.clamp(radius * multiplier, 0, 32);
        }
    }

    @Mixin(value = FrozenHumanoid.class, remap = false)
    public static class FrozenShatter {
        @WrapMethod(method = "hurt", remap = true)
        private boolean apoth_shardSource(DamageSource damageSource, float damage, Operation<Boolean> original) {
            var entity = (FrozenHumanoid) (Object) this;
            if (entity.level().isClientSide) return original.call(damageSource, damage);
            var snapshot = SpellCastHooks.entitySnapshot(entity);
            if (snapshot != null && !snapshot.spellId().equals("irons_spellbooks:frostbite")) snapshot = null;
            try (var scope = DelayedSpellEffectHandler.enter(snapshot, entity.getSummoner())) {
                return original.call(damageSource, damage);
            }
        }
    }
}
