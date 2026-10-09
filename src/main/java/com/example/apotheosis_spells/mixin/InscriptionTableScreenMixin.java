package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.SpellCastHooks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellSlot;
import io.redspace.ironsspellbooks.gui.inscription_table.InscriptionTableMenu;
import io.redspace.ironsspellbooks.gui.inscription_table.InscriptionTableScreen;
import io.redspace.ironsspellbooks.item.SpellBook;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Mixin(value = InscriptionTableScreen.class, remap = false)
public class InscriptionTableScreenMixin {

    @Shadow
    private int selectedSpellIndex;

    @WrapMethod(method = "renderLorePage")
    private void apoth_renderLorePage(net.minecraft.client.gui.GuiGraphics guiHelper, float partialTick,
                                      int mouseX, int mouseY, Operation<Void> original) {
        SpellCastHooks.Context context = apoth_context();
        if (context == null) {
            original.call(guiHelper, partialTick, mouseX, mouseY);
            return;
        }
        try (var scope = SpellCastHooks.enter(context)) {
            original.call(guiHelper, partialTick, mouseX, mouseY);
        }
    }

    @Redirect(method = "renderLorePage", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/SpellSlot;getLevel()I"))
    private int apoth_level(SpellSlot slot) {
        int base = slot.getSpell().getLevelFor(slot.getLevel(), Minecraft.getInstance().player);
        var ctx = SpellCastHooks.get();
        return (ctx != null && ctx.data() != null && ctx.data().lvl() > 0) ? base + ctx.data().lvl() : base;
    }

    @WrapOperation(method = "renderLorePage", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getUniqueInfo(ILnet/minecraft/world/entity/LivingEntity;)Ljava/util/List;"))
    private List<MutableComponent> apoth_uniqueInfo(AbstractSpell spell, int spellLevel, LivingEntity caster,
                                                    Operation<List<MutableComponent>> original) {
        LivingEntity c = caster != null ? caster : Minecraft.getInstance().player;
        return original.call(spell, spellLevel, c);
    }

    @Redirect(method = "renderLorePage", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getManaCost(I)I"))
    private int apoth_manaCost(AbstractSpell spell, int level) {
        var ctx = SpellCastHooks.get();
        int base = spell.getManaCost(level);
        if (ctx == null || ctx.data() == null || ctx.data().mana() == 1f) return base;
        return Math.max(0, Math.round(base * ctx.data().mana()));
    }

    @Redirect(method = "renderLorePage", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getSpellCooldown()I"))
    private int apoth_cooldown(AbstractSpell spell) {
        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            return io.redspace.ironsspellbooks.capabilities.magic.MagicManager.getEffectiveSpellCooldown(
                    spell, player, io.redspace.ironsspellbooks.api.spells.CastSource.SPELLBOOK);
        }
        var ctx = SpellCastHooks.get();
        int base = spell.getSpellCooldown();
        if (ctx != null && ctx.data() != null && ctx.data().cd() != 1f) return Math.max(0, Math.round(base * ctx.data().cd()));
        return base;
    }

    @Redirect(method = "renderLorePage", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getEffectiveCastTime(ILnet/minecraft/world/entity/LivingEntity;)I"))
    private int apoth_castTime(AbstractSpell spell, int spellLevel, LivingEntity entity) {
        var ctx = SpellCastHooks.get();
        LivingEntity castEntity = entity != null ? entity : Minecraft.getInstance().player;
        int base = spell.getEffectiveCastTime(spellLevel, castEntity);
        if (ctx == null || ctx.data() == null || ctx.data().cast() == 1f) return base;
        return Math.max(0, Math.round(base * ctx.data().cast()));
    }

    @Unique
    private SpellCastHooks.Context apoth_context() {
        Player player = Minecraft.getInstance().player;
        if (player == null || selectedSpellIndex < 0) return null;
        InscriptionTableScreen screen = (InscriptionTableScreen) (Object) this;
        InscriptionTableMenu menu = screen.getMenu();
        ItemStack bookStack = menu.getSpellBookSlot().getItem();
        if (bookStack.isEmpty() || !(bookStack.getItem() instanceof SpellBook)) return null;
        ISpellContainer container = ISpellContainer.get(bookStack);
        if (container == null) return null;
        SpellSlot[] slots = container.getAllSpells();
        if (selectedSpellIndex >= slots.length) return null;
        SpellSlot spellSlot = slots[selectedSpellIndex];
        if (spellSlot == null || spellSlot.spellData() == null || spellSlot.spellData().getSpell() == null) return null;
        return SpellCastHooks.buildContext(bookStack, player, spellSlot.index(), spellSlot.getLevel(), spellSlot.spellData());
    }
}
