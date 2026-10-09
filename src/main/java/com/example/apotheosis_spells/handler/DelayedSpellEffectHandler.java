package com.example.apotheosis_spells.handler;

import io.redspace.ironsspellbooks.registries.MobEffectRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

public final class DelayedSpellEffectHandler {
    private DelayedSpellEffectHandler() {}

    public static String spellId(MobEffect effect) {
        if (effect == MobEffectRegistry.FROSTBITTEN_STRIKES.get()) return "irons_spellbooks:frostbite";
        if (effect == MobEffectRegistry.ECHOING_STRIKES.get()) return "irons_spellbooks:echoing_strikes";
        if (effect == MobEffectRegistry.THUNDERSTORM.get()) return "irons_spellbooks:thunderstorm";
        return null;
    }

    public static SpellCastHooks.Snapshot valid(MobEffect effect, SpellCastHooks.Snapshot snapshot) {
        return snapshot != null && snapshot.spellId().equals(spellId(effect)) ? snapshot : null;
    }

    public static SpellCastHooks.Snapshot snapshot(MobEffectInstance effect, LivingEntity caster) {
        var snapshot = effect == null ? null : valid(effect.getEffect(),
                ((SpellCastHooks.SnapshotCarrier) (Object) effect).apoth$getSnapshot());
        return snapshot != null && caster instanceof ServerPlayer && snapshot.owner().equals(caster.getUUID())
                ? snapshot : null;
    }

    public static void associate(MobEffectInstance effect, LivingEntity caster) {
        var current = valid(effect.getEffect(), SpellCastHooks.currentSnapshot());
        ((SpellCastHooks.SnapshotCarrier) (Object) effect).apoth$setSnapshot(
                current != null && caster instanceof ServerPlayer && current.owner().equals(caster.getUUID())
                        ? current : null);
    }

    public static SpellCastHooks.Scope enter(SpellCastHooks.Snapshot snapshot, LivingEntity caster) {
        return SpellCastHooks.enter(snapshot, caster instanceof ServerPlayer player ? player : null);
    }
}
