package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.SpellCastHooks;
import com.example.apotheosis_spells.handler.SpellEffectHandler;
import com.example.apotheosis_spells.ApotheosisSpells.Diagnostics;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import io.redspace.ironsspellbooks.capabilities.magic.PlayerRecasts;
import io.redspace.ironsspellbooks.capabilities.magic.RecastInstance;
import io.redspace.ironsspellbooks.capabilities.magic.RecastResult;
import io.redspace.ironsspellbooks.damage.DamageSources;
import io.redspace.ironsspellbooks.damage.SpellDamageSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MagicManager.class, remap = false)
public class MagicManagerMixin {
    @Mixin(DamageSource.class)
    public static class EchoDamage {
        @Inject(method = "is(Lnet/minecraft/tags/TagKey;)Z", at = @At("HEAD"), cancellable = true)
        private void apoth_echoCooldown(net.minecraft.tags.TagKey<net.minecraft.world.damagesource.DamageType> tag,
                                       CallbackInfoReturnable<Boolean> cir) {
            if (!net.minecraft.tags.DamageTypeTags.BYPASSES_COOLDOWN.equals(tag)
                    || !((Object) this instanceof SpellDamageSource source)) return;
            var snapshot = SpellCastHooks.forDamage(source);
            if (snapshot != null && snapshot.echo()) cir.setReturnValue(true);
        }
    }

    @Mixin(value = MagicData.class, remap = false)
    public static class EchoEquipment {
        @Inject(method = "getCastingEquipmentSlot", at = @At("HEAD"), cancellable = true)
        private void apoth_echoSlot(CallbackInfoReturnable<String> cir) {
            String slot = SpellEffectHandler.echoEquipmentSlot((MagicData) (Object) this);
            if (slot != null) cir.setReturnValue(slot);
        }
    }

    @WrapMethod(method = "lambda$tick$0")
    private void apoth_tick(boolean regen, Player player, Operation<Void> original) {
        MagicData magic = MagicData.getPlayerMagicData(player);
        var snapshot = magic != null && magic.isCasting()
                ? SpellCastHooks.active(player, magic.getCastingSpellId()) : null;
        try (var scope = SpellCastHooks.enter(snapshot, player)) {
            original.call(regen, player);
        }
    }

    @Inject(method = "getEffectiveSpellCooldown", at = @At("RETURN"), cancellable = true)
    private static void apoth_cooldown(AbstractSpell spell, Player player, CastSource source, CallbackInfoReturnable<Integer> cir) {
        if (!SpellCastHooks.matches(spell, player)) return;
        int before = cir.getReturnValueI();
        float multiplier = SpellCastHooks.get().data().cd();
        cir.setReturnValue(Math.max(0, Math.round(before * multiplier)));
        if (player != null && SpellCastHooks.get().castContext()) Diagnostics.calculation("COOLDOWN_CALC",
                player.getUUID() + ":" + spell.getSpellId(), "source=" + source + " before=" + before
                        + " multiplier=" + multiplier + " after=" + cir.getReturnValueI());
    }

    @Inject(method = "addCooldown", at = @At("HEAD"), cancellable = true)
    private void apoth_skipCooldown(ServerPlayer player, AbstractSpell spell, CastSource source, CallbackInfo ci) {
        var snapshot = SpellCastHooks.currentSnapshot();
        if (snapshot != null && player != null && SpellCastHooks.matches(spell, player)) {
            if (snapshot.effects().skipCooldown(player, spell)) ci.cancel();
        }
    }

    @Mixin(value = PlayerRecasts.class, remap = false)
    public static class Recasts {
        @Shadow @Final private ServerPlayer serverPlayer;

        @WrapMethod(method = "triggerRecastComplete")
        private void apoth_complete(RecastInstance recast, RecastResult result, Operation<Void> original) {
            var snapshot = ((SpellCastHooks.SnapshotCarrier) recast).apoth$getSnapshot();
            if (snapshot != null && (!snapshot.spellId().equals(recast.getSpellId())
                    || serverPlayer == null || !snapshot.owner().equals(serverPlayer.getUUID()))) snapshot = null;
            try (var scope = SpellCastHooks.enter(snapshot, serverPlayer)) {
                original.call(recast, result);
            }
        }
    }

    @Mixin(value = RecastInstance.class, remap = false)
    public static class RecastState implements SpellCastHooks.SnapshotCarrier {
        @Unique private SpellCastHooks.Snapshot apoth$snapshot;

