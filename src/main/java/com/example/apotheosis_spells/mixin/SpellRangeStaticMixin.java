package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.SpellCastHooks;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 范围词缀作用于「射程类」法术的 <b>静态</b> getRange(int,LivingEntity)（返回 float）——射线/光束类的射程。
 * 静态目标方法需用静态处理器。
 */
@Mixin(value = {
        io.redspace.ironsspellbooks.spells.ice.RayOfFrostSpell.class,
        io.redspace.ironsspellbooks.spells.eldritch.SonicBoomSpell.class,
        io.redspace.ironsspellbooks.spells.eldritch.EldritchBlastSpell.class
}, remap = false)
public class SpellRangeStaticMixin {

    @Inject(method = "getRange", at = @At("RETURN"), cancellable = true)
    private static void apoth_scaleRange(int spellLevel, LivingEntity caster, CallbackInfoReturnable<Float> cir) {
        var ctx = SpellCastHooks.get();
        if (ctx == null || ctx.data() == null || ctx.data().radius() == 1f) return;
        if (caster != null && ctx.caster() != null && caster != ctx.caster()) return;
        if (ctx.spellData() == null || !java.util.Set.of("irons_spellbooks:ray_of_frost",
                "irons_spellbooks:sonic_boom", "irons_spellbooks:eldritch_blast")
                .contains(ctx.spellData().getSpell().getSpellId())) return;
        cir.setReturnValue(cir.getReturnValueF() * ctx.data().radius());
    }
}
