package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.SiphoningBeamRange;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class SiphoningRangeEntityMixin implements SiphoningBeamRange.Data {
    @Unique private static final EntityDataAccessor<Float> apoth$SIPHONING_RANGE =
            SynchedEntityData.defineId(Player.class, EntityDataSerializers.FLOAT);

    @Inject(method = "defineSynchedData", at = @At("TAIL"), require = 1)
    private void apoth_defineSiphoningRange(CallbackInfo ci) {
        ((Player) (Object) this).getEntityData().define(apoth$SIPHONING_RANGE, 0f);
    }

    @Override
    public float apoth$getSiphoningRange() {
        return ((Player) (Object) this).getEntityData().get(apoth$SIPHONING_RANGE);
    }

    @Override
    public void apoth$setSiphoningRange(float range) {
        ((Player) (Object) this).getEntityData().set(apoth$SIPHONING_RANGE, range);
    }
}
