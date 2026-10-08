package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.SpellCastHooks;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = {
        io.redspace.ironsspellbooks.spells.fire.FireArrowSpell.class,
        io.redspace.ironsspellbooks.spells.fire.HeatSurgeSpell.class,
        io.redspace.ironsspellbooks.spells.fire.MagmaBombSpell.class,
        io.redspace.ironsspellbooks.spells.ice.FrostwaveSpell.class,
        io.redspace.ironsspellbooks.spells.ice.SnowballSpell.class,
        io.redspace.ironsspellbooks.spells.lightning.ShockwaveSpell.class,
        io.redspace.ironsspellbooks.spells.nature.AcidOrbSpell.class,
        io.redspace.ironsspellbooks.spells.ender.BlackHoleSpell.class,
        io.redspace.ironsspellbooks.spells.holy.HealingCircleSpell.class,
        io.redspace.ironsspellbooks.spells.nature.EarthquakeSpell.class
}, remap = false)
public class SpellRadiusFloatMixin {

    @Inject(method = "getRadius", at = @At("RETURN"), cancellable = true)
    private void apoth_scaleRadius(int spellLevel, LivingEntity caster, CallbackInfoReturnable<Float> cir) {
        var ctx = SpellCastHooks.get();
        var spell = (AbstractSpell) (Object) this;
        if (!SpellCastHooks.matches(spell, caster)
                || ctx.data().radius() == 1f) return;
        float radius = cir.getReturnValueF() * ctx.data().radius();
        cir.setReturnValue(switch (spell.getSpellId()) {
            case "irons_spellbooks:black_hole" -> Mth.clamp(radius, 0, 48);
            case "irons_spellbooks:healing_circle", "irons_spellbooks:earthquake" -> Mth.clamp(radius, 0, 32);
            default -> radius;
        });
    }
}
