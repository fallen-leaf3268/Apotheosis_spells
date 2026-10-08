package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.SpellCastHooks;
import com.example.apotheosis_spells.handler.BookAttributeHandler;
import com.example.apotheosis_spells.ApotheosisSpells.Diagnostics;
import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.handler.SpellEffectHandler;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AbstractSpell.class, remap = false)
public class CastMixin {
    @WrapMethod(method = "attemptInitiateCast")
    private boolean apoth_attempt(ItemStack stack, int level, Level world, Player player,
                                  CastSource source, boolean cooldown, String slot, Operation<Boolean> original) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return original.call(stack, level, world, player, source, cooldown, slot);
        }
        BookAttributeHandler.refresh(serverPlayer);
        AbstractSpell spell = (AbstractSpell) (Object) this;
        MagicData magic = MagicData.getPlayerMagicData(player);
        Diagnostics.log("CAST_REQUEST", () -> Diagnostics.player(player) + " spell=" + spell.getSpellId() + " level=" + level
                + " source=" + source + " equipmentSlot=" + slot + " alreadyCasting=" + magic.isCasting()
                + " input=" + Diagnostics.stack(stack, true));
        if (magic.isCasting()) return original.call(stack, level, world, player, source, cooldown, slot);
        var context = SpellCastHooks.resolveSource(player, stack, slot, spell.getSpellId(), level);
        int added = context == null ? 0 : context.data().lvl();
        int boosted = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, (long) level + added));
        var snapshot = SpellCastHooks.capture(player, spell, boosted, context);
        if (magic.getPlayerRecasts().hasRecastForSpell(spell)) {
            var recast = magic.getPlayerRecasts().getRecastInstance(spell.getSpellId());
            if (recast instanceof SpellCastHooks.SnapshotCarrier carrier && carrier.apoth$getSnapshot() != null) {
                var stored = carrier.apoth$getSnapshot();
                if (stored.owner().equals(player.getUUID()) && stored.spellId().equals(spell.getSpellId())) {
                    snapshot = stored;
                    boosted = snapshot.spellLevel();
                }
            }
        }
        var resolvedSnapshot = snapshot;
        int resolvedLevel = boosted;
        Diagnostics.log("CAST_RESOLVE", () -> Diagnostics.player(player) + " spell=" + spell.getSpellId()
                + " originalLevel=" + level + " effectiveLevel=" + resolvedLevel + " physicalSlot="
                + (context == null ? "UNRESOLVED" : context.spellSlotIndex())
                + " resolvedSource=" + Diagnostics.stack(context == null ? null : context.stack(), false)
                + " data=" + resolvedSnapshot.data() + " effects=" + resolvedSnapshot.effects()
                + " attributes=" + resolvedSnapshot.attributes());
        if (context != null) Diagnostics.log("CAST_AFFIX_REGISTRY", () -> {
            var data = context.stack().getItem() instanceof io.redspace.ironsspellbooks.item.Scroll
                    ? ReforgeCache.scrollAffixData(context.stack())
                    : ReforgeCache.getBookAffix(context.stack(), context.spellSlotIndex());
            return Diagnostics.player(player) + " spell=" + spell.getSpellId() + ' ' + Diagnostics.affixes(data);
        });
        try (var scope = SpellCastHooks.enter(snapshot, serverPlayer)) {
            double manaBefore = magic.getMana();
            boolean accepted = original.call(stack, boosted, world, player, source, cooldown, slot);
            Diagnostics.log("CAST_RESULT", () -> Diagnostics.player(player) + " spell=" + spell.getSpellId()
                    + " accepted=" + accepted + " manaBefore=" + manaBefore + " manaAfter=" + magic.getMana()
                    + " isCasting=" + magic.isCasting());
            if (accepted && magic.isCasting()) {
                SpellCastHooks.begin(player, snapshot);
                SpellEffectHandler.duringCast(serverPlayer, snapshot);
            }
            return accepted;
        }
    }

    @WrapMethod(method = "castSpell")
    private void apoth_cast(Level world, int level, ServerPlayer player, CastSource source,
                            boolean cooldown, Operation<Void> original) {
        AbstractSpell spell = (AbstractSpell) (Object) this;
        var snapshot = SpellCastHooks.active(player, spell.getSpellId());
        var activeSnapshot = snapshot;
        Diagnostics.log("CAST_EXECUTE", () -> Diagnostics.player(player) + " spell=" + spell.getSpellId()
                + " level=" + level + " source=" + source + " activeSnapshot=" + activeSnapshot
                + " surroundingSnapshot=" + SpellCastHooks.currentSnapshot());
        if (snapshot == null) {
            var surrounding = SpellCastHooks.currentSnapshot();
            snapshot = surrounding != null && surrounding.owner().equals(player.getUUID())
                    && surrounding.spellId().equals(spell.getSpellId())
                    ? surrounding : SpellCastHooks.capture(player, spell, level, null);
        }
        try (var scope = SpellCastHooks.enter(snapshot, player)) {
            original.call(world, level, player, source, cooldown);
        }
    }

    @WrapOperation(method = "castSpell", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/magic/MagicData;setMana(F)V"))
    private void apoth_paidMana(MagicData magic, float remaining, Operation<Void> original,
                               @com.llamalad7.mixinextras.sugar.Share("apoth_paid_mana") com.llamalad7.mixinextras.sugar.ref.LocalFloatRef paidMana) {
        float before = magic.getMana();
        original.call(magic, remaining);
        paidMana.set(Math.max(0, before - magic.getMana()));
        var snapshot = SpellCastHooks.currentSnapshot();
        if (snapshot != null) Diagnostics.calculation("MANA_SPEND", snapshot.owner() + ":" + snapshot.spellId(),
                "before=" + before + " requestedRemaining=" + remaining + " after=" + magic.getMana() + " paid=" + paidMana.get());
    }

    @WrapOperation(method = "castSpell", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;onCast(Lnet/minecraft/world/level/Level;ILnet/minecraft/world/entity/LivingEntity;Lio/redspace/ironsspellbooks/api/spells/CastSource;Lio/redspace/ironsspellbooks/api/magic/MagicData;)V"))
    private void apoth_success(AbstractSpell spell, Level world, int level, LivingEntity caster,
                               CastSource source, MagicData magic, Operation<Void> original,
                               @com.llamalad7.mixinextras.sugar.Share("apoth_paid_mana") com.llamalad7.mixinextras.sugar.ref.LocalFloatRef paidMana) {
        original.call(spell, world, level, caster, source, magic);
        if (caster instanceof ServerPlayer player) {
            SpellCastHooks.successful(player, spell.getSpellId());
            SpellEffectHandler.afterCast(player, spell, level, source, magic, SpellCastHooks.currentSnapshot(), paidMana.get());
        }
    }

    @WrapMethod(method = "onServerCastComplete")
    private void apoth_complete(Level world, int level, LivingEntity caster, MagicData magic,
                                boolean cancelled, Operation<Void> original) {
        AbstractSpell spell = (AbstractSpell) (Object) this;
        boolean completed = false;
        try {
            original.call(world, level, caster, magic, cancelled);
            completed = true;
        } finally {
            if (caster instanceof ServerPlayer player) {
                var snapshot = SpellCastHooks.finish(player, spell.getSpellId(), cancelled || !completed);
                boolean completedCall = completed;
                Diagnostics.log("CAST_COMPLETE", () -> Diagnostics.player(player) + " spell=" + spell.getSpellId()
                        + " cancelled=" + cancelled + " completedCall=" + completedCall + " finishedSnapshot=" + snapshot);
                if (snapshot != null) SpellEffectHandler.afterCompletion(player, snapshot.effects());
            }
        }
    }

    @Inject(method = "getManaCost", at = @At("RETURN"), cancellable = true)
    private void apoth_manaCost(int level, CallbackInfoReturnable<Integer> cir) {
        var context = SpellCastHooks.get();
        if (context == null || !context.castContext()
                || !SpellCastHooks.matches((AbstractSpell) (Object) this, null)) return;
        int before = cir.getReturnValueI();
        cir.setReturnValue(Math.max(0, Math.round(before * context.data().mana())));
        if (context.caster() != null) Diagnostics.calculation("MANA_CALC", context.caster().getUUID() + ":" + ((AbstractSpell) (Object) this).getSpellId(),
                "level=" + level + " before=" + before + " multiplier=" + context.data().mana() + " after=" + cir.getReturnValueI());
    }

    @Inject(method = "getSpellPower", at = @At("RETURN"), cancellable = true)
    private void apoth_spellPower(int level, net.minecraft.world.entity.Entity caster, CallbackInfoReturnable<Float> cir) {
        if (!SpellCastHooks.matches((AbstractSpell) (Object) this, caster)) return;
        float before = cir.getReturnValueF();
        cir.setReturnValue(before * SpellCastHooks.get().data().dmg());
        var context = SpellCastHooks.get();
        if (context.castContext() && context.caster() != null) Diagnostics.calculation("POWER_CALC",
                context.caster().getUUID() + ":" + ((AbstractSpell) (Object) this).getSpellId(),
                "level=" + level + " before=" + before + " multiplier=" + context.data().dmg() + " after=" + cir.getReturnValueF());
    }

    @Inject(method = "getEffectiveCastTime", at = @At("RETURN"), cancellable = true)
    private void apoth_castTime(int level, LivingEntity caster, CallbackInfoReturnable<Integer> cir) {
        var context = SpellCastHooks.get();
        if (context == null || !context.castContext()
                || !SpellCastHooks.matches((AbstractSpell) (Object) this, caster)) return;
        int before = cir.getReturnValueI();
        cir.setReturnValue(Math.max(0, Math.round(before * context.data().cast())));
        if (context.caster() != null) Diagnostics.calculation("CAST_TIME_CALC",
                context.caster().getUUID() + ":" + ((AbstractSpell) (Object) this).getSpellId(),
                "level=" + level + " before=" + before + " multiplier=" + context.data().cast() + " after=" + cir.getReturnValueI());
    }
}
