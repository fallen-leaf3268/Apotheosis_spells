package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.SpellCastHooks;
import io.redspace.ironsspellbooks.entity.spells.AoeEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Set;

@Mixin(value = AoeEntity.class, remap = false)
public class AoeScaleMixin {

    private static final Set<String> APOTH_CAPTURED_DURATION_SPELLS = Set.of(
            "irons_spellbooks:healing_circle",
            "irons_spellbooks:poison_splash",
            "irons_spellbooks:snowball",
            "irons_spellbooks:fang_swirl",
            "irons_spellbooks:blizzard",
            "irons_spellbooks:echoing_strikes");

    private static boolean apoth_preservesCapturedDuration(SpellCastHooks.Context ctx) {
        return ctx.spellData() != null && ctx.spellData().getSpell() != null
                && APOTH_CAPTURED_DURATION_SPELLS.contains(ctx.spellData().getSpell().getSpellId());
    }

    @ModifyVariable(method = "setDuration", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int apoth_scaleDuration(int duration) {
        var ctx = SpellCastHooks.get();
        if (((Entity) (Object) this).isAddedToWorld() || ctx == null || !ctx.castContext()
                || ctx.data() == null || ctx.data().duration() == 1f || apoth_preservesCapturedDuration(ctx)) return duration;
        return Math.max(0, Math.round(duration * ctx.data().duration()));
    }

    @ModifyVariable(method = "setEffectDuration", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int apoth_scaleEffectDuration(int duration) {
        var ctx = SpellCastHooks.get();
        if (((Entity) (Object) this).isAddedToWorld() || ctx == null || !ctx.castContext()
                || ctx.data() == null || ctx.data().duration() == 1f || apoth_preservesCapturedDuration(ctx)) return duration;
        return Math.max(0, Math.round(duration * ctx.data().duration()));
    }
}
