package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.SpellCastHooks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.redspace.ironsspellbooks.api.magic.SpellSelectionManager;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.gui.overlays.SpellWheelOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(value = SpellWheelOverlay.class, remap = false)
public class SpellWheelMixin {

    @Shadow
    private int wheelSelection;

    @Shadow
    private SpellSelectionManager swsm;

    @Unique private SpellCastHooks.Context apoth$renderContext;
    @Unique private SpellCastHooks.Scope apoth$renderScope;
    @Unique private boolean apoth$contextResolved;

    @WrapMethod(method = "render")
    private void apoth_render(net.minecraftforge.client.gui.overlay.ForgeGui gui,
                              net.minecraft.client.gui.GuiGraphics graphics, float partialTick,
                              int width, int height, Operation<Void> original) {
        var previousContext = apoth$renderContext;
        var previousScope = apoth$renderScope;
        boolean previousResolved = apoth$contextResolved;
        apoth$renderContext = null;
        apoth$renderScope = null;
        apoth$contextResolved = false;
        try (var ignored = SpellCastHooks.enter((SpellCastHooks.Context) null)) {
            try {
                original.call(gui, graphics, partialTick, width, height);
            } finally {
                if (apoth$renderScope != null) apoth$renderScope.close();
            }
        } finally {
            apoth$renderContext = previousContext;
            apoth$renderScope = previousScope;
            apoth$contextResolved = previousResolved;
        }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getLevelFor(ILnet/minecraft/world/entity/LivingEntity;)I"))
    private int apoth_level(AbstractSpell spell, int level, LivingEntity caster, Operation<Integer> original) {
        var context = apoth_context();
        if (context == null) return original.call(spell, level, caster);
        int result = original.call(spell, level, caster);
        return context.data().lvl() > 0 ? result + context.data().lvl() : result;
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getUniqueInfo(ILnet/minecraft/world/entity/LivingEntity;)Ljava/util/List;"))
    private List<MutableComponent> apoth_uniqueInfo(AbstractSpell spell, int level, LivingEntity caster,
                                                    Operation<List<MutableComponent>> original) {
        apoth_context();
        return original.call(spell, level, caster);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getManaCost(I)I"))
    private int apoth_manaCost(AbstractSpell spell, int level, Operation<Integer> original) {
        var context = apoth_context();
        if (context == null) return original.call(spell, level);
        int base = original.call(spell, level);
        return Math.max(0, Math.round(base * context.data().mana()));
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getEffectiveCastTime(ILnet/minecraft/world/entity/LivingEntity;)I"))
    private int apoth_castTime(AbstractSpell spell, int level, LivingEntity caster, Operation<Integer> original) {
        var context = apoth_context();
        if (context == null) return original.call(spell, level, caster);
        int base = original.call(spell, level, caster);
        return Math.max(0, Math.round(base * context.data().cast()));
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/util/TooltipsUtils;getLevelComponenet(Lio/redspace/ironsspellbooks/api/spells/SpellData;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/network/chat/MutableComponent;"))
    private MutableComponent apoth_levelComponent(SpellData spellData, LivingEntity caster,
                                                   Operation<MutableComponent> original) {
        var context = apoth_context();
        if (context == null || context.data().lvl() <= 0) return original.call(spellData, caster);
        int stored = spellData.getLevel();
        int level = spellData.getSpell().getLevelFor(stored, caster) + context.data().lvl();
        int diffFromStored = level - stored;
        if (diffFromStored > 0) return net.minecraft.network.chat.Component.literal(level + " (+" + diffFromStored + ")");
        if (diffFromStored < 0) return net.minecraft.network.chat.Component.literal(level + " (" + diffFromStored + ")");
        return net.minecraft.network.chat.Component.literal(String.valueOf(level));
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/capabilities/magic/MagicManager;getEffectiveSpellCooldown(Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;Lnet/minecraft/world/entity/player/Player;Lio/redspace/ironsspellbooks/api/spells/CastSource;)I"))
    private int apoth_cooldown(AbstractSpell spell, Player player, CastSource source, Operation<Integer> original) {
        apoth_context();
        return original.call(spell, player, source);
    }

    @Unique
    private SpellCastHooks.Context apoth_context() {
        if (apoth$contextResolved) return apoth$renderContext;
        apoth$contextResolved = true;
        Player player = Minecraft.getInstance().player;
        if (player == null || swsm == null || wheelSelection < 0 || wheelSelection >= swsm.getSpellCount()) return null;
        apoth$renderContext = SpellCastHooks.resolveSelection(player, swsm.getSpellSlot(wheelSelection));
        if (apoth$renderContext != null) apoth$renderScope = SpellCastHooks.enter(apoth$renderContext);
        return apoth$renderContext;
    }
}
