package com.example.apotheosis_spells.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.redspace.ironsspellbooks.entity.spells.AbstractConeProjectile;
import io.redspace.ironsspellbooks.entity.spells.ConePart;
import io.redspace.ironsspellbooks.entity.spells.gust.GustCollider;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = AbstractConeProjectile.class, remap = false)
public abstract class GustGeometryMixin {
    @WrapOperation(method = "tick", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lio/redspace/ironsspellbooks/entity/spells/ConePart;setPos(Lnet/minecraft/world/phys/Vec3;)V"), require = 1)
    private void apoth_scaleGustGeometry(ConePart part, Vec3 nativePosition, Operation<Void> original) {
        if (!((Object) this instanceof GustCollider gust)) {
            original.call(part, nativePosition);
            return;
        }
        float scale = gust.range / 8f;
        if (scale == 1 || scale <= 0 || !Float.isFinite(scale)) {
            original.call(part, nativePosition);
            return;
        }
        var origin = gust.position();
        var position = origin.add(nativePosition.subtract(origin).scale(scale));
        var size = part.getDimensions(part.getPose()).scale(scale);
        original.call(part, position);
        part.setBoundingBox(new AABB(position.x - size.width / 2, position.y, position.z - size.width / 2,
                position.x + size.width / 2, position.y + size.height, position.z + size.width / 2));
    }
}
