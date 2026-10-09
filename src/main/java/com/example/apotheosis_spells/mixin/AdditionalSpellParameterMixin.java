package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.SpellCastHooks;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public final class AdditionalSpellParameterMixin {
    @Mixin(targets = {
            "io.redspace.ironsspellbooks.spells.eldritch.TelekinesisSpell",
            "io.redspace.ironsspellbooks.spells.evocation.FirecrackerSpell",
            "io.redspace.ironsspellbooks.spells.nature.StompSpell"
    }, remap = false)
    public static class IntegerRange {
        @Inject(method = "getRange", at = @At("RETURN"), cancellable = true, require = 1)
        private void apoth_range(int level, LivingEntity caster, CallbackInfoReturnable<Integer> cir) {
            if (!SpellCastHooks.matches((AbstractSpell) (Object) this, caster)) return;
            float multiplier = SpellCastHooks.get().data().radius();
            if (multiplier != 1) cir.setReturnValue(Math.max(0, Math.round(cir.getReturnValueI() * multiplier)));
        }
    }

    @Mixin(targets = "io.redspace.ironsspellbooks.spells.ender.StarfallSpell", remap = false)
    public static class CasterRadius {
        @Inject(method = "getRadius", at = @At("RETURN"), cancellable = true, require = 1)
        private void apoth_radius(LivingEntity caster, CallbackInfoReturnable<Float> cir) {
            if (!SpellCastHooks.matches((AbstractSpell) (Object) this, caster)) return;
            float multiplier = SpellCastHooks.get().data().radius();
            if (multiplier != 1) cir.setReturnValue(cir.getReturnValueF() * multiplier);
        }
    }

    @Mixin(targets = {
            "io.redspace.ironsspellbooks.spells.blood.BloodStepSpell",
            "io.redspace.ironsspellbooks.spells.ender.TeleportSpell",
            "io.redspace.ironsspellbooks.spells.ice.FrostStepSpell",
            "io.redspace.ironsspellbooks.spells.lightning.ThunderStepSpell",
            "io.redspace.ironsspellbooks.spells.fire.WallOfFireSpell"
    }, remap = false)
    public static class Distance {
        @Inject(method = {"getDistance", "getWallLength"}, at = @At("RETURN"), cancellable = true, require = 1)
        private void apoth_distance(int level, LivingEntity caster, CallbackInfoReturnable<Float> cir) {
            if (!SpellCastHooks.matches((AbstractSpell) (Object) this, caster)) return;
            float multiplier = SpellCastHooks.get().data().radius();
            if (multiplier != 1) cir.setReturnValue(cir.getReturnValueF() * multiplier);
        }
    }

    @Mixin(targets = {
            "io.redspace.ironsspellbooks.spells.ice.SnowballSpell",
            "io.redspace.ironsspellbooks.spells.ice.IceTombSpell"
    }, remap = false)
    public static class FloatDuration {
        @Inject(method = "getDuration", at = @At("RETURN"), cancellable = true, require = 1)
        private void apoth_duration(int level, LivingEntity caster, CallbackInfoReturnable<Float> cir) {
            if (!SpellCastHooks.matches((AbstractSpell) (Object) this, caster)) return;
            float multiplier = SpellCastHooks.get().data().duration();
            if (multiplier != 1) cir.setReturnValue(Math.max(0, cir.getReturnValueF() * multiplier));
        }
    }

    @Pseudo
    @Mixin(targets = {
            "io.redspace.ironsspellbooks.spells.ender.GravityFissureSpell",
            "io.redspace.ironsspellbooks.spells.fire.RaiseHellSpell",
            "io.redspace.ironsspellbooks.spells.ice.BlizzardSpell",
            "io.redspace.ironsspellbooks.spells.ender.ArcaneShackleSpell",
            "io.redspace.ironsspellbooks.spells.evocation.FangSwirlSpell"
    }, remap = false)
    public static class NamedRadius {
        @Inject(method = {"getRadius", "getLashRadius", "getSwirlRadius"}, at = @At("RETURN"), cancellable = true, require = 1)
        private void apoth_radius(int level, LivingEntity caster, CallbackInfoReturnable<Float> cir) {
            var spell = (AbstractSpell) (Object) this;
            if (!SpellCastHooks.matches(spell, caster)) return;
            float multiplier = SpellCastHooks.get().data().radius();
            if (multiplier == 1) return;
            float radius = cir.getReturnValueF() * multiplier;
            cir.setReturnValue(switch (spell.getSpellId()) {
                case "irons_spellbooks:gravity_fissure" -> Mth.clamp(radius, 0, 48);
                case "irons_spellbooks:blizzard", "irons_spellbooks:fang_swirl", "irons_spellbooks:raise_hell" -> Mth.clamp(radius, 0, 32);
                default -> radius;
            });
        }
    }

    @Pseudo
    @Mixin(targets = {
            "io.redspace.ironsspellbooks.spells.ender.GravityFissureSpell",
            "io.redspace.ironsspellbooks.spells.ice.BlizzardSpell",
            "io.redspace.ironsspellbooks.spells.lightning.ThunderstormSpell",
            "io.redspace.ironsspellbooks.spells.ender.ArcaneShackleSpell",
            "io.redspace.ironsspellbooks.spells.evocation.FangSwirlSpell",
            "io.redspace.ironsspellbooks.spells.holy.AngelWingsSpell",
            "io.redspace.ironsspellbooks.spells.nature.AcidOrbSpell",
            "io.redspace.ironsspellbooks.spells.ender.PortalSpell"
    }, remap = false)
    public static class NamedDuration {
        @Inject(method = {"getDurationTicks", "getChainDuration", "getSwirlDurationTicks", "getEffectDuration", "getRendDuration", "getPortalDuration"},
                at = @At("RETURN"), cancellable = true, require = 1)
        private void apoth_duration(int level, LivingEntity caster, CallbackInfoReturnable<Integer> cir) {
            if (!SpellCastHooks.matches((AbstractSpell) (Object) this, caster)) return;
            float multiplier = SpellCastHooks.get().data().duration();
            if (multiplier != 1) cir.setReturnValue(Math.max(0, Math.round(cir.getReturnValueI() * multiplier)));
        }
    }

    @Pseudo
    @Mixin(targets = "io.redspace.ironsspellbooks.spells.evocation.FangSwirlSpell", remap = false)
    public static class FangRange {
        @Inject(method = "getRange", at = @At("RETURN"), cancellable = true, require = 1)
        private void apoth_range(int level, LivingEntity caster, CallbackInfoReturnable<Float> cir) {
            if (!SpellCastHooks.matches((AbstractSpell) (Object) this, caster)) return;
            float multiplier = SpellCastHooks.get().data().radius();
            if (multiplier != 1) cir.setReturnValue(cir.getReturnValueF() * multiplier);
        }
    }

    @Mixin(targets = "io.redspace.ironsspellbooks.spells.ender.PortalSpell", remap = false)
    public static class PortalDistance {
        @Inject(method = "getCastDistance", at = @At("RETURN"), cancellable = true, require = 1)
        private void apoth_range(int level, LivingEntity caster, CallbackInfoReturnable<Float> cir) {
            if (!SpellCastHooks.matches((AbstractSpell) (Object) this, caster)) return;
            float multiplier = SpellCastHooks.get().data().radius();
            if (multiplier != 1) cir.setReturnValue(cir.getReturnValueF() * multiplier);
        }
    }

    @Pseudo
    @Mixin(targets = "io.redspace.ironsspellbooks.spells.fire.SoulfireRaySpell", remap = false)
    public static class SoulfireRange {
        @Inject(method = "getRange", at = @At("RETURN"), cancellable = true, require = 1)
        private static void apoth_range(int level, LivingEntity caster, CallbackInfoReturnable<Float> cir) {
            var ctx = SpellCastHooks.get();
            if (ctx == null || ctx.data() == null || ctx.spellData() == null
                    || !ctx.spellData().getSpell().getSpellId().equals("irons_spellbooks:soulfire_ray")
                    || !SpellCastHooks.matches(ctx.spellData().getSpell(), caster)) return;
            float multiplier = ctx.data().radius();
            if (multiplier != 1) cir.setReturnValue(cir.getReturnValueF() * multiplier);
        }
    }
}
