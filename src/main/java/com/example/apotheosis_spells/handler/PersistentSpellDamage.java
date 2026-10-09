package com.example.apotheosis_spells.handler;

import com.example.apotheosis_spells.api.SpellEffects;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

public final class PersistentSpellDamage {
    @FunctionalInterface
    public interface DamageOperation {
        boolean hurt(DamageSource source, float amount);
    }

    private PersistentSpellDamage() {}

    public static boolean hurt(Entity carrier, Entity target, DamageSource source, float amount, DamageOperation operation) {
        var snapshot = SpellCastHooks.entitySnapshot(carrier);
        if (!(target instanceof LivingEntity living) || !(source.getEntity() instanceof ServerPlayer owner)
                || snapshot == null || !snapshot.owner().equals(owner.getUUID())) return nativeDamage(source, amount, operation);
        var spell = SpellRegistry.getSpell(snapshot.spellId());
        if (spell == null || !snapshot.spellId().equals(spell.getSpellId())) return nativeDamage(source, amount, operation);
        var metadata = spell.getDamageSource(carrier, owner);
        try (var scope = SpellCastHooks.enter(snapshot, owner);
             var settlement = new SpellEffectHandler.DamageSettlement(living, source)) {
            float modified = snapshot.effects().modifyDamage(new SpellEffects.DamageContext(owner, living, metadata, amount), amount);
            boolean succeeded = operation.hurt(source, Float.isFinite(modified) && modified >= 0 ? modified : amount);
            float damage = settlement.amount(succeeded);
            if (damage > 0) snapshot.effects().afterDamage(new SpellEffects.DamageContext(owner, living, metadata, damage));
            return succeeded;
        }
    }

    private static boolean nativeDamage(DamageSource source, float amount, DamageOperation operation) {
        try (var scope = SpellCastHooks.enter((SpellCastHooks.Snapshot) null, null)) {
            return operation.hurt(source, amount);
        }
    }
}
