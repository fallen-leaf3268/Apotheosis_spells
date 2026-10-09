package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.DelayedSpellEffectHandler;
import com.example.apotheosis_spells.handler.SpellCastHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.redspace.ironsspellbooks.registries.MobEffectRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MobEffectInstance.class)
public class MobEffectSnapshotMixin implements SpellCastHooks.SnapshotCarrier {
    @Unique private SpellCastHooks.Snapshot apoth$snapshot;

    @Override
    public SpellCastHooks.Snapshot apoth$getSnapshot() {
        return apoth$snapshot;
    }

    @Override
    public void apoth$setSnapshot(SpellCastHooks.Snapshot snapshot) {
        apoth$snapshot = DelayedSpellEffectHandler.valid(((MobEffectInstance) (Object) this).getEffect(), snapshot);
    }

    @Inject(method = "setDetailsFrom", at = @At("TAIL"))
    private void apoth_copySource(MobEffectInstance incoming, CallbackInfo ci) {
        apoth$setSnapshot(((SpellCastHooks.SnapshotCarrier) (Object) incoming).apoth$getSnapshot());
    }

    @Inject(method = "update", require = 2, allow = 2, at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/world/effect/MobEffectInstance;duration:I", shift = At.Shift.AFTER))
    private void apoth_adoptSource(MobEffectInstance incoming, CallbackInfoReturnable<Boolean> cir) {
        apoth$setSnapshot(((SpellCastHooks.SnapshotCarrier) (Object) incoming).apoth$getSnapshot());
    }

    @Inject(method = "save", at = @At("RETURN"))
    private void apoth_saveSource(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        if (apoth$snapshot != null) cir.getReturnValue().put(SpellCastHooks.SNAPSHOT_KEY, apoth$snapshot.write());
        else cir.getReturnValue().remove(SpellCastHooks.SNAPSHOT_KEY);
    }

    @Inject(method = "loadSpecifiedEffect", at = @At("RETURN"))
    private static void apoth_loadSource(MobEffect effect, CompoundTag tag, CallbackInfoReturnable<MobEffectInstance> cir) {
        var instance = cir.getReturnValue();
        if (instance != null && tag.contains(SpellCastHooks.SNAPSHOT_KEY, Tag.TAG_COMPOUND)) {
            ((SpellCastHooks.SnapshotCarrier) (Object) instance).apoth$setSnapshot(
                    SpellCastHooks.Snapshot.read(tag.getCompound(SpellCastHooks.SNAPSHOT_KEY)));
        }
    }

    @WrapOperation(method = "applyEffect", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/effect/MobEffect;applyEffectTick(Lnet/minecraft/world/entity/LivingEntity;I)V"))
    private void apoth_tickSource(MobEffect effect, LivingEntity caster, int amplifier, Operation<Void> original) {
        if (effect != MobEffectRegistry.THUNDERSTORM.get() || caster.level().isClientSide) {
            original.call(effect, caster, amplifier);
            return;
        }
        var snapshot = DelayedSpellEffectHandler.snapshot((MobEffectInstance) (Object) this, caster);
        try (var scope = DelayedSpellEffectHandler.enter(snapshot, caster)) {
            original.call(effect, caster, amplifier);
        }
    }
}
