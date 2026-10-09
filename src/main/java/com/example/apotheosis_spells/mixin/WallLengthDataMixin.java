package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.SpellCastHooks;
import io.redspace.ironsspellbooks.spells.fire.WallOfFireSpell;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "io.redspace.ironsspellbooks.spells.fire.WallOfFireSpell$FireWallData", remap = false)
public class WallLengthDataMixin {
    @Shadow public float maxTotalDistance;
    @Shadow public float accumulatedDistance;
    @Unique private boolean apoth$capturedLength;

    @Inject(method = "<init>(Lio/redspace/ironsspellbooks/spells/fire/WallOfFireSpell;F)V", at = @At("RETURN"), require = 1)
    private void apoth_captureLength(WallOfFireSpell spell, float length, CallbackInfo ci) {
        var ctx = SpellCastHooks.get();
        apoth$capturedLength = ctx != null && ctx.castContext() && ctx.data().radius() != 1
                && SpellCastHooks.matches(spell, ctx.caster());
    }

    @Inject(method = "serializeNBT()Lnet/minecraft/nbt/CompoundTag;", at = @At("RETURN"), require = 1)
    private void apoth_saveLength(CallbackInfoReturnable<CompoundTag> cir) {
        if (!apoth$capturedLength) return;
        var length = new CompoundTag();
        length.putFloat("Maximum", maxTotalDistance);
        length.putFloat("Used", accumulatedDistance);
        cir.getReturnValue().put("apotheosis_spells:wall_length", length);
    }

    @Inject(method = "deserializeNBT(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"), require = 1)
    private void apoth_loadLength(CompoundTag tag, CallbackInfo ci) {
        apoth$capturedLength = false;
        if (!tag.contains("apotheosis_spells:wall_length", Tag.TAG_COMPOUND)) return;
        var length = tag.getCompound("apotheosis_spells:wall_length");
        float maximum = length.getFloat("Maximum");
        float used = length.getFloat("Used");
        if (!Float.isFinite(maximum) || !Float.isFinite(used) || maximum < 0 || used < 0) return;
        maxTotalDistance = maximum;
        accumulatedDistance = used;
        apoth$capturedLength = true;
    }
}
