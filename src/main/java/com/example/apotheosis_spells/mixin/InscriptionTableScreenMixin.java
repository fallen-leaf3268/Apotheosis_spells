package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.handler.SpellCastHooks;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.List;

@Mixin(value = InscriptionTableScreen.class, remap = false)
public class InscriptionTableScreenMixin {

    private static final int SPELLBOOK_SLOT = 36 + 0;

    @Inject(method = "renderLorePage", at = @At("HEAD"))
    private void onRenderLorePageHead(net.minecraft.client.gui.GuiGraphics guiHelper, float partialTick, int mouseX, int mouseY, CallbackInfo ci) {
        SpellCastHooks.clear();
        try {
            InscriptionTableScreen self = (InscriptionTableScreen) (Object) this;
            InscriptionTableMenu menu = (InscriptionTableMenu) self.getMenu();

            Player player = Minecraft.getInstance().player;
            if (player == null) return;

            ItemStack bookStack = menu.slots.get(SPELLBOOK_SLOT).getItem();
            if (bookStack.isEmpty() || !(bookStack.getItem() instanceof SpellBook)) return;

            int selectedIndex = getSelectedSpellIndex(self);
            if (selectedIndex < 0) return;

            int physicalIndex = getPhysicalIndex(self, selectedIndex);
            if (physicalIndex < 0) return;

            ReforgeCache.Data data = ReforgeCache.getFromSpellBook(bookStack, physicalIndex);
            SpellSlot spellSlot = getSpellSlot(self, selectedIndex);
            if (spellSlot == null) return;

            int spellLevel = spellSlot.getLevel();
            SpellCastHooks.set(new SpellCastHooks.Context(bookStack, player, physicalIndex, spellLevel, data, spellSlot.spellData()));
        } catch (Exception e) {
            SpellCastHooks.clear();
        }
    }

    @Inject(method = "renderLorePage", at = @At("RETURN"))
    private void onRenderLorePageReturn(net.minecraft.client.gui.GuiGraphics guiHelper, float partialTick, int mouseX, int mouseY, CallbackInfo ci) {
        SpellCastHooks.clear();
    }

    @Redirect(method = "renderLorePage", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getLevelFor(ILnet/minecraft/world/entity/LivingEntity;)I"))
    private int redirectGetLevelFor(AbstractSpell spell, int level, LivingEntity caster) {
        int result = spell.getLevelFor(level, caster);
        var ctx = SpellCastHooks.get();
        return (ctx != null && ctx.data() != null && ctx.data().lvl() > 0) ? result + ctx.data().lvl() : result;
    }

    @Redirect(method = "renderLorePage", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/SpellSlot;getLevel()I"))
    private int apoth_boostDisplayLevel(SpellSlot slot) {
        int base = slot.getLevel();
        var ctx = SpellCastHooks.get();
        return (ctx != null && ctx.data() != null && ctx.data().lvl() > 0) ? base + ctx.data().lvl() : base;
    }

    @Redirect(method = "renderLorePage", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getUniqueInfo(ILnet/minecraft/world/entity/LivingEntity;)Ljava/util/List;"))
    private List<MutableComponent> redirectGetUniqueInfo(AbstractSpell spell, int spellLevel, LivingEntity caster) {
        LivingEntity c = caster != null ? caster : Minecraft.getInstance().player;
        return spell.getUniqueInfo(spellLevel, c);
    }

    @Redirect(method = "renderLorePage", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getManaCost(I)I"))
    private int redirectGetManaCost(AbstractSpell spell, int level) {
        var ctx = SpellCastHooks.get();
        int base = spell.getManaCost(level);
        if (ctx == null || ctx.data() == null || ctx.data().mana() == 1f) return base;
        return Math.max(0, Math.round(base * ctx.data().mana()));
    }

    @Redirect(method = "renderLorePage", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getSpellCooldown()I"))
    private int redirectGetSpellCooldown(AbstractSpell spell) {
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
    private int redirectGetEffectiveCastTime(AbstractSpell spell, int spellLevel, LivingEntity entity) {
        var ctx = SpellCastHooks.get();
        LivingEntity castEntity = entity != null ? entity : Minecraft.getInstance().player;
        int base = spell.getEffectiveCastTime(spellLevel, castEntity);
        if (ctx == null || ctx.data() == null || ctx.data().cast() == 1f) return base;
        return Math.max(0, Math.round(base * ctx.data().cast()));
    }

    @Redirect(method = "renderLorePage", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/util/TooltipsUtils;getLevelComponenet(Lio/redspace/ironsspellbooks/api/spells/SpellData;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/network/chat/MutableComponent;"))
    private MutableComponent redirectGetLevelComponenet(SpellData spellData, LivingEntity caster) {
        int stored = spellData.getLevel();
        int level = spellData.getSpell().getLevelFor(stored, caster);
        var ctx = SpellCastHooks.get();
        int diff = (ctx != null && ctx.data() != null && ctx.data().lvl() > 0) ? ctx.data().lvl() : 0;
        level += diff;
        int diffFromStored = level - stored;
        if (diffFromStored > 0) return net.minecraft.network.chat.Component.literal(level + " (+" + diffFromStored + ")");
        if (diffFromStored < 0) return net.minecraft.network.chat.Component.literal(level + " (" + diffFromStored + ")");
        return net.minecraft.network.chat.Component.literal(String.valueOf(level));
    }

    private static int getSelectedSpellIndex(InscriptionTableScreen screen) {
        try {
            Field field = screen.getClass().getDeclaredField("selectedSpellIndex");
            field.setAccessible(true);
            Object val = field.get(screen);
            if (val instanceof Integer) return (Integer) val;
        } catch (Exception ignored) {}
        return -1;
    }

    private static int getPhysicalIndex(InscriptionTableScreen screen, int selectedIndex) {
        try {
            SpellSlot spellSlot = getSpellSlot(screen, selectedIndex);
            if (spellSlot == null) return -1;
            return spellSlot.index();
        } catch (Exception e) {
            return -1;
        }
    }

    @SuppressWarnings("rawtypes")
    private static SpellSlot getSpellSlot(InscriptionTableScreen screen, int selectedIndex) {
        try {
            Field spellSlotsField = screen.getClass().getDeclaredField("spellSlots");
            spellSlotsField.setAccessible(true);
            List spellSlots = (List) spellSlotsField.get(screen);
            if (selectedIndex < 0 || selectedIndex >= spellSlots.size()) return null;

            Object spellSlotInfo = spellSlots.get(selectedIndex);
            Field spellSlotField = spellSlotInfo.getClass().getDeclaredField("spellSlot");
            spellSlotField.setAccessible(true);
            return (SpellSlot) spellSlotField.get(spellSlotInfo);
        } catch (Exception e) {
            return null;
        }
    }
}
