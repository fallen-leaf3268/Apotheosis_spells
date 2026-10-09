package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.SpellCastHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.entity.mobs.IMagicSummon;
import io.redspace.ironsspellbooks.spells.blood.SacrificeSpell;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SacrificeSpell.class, remap = false)
public class SacrificeRadiusMixin {
    @Group(name = "sacrifice_radius", min = 1, max = 1)
    @Inject(method = "getRadius", at = @At("RETURN"), cancellable = true, require = 0)
    private void apoth_radius(LivingEntity target, CallbackInfoReturnable<Float> cir) {
        LivingEntity caster = target;
        if (target instanceof IMagicSummon summon && summon.getSummoner() instanceof LivingEntity owner) caster = owner;
        if (!SpellCastHooks.matches((AbstractSpell) (Object) this, caster)) return;
        float multiplier = SpellCastHooks.get().data().radius();
        if (multiplier != 1) cir.setReturnValue(cir.getReturnValueF() * multiplier);
    }

    @Group(name = "sacrifice_radius", min = 1, max = 1)
    @ModifyConstant(method = "onCast", constant = @Constant(floatValue = 3, ordinal = 0), require = 0)
    private float apoth_legacyRadius(float radius) {
        var ctx = SpellCastHooks.get();
        return ctx != null && ctx.castContext() && SpellCastHooks.matches((AbstractSpell) (Object) this, ctx.caster())
                ? radius * ctx.data().radius() : radius;
    }

    @WrapOperation(method = "getUniqueInfo", at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;"), require = 1)
    private MutableComponent apoth_displayedRadius(String key, Object[] arguments, Operation<MutableComponent> original,
                                                   @Local(argsOnly = true) LivingEntity caster) {
        if (key.equals("ui.irons_spellbooks.radius") && SpellCastHooks.matches((AbstractSpell) (Object) this, caster)
                && SpellCastHooks.get().data().radius() != 1) {
            arguments = java.util.Arrays.copyOf(arguments, arguments.length);
            arguments[0] = ((Number) arguments[0]).floatValue() * SpellCastHooks.get().data().radius();
        }
        return original.call(key, arguments);
    }
}