        @Override public SpellCastHooks.Snapshot apoth$getSnapshot() { return apoth$snapshot; }
        @Override public void apoth$setSnapshot(SpellCastHooks.Snapshot snapshot) { apoth$snapshot = snapshot; }

        @Inject(method = "<init>", at = @At("RETURN"))
        private void apoth_created(CallbackInfo ci) {
            var current = SpellCastHooks.currentSnapshot();
            if (current != null && current.spellId().equals(((RecastInstance) (Object) this).getSpellId())) {
                apoth$snapshot = current;
            }
        }

        @Inject(method = "serializeNBT()Lnet/minecraft/nbt/CompoundTag;", at = @At("RETURN"))
        private void apoth_save(CallbackInfoReturnable<CompoundTag> cir) {
            if (apoth$snapshot != null) cir.getReturnValue().put(SpellCastHooks.SNAPSHOT_KEY, apoth$snapshot.write());
        }

        @Inject(method = "deserializeNBT(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("RETURN"))
        private void apoth_load(CompoundTag tag, CallbackInfo ci) {
            apoth$snapshot = tag.contains(SpellCastHooks.SNAPSHOT_KEY)
                    ? SpellCastHooks.Snapshot.read(tag.getCompound(SpellCastHooks.SNAPSHOT_KEY)) : null;
        }
    }

    @Mixin(value = DamageSources.class, remap = false)
    public static class Damage {
        @WrapMethod(method = "applyDamage")
        private static boolean apoth_damage(Entity target, float amount, DamageSource source, Operation<Boolean> original) {
            if (!(target instanceof LivingEntity living) || !(source instanceof SpellDamageSource spellSource)) {
                return original.call(target, amount, source);
            }
            float health = living.getHealth();
            float absorption = living.getAbsorptionAmount();
            boolean applied;
            float dealt;
            try (var settlement = new SpellEffectHandler.DamageSettlement(living, source)) {
                applied = original.call(target, amount, source);
                dealt = settlement.amount(applied);
            }
            if (!target.level().isClientSide && spellSource.getEntity() instanceof Player player) Diagnostics.log("SPELL_DAMAGE", () -> Diagnostics.player(player)
                    + " spell=" + (spellSource.spell() == null ? "NONE" : spellSource.spell().getSpellId())
                    + " targetType=" + net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(target.getType())
                    + " directType=" + (source.getDirectEntity() == null ? "NONE"
                            : net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(source.getDirectEntity().getType()))
                    + " target=" + target.getId() + " requested=" + amount + " applied=" + applied + " settled=" + dealt
                    + " health=" + health + "->" + living.getHealth() + " absorption=" + absorption + "->" + living.getAbsorptionAmount()
                    + " snapshot=" + SpellCastHooks.forDamage(spellSource));
            if (dealt > 0) SpellEffectHandler.afterDamage(living, spellSource, dealt);
            return applied;
        }
    }

    @Mixin(value = net.minecraftforge.common.ForgeHooks.class, remap = false)
    public static class DamageSettlement {
        @Inject(method = "onLivingDamage", at = @At("RETURN"))
        private static void apoth_settled(LivingEntity target, DamageSource source, float amount, CallbackInfoReturnable<Float> cir) {
            SpellEffectHandler.recordSettledDamage(target, source, cir.getReturnValueF());
        }
    }

    @Mixin(ServerLevel.class)
    public static class EntityTicks {
        @WrapOperation(method = "tickNonPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
        private void apoth_tickEntity(Entity entity, Operation<Void> original) {
            var snapshot = SpellCastHooks.entitySnapshot(entity);
            if (snapshot == null) {
                original.call(entity);
                return;
            }
            try (var scope = SpellCastHooks.enter(snapshot, SpellCastHooks.owner(entity, snapshot))) {
                original.call(entity);
            }
        }

        @WrapOperation(method = "tickPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;rideTick()V"))
        private void apoth_tickPassenger(Entity entity, Operation<Void> original) {
            var snapshot = SpellCastHooks.entitySnapshot(entity);
            if (snapshot == null) {
                original.call(entity);
                return;
            }
            try (var scope = SpellCastHooks.enter(snapshot, SpellCastHooks.owner(entity, snapshot))) {
                original.call(entity);
            }
        }
    }
}
