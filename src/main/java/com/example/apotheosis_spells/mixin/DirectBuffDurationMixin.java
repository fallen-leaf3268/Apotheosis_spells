package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.SpellCastHooks;
import com.llamalad7.mixinextras.sugar.Local;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(targets = {
        "io.redspace.ironsspellbooks.spells.blood.HeartstopSpell",
        "io.redspace.ironsspellbooks.spells.eldritch.AbyssalShroudSpell",
        "io.redspace.ironsspellbooks.spells.eldritch.PlanarSightSpell",
        "io.redspace.ironsspellbooks.spells.ender.EchoingStrikesSpell",
        "io.redspace.ironsspellbooks.spells.ender.EvasionSpell",
        "io.redspace.ironsspellbooks.spells.holy.FortifySpell",
        "io.redspace.ironsspellbooks.spells.ice.FrostbiteSpell",
        "io.redspace.ironsspellbooks.spells.lightning.ChargeSpell",
        "io.redspace.ironsspellbooks.spells.nature.GluttonySpell",
        "io.redspace.ironsspellbooks.spells.nature.OakskinSpell",
        "io.redspace.ironsspellbooks.spells.nature.SpiderAspectSpell"
}, remap = false)
public class DirectBuffDurationMixin {
    @ModifyArg(method = {"onCast", "lambda$onCast$0"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/effect/MobEffectInstance;<init>(Lnet/minecraft/world/effect/MobEffect;IIZZZ)V"), index = 1, require = 1)
    private int apoth_duration(int duration) {
        var ctx = SpellCastHooks.get();
        if (ctx == null || !ctx.castContext() || !SpellCastHooks.matches((AbstractSpell) (Object) this, ctx.caster())) return duration;
        float multiplier = ctx.data().duration();
        return multiplier == 1 ? duration : Math.max(0, Math.round(duration * multiplier));
    }

    @ModifyArg(method = "getUniqueInfo", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/util/Utils;timeFromTicks(FI)Ljava/lang/String;"), index = 0, require = 0)
    private float apoth_displayDuration(float duration, @Local(argsOnly = true) LivingEntity caster) {
        if (!SpellCastHooks.matches((AbstractSpell) (Object) this, caster)) return duration;
        float multiplier = SpellCastHooks.get().data().duration();
        return multiplier == 1 ? duration : Math.max(0, Math.round((int) duration * multiplier));
    }
}
