package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.BookAttributeHandler;
import com.example.apotheosis_spells.handler.SpellCastHooks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(LivingEntity.class)
public class PreviewAttributeMixin {
    @Inject(method = "getAttribute(Lnet/minecraft/world/entity/ai/attributes/Attribute;)Lnet/minecraft/world/entity/ai/attributes/AttributeInstance;",
            at = @At("HEAD"), cancellable = true)
    private void apoth_previewAttribute(Attribute attribute, CallbackInfoReturnable<AttributeInstance> cir) {
        var preview = SpellCastHooks.previewAttribute((LivingEntity) (Object) this, attribute);
        if (preview != null) cir.setReturnValue(preview);
    }

    @Inject(method = "getAttributeValue(Lnet/minecraft/world/entity/ai/attributes/Attribute;)D",
            at = @At("HEAD"), cancellable = true)
    private void apoth_previewValue(Attribute attribute, CallbackInfoReturnable<Double> cir) {
        var preview = SpellCastHooks.previewAttribute((LivingEntity) (Object) this, attribute);
        if (preview != null) cir.setReturnValue(preview.getValue());
    }

    @Mixin(AttributeMap.class)
    public static abstract class AttributeMapSource implements BookAttributeHandler.PreviewAttributeSource {
        @Shadow @Final
        private Map<Attribute, AttributeInstance> attributes;
        @Shadow @Final
        private AttributeSupplier supplier;

        @Override
        public AttributeInstance apoth$copyAttribute(Attribute attribute) {
            AttributeInstance original = attributes.get(attribute);
            if (original == null) return supplier.createInstance(instance -> {}, attribute);
            var copy = new AttributeInstance(attribute, instance -> {});
            copy.replaceFrom(original);
            return copy;
        }
    }
}
