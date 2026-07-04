package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.handler.SpellCastHooks;
import io.redspace.ironsspellbooks.api.magic.SpellSelectionManager;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.api.spells.SpellSlot;
import io.redspace.ironsspellbooks.gui.overlays.SpellWheelOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mixin(value = SpellWheelOverlay.class, remap = false)
public class SpellWheelMixin {

    @Unique
    private static final Map<ResourceLocation, ReforgeCache.Data> SPELL_DATA_MAP = new HashMap<>();

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getLevelFor(ILnet/minecraft/world/entity/LivingEntity;)I"))
    private int redirectGetLevelFor(AbstractSpell spell, int level, LivingEntity caster) {
        ReforgeCache.Data d = SPELL_DATA_MAP.get(spell.getSpellResource());
        int result = spell.getLevelFor(level, caster);
        return (d != null && d.lvl() > 0) ? result + d.lvl() : result;
    }

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getSpellPower(ILnet/minecraft/world/entity/Entity;)F"))
    private float redirectGetSpellPower(AbstractSpell spell, int spellLevel, net.minecraft.world.entity.Entity source) {
        return spell.getSpellPower(spellLevel, source);
    }

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getManaCost(I)I"))
    private int redirectGetManaCost(AbstractSpell spell, int level) {
        ReforgeCache.Data d = SPELL_DATA_MAP.get(spell.getSpellResource());
        int base = spell.getManaCost(level);
        if (d == null || d.mana() == 1f) return base;
        return Math.max(0, Math.round(base * d.mana()));
    }

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getEffectiveCastTime(ILnet/minecraft/world/entity/LivingEntity;)I"))
    private int redirectGetEffectiveCastTime(AbstractSpell spell, int spellLevel, LivingEntity entity) {
        ReforgeCache.Data d = SPELL_DATA_MAP.get(spell.getSpellResource());
        int base = spell.getEffectiveCastTime(spellLevel, entity);
        if (d == null || d.cast() == 1f) return base;
        return Math.max(0, Math.round(base * d.cast()));
    }

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/util/TooltipsUtils;getLevelComponenet(Lio/redspace/ironsspellbooks/api/spells/SpellData;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/network/chat/MutableComponent;"))
    private MutableComponent redirectGetLevelComponenet(SpellData spellData, LivingEntity caster) {
        int stored = spellData.getLevel();
        int level = spellData.getSpell().getLevelFor(stored, caster);
        ReforgeCache.Data d = SPELL_DATA_MAP.get(spellData.getSpell().getSpellResource());
        int diff = (d != null && d.lvl() > 0) ? d.lvl() : 0;
        level += diff;
        int diffFromStored = level - stored;
        if (diffFromStored > 0) return net.minecraft.network.chat.Component.literal(level + " (+" + diffFromStored + ")");
        if (diffFromStored < 0) return net.minecraft.network.chat.Component.literal(level + " (" + diffFromStored + ")");
        return net.minecraft.network.chat.Component.literal(String.valueOf(level));
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderHead(CallbackInfo ci) {
        SpellCastHooks.clear();
        SPELL_DATA_MAP.clear();
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;
        ItemStack bookStack = io.redspace.ironsspellbooks.api.util.Utils.getPlayerSpellbookStack(player);
        if (bookStack == null || bookStack.isEmpty()) return;
        if (!(bookStack.getItem() instanceof io.redspace.ironsspellbooks.item.SpellBook)) return;

        io.redspace.ironsspellbooks.api.spells.ISpellContainer container =
                io.redspace.ironsspellbooks.api.spells.ISpellContainer.get(bookStack);
        for (SpellSlot spellSlot : container.getActiveSpells()) {
            AbstractSpell spell = spellSlot.spellData().getSpell();
            if (spell != null) {
                ReforgeCache.Data d = ReforgeCache.getFromSpellBook(bookStack, spellSlot.index());
                if (d != null) SPELL_DATA_MAP.put(spell.getSpellResource(), d);
            }
        }

        SpellSelectionManager ssm = new SpellSelectionManager(player);
        var sel = ssm.getSelection();
        if (sel != null) {
            AbstractSpell cachedSpell = sel.spellData.getSpell();
            if (cachedSpell != null) {
                ReforgeCache.Data d = SPELL_DATA_MAP.get(cachedSpell.getSpellResource());
                if (d == null) d = ReforgeCache.Data.DEF;
                SpellCastHooks.set(new SpellCastHooks.Context(bookStack, player, -1, sel.spellData.getLevel(), d, sel.spellData));
            }
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void onRenderEnd(CallbackInfo ci) {
        SPELL_DATA_MAP.clear();
        SpellCastHooks.clear();
    }
}
