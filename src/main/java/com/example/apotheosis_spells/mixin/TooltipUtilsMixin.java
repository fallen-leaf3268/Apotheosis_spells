package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.handler.SpellCastHooks;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.item.Scroll;
import io.redspace.ironsspellbooks.item.SpellBook;
import io.redspace.ironsspellbooks.util.TooltipsUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = TooltipsUtils.class, remap = false)
public class TooltipUtilsMixin {

    @Inject(method = "formatScrollTooltip", at = @At("HEAD"))
    private static void onEnterScrollTooltip(ItemStack stack, Player player,
                                             CallbackInfoReturnable<List<net.minecraft.network.chat.Component>> cir) {
        SpellCastHooks.clear();
        if (stack == null || stack.isEmpty()) return;
        if (!(stack.getItem() instanceof Scroll)) return;
        if (!io.redspace.ironsspellbooks.api.spells.ISpellContainer.isSpellContainer(stack)) return;

        ReforgeCache.Data data = ReforgeCache.getFromScroll(stack);
        SpellData scrollSpellData = io.redspace.ironsspellbooks.api.spells.ISpellContainer.get(stack).getSpellAtIndex(0);
        if (scrollSpellData == null || scrollSpellData.getSpell() == null) return;
        SpellCastHooks.set(new SpellCastHooks.Context(stack, player, 0, scrollSpellData.getLevel(), data, scrollSpellData));
    }

    @Inject(method = "formatScrollTooltip", at = @At("RETURN"))
    private static void onExitScrollTooltip(ItemStack stack, Player player,
                                            CallbackInfoReturnable<List<net.minecraft.network.chat.Component>> cir) {
        SpellCastHooks.clear();
    }

    @Inject(method = "formatActiveSpellTooltip", at = @At("HEAD"))
    private static void onEnterActiveTooltip(ItemStack stack, SpellData spellData, CastSource castSource,
                                             LocalPlayer player,
                                             CallbackInfoReturnable<List<net.minecraft.network.chat.Component>> cir) {
        SpellCastHooks.clear();
        if (spellData == null || spellData == SpellData.EMPTY) return;

        ItemStack bookStack = stack;
        int slotIndex = -1;

        if (bookStack == null || bookStack.isEmpty()) {
            bookStack = io.redspace.ironsspellbooks.api.util.Utils.getPlayerSpellbookStack(player);
        }

        if (bookStack != null && !bookStack.isEmpty() && bookStack.getItem() instanceof SpellBook) {
            slotIndex = ReforgeCache.resolveSelectedSpellIndex(bookStack, player);
        }

        ReforgeCache.Data data = (bookStack != null && !bookStack.isEmpty())
                ? ReforgeCache.resolveDataFromStack(bookStack, player)
                : ReforgeCache.Data.DEF;
        SpellCastHooks.set(new SpellCastHooks.Context(bookStack, player, slotIndex, spellData.getLevel(), data, spellData));
    }

    @Inject(method = "formatActiveSpellTooltip", at = @At("RETURN"))
    private static void onExitActiveTooltip(ItemStack stack, SpellData spellData, CastSource castSource,
                                            LocalPlayer player,
                                            CallbackInfoReturnable<List<net.minecraft.network.chat.Component>> cir) {
        SpellCastHooks.clear();
    }

    @Redirect(method = "formatScrollTooltip", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getLevelFor(ILnet/minecraft/world/entity/LivingEntity;)I"))
    private static int redirectGetLevelFor(AbstractSpell spell, int level, LivingEntity caster) {
        int result = spell.getLevelFor(level, caster);
        var ctx = SpellCastHooks.get();
        return (ctx != null && ctx.data() != null && ctx.data().lvl() > 0) ? result + ctx.data().lvl() : result;
    }

    @Redirect(method = "formatScrollTooltip", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getSpellPower(ILnet/minecraft/world/entity/Entity;)F"))
    private static float redirectGetSpellPower(AbstractSpell spell, int spellLevel, net.minecraft.world.entity.Entity source) {
        // ×dmg 由 CastMixin.apoth_spellPower 全局施加，此处只取基础值
        return spell.getSpellPower(spellLevel, source);
    }

    @Redirect(method = "formatScrollTooltip", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getManaCost(I)I"))
    private static int redirectGetManaCost(AbstractSpell spell, int level) {
        int base = spell.getManaCost(level);
        var ctx = SpellCastHooks.get();
        if (ctx == null || ctx.data() == null || ctx.data().mana() == 1f) return base;
        return Math.max(0, Math.round(base * ctx.data().mana()));
    }

    @Redirect(method = "formatScrollTooltip", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getEffectiveCastTime(ILnet/minecraft/world/entity/LivingEntity;)I"))
    private static int redirectGetEffectiveCastTime(AbstractSpell spell, int spellLevel, LivingEntity entity) {
        int base = spell.getEffectiveCastTime(spellLevel, entity);
        var ctx = SpellCastHooks.get();
        if (ctx == null || ctx.data() == null || ctx.data().cast() == 1f) return base;
        return Math.max(0, Math.round(base * ctx.data().cast()));
    }

    @Redirect(method = {"formatScrollTooltip", "getTitleComponent"}, at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/util/TooltipsUtils;getLevelComponenet(Lio/redspace/ironsspellbooks/api/spells/SpellData;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/network/chat/MutableComponent;"))
    private static net.minecraft.network.chat.MutableComponent redirectGetLevelComponenet(SpellData spellData, LivingEntity caster) {
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

    @Redirect(method = "formatActiveSpellTooltip", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getLevelFor(ILnet/minecraft/world/entity/LivingEntity;)I"))
    private static int redirectActiveGetLevelFor(AbstractSpell spell, int level, LivingEntity caster) {
        int result = spell.getLevelFor(level, caster);
        var ctx = SpellCastHooks.get();
        return (ctx != null && ctx.data() != null && ctx.data().lvl() > 0) ? result + ctx.data().lvl() : result;
    }

    @Redirect(method = "formatActiveSpellTooltip", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getSpellPower(ILnet/minecraft/world/entity/Entity;)F"))
    private static float redirectActiveGetSpellPower(AbstractSpell spell, int spellLevel, net.minecraft.world.entity.Entity source) {
        return spell.getSpellPower(spellLevel, source);
    }

    @Redirect(method = "formatActiveSpellTooltip", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getManaCost(I)I"))
    private static int redirectActiveGetManaCost(AbstractSpell spell, int level) {
        int base = spell.getManaCost(level);
        var ctx = SpellCastHooks.get();
        if (ctx == null || ctx.data() == null || ctx.data().mana() == 1f) return base;
        return Math.max(0, Math.round(base * ctx.data().mana()));
    }

    @Redirect(method = "formatActiveSpellTooltip", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getEffectiveCastTime(ILnet/minecraft/world/entity/LivingEntity;)I"))
    private static int redirectActiveGetEffectiveCastTime(AbstractSpell spell, int spellLevel, LivingEntity entity) {
        int base = spell.getEffectiveCastTime(spellLevel, entity);
        var ctx = SpellCastHooks.get();
        if (ctx == null || ctx.data() == null || ctx.data().cast() == 1f) return base;
        return Math.max(0, Math.round(base * ctx.data().cast()));
    }
}
