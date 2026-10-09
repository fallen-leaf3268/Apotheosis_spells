package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.handler.SpellCastHooks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.api.spells.SpellSlot;
import io.redspace.ironsspellbooks.item.Scroll;
import io.redspace.ironsspellbooks.item.SpellBook;
import io.redspace.ironsspellbooks.player.ClientMagicData;
import io.redspace.ironsspellbooks.player.ClientPlayerEvents;
import io.redspace.ironsspellbooks.util.TooltipsUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Mixin(value = TooltipsUtils.class, remap = false)
public class TooltipUtilsMixin {

    @Mixin(value = SpellBook.class, remap = false)
    public static class BookPages {
        @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "appendHoverText", remap = true, at = @At(value = "INVOKE", remap = false,
                target = "Lio/redspace/ironsspellbooks/util/TooltipsUtils;getTitleComponent(Lio/redspace/ironsspellbooks/api/spells/SpellData;Lnet/minecraft/client/player/LocalPlayer;)Lnet/minecraft/network/chat/MutableComponent;"))
        private net.minecraft.network.chat.MutableComponent apoth_titleContext(
                SpellData spellData, LocalPlayer player,
                Operation<net.minecraft.network.chat.MutableComponent> original,
                @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) ItemStack stack,
                @com.llamalad7.mixinextras.sugar.Local(ordinal = 0) int activeIndex) {
            SpellSlot slot = ISpellContainer.get(stack).getActiveSpells().get(activeIndex);
            try (var scope = SpellCastHooks.enter(SpellCastHooks.buildContext(stack, player,
                    slot.index(), spellData.getLevel(), spellData))) {
                return original.call(spellData, player);
            }
        }

        @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "getPages", at = @At(value = "INVOKE",
                target = "Ljava/util/stream/Stream;map(Ljava/util/function/Function;)Ljava/util/stream/Stream;"))
        private java.util.stream.Stream<net.minecraft.network.chat.Component> apoth_pageContext(
                java.util.stream.Stream<SpellSlot> slots,
                java.util.function.Function<SpellSlot, net.minecraft.network.chat.Component> render,
                Operation<java.util.stream.Stream<net.minecraft.network.chat.Component>> original,
                @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) ItemStack stack) {
            java.util.function.Function<SpellSlot, net.minecraft.network.chat.Component> scoped = slot ->
                    SpellCastHooks.withPageContext(stack, net.minecraft.client.Minecraft.getInstance().player, slot, render);
            return original.call(slots, scoped);
        }
    }

    @Mixin(value = ClientPlayerEvents.class, remap = false)
    public static class CastingImplementTooltips {
        @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "handleCastingImplementTooltip", at = @At(value = "INVOKE",
                target = "Lio/redspace/ironsspellbooks/util/TooltipsUtils;formatActiveSpellTooltip(Lnet/minecraft/world/item/ItemStack;Lio/redspace/ironsspellbooks/api/spells/SpellData;Lio/redspace/ironsspellbooks/api/spells/CastSource;Lnet/minecraft/client/player/LocalPlayer;)Ljava/util/List;"))
        private static List<net.minecraft.network.chat.MutableComponent> apoth_selectedContext(
                ItemStack stack, SpellData spellData, CastSource castSource, LocalPlayer player,
                Operation<List<net.minecraft.network.chat.MutableComponent>> original) {
            var selection = ClientMagicData.getSpellSelectionManager().getSelection();
            try (var scope = SpellCastHooks.enter(SpellCastHooks.resolveSelection(player, selection))) {
                return original.call(null, spellData, castSource, player);
            }
        }
    }

    @WrapMethod(method = "formatScrollTooltip")
    private static List<net.minecraft.network.chat.Component> apoth_scrollTooltip(
            ItemStack stack, Player player, Operation<List<net.minecraft.network.chat.Component>> original) {
        SpellCastHooks.Context context = apoth_context(stack, null, player);
        if (context == null) return original.call(stack, player);
        try (var scope = SpellCastHooks.enter(context)) {
            return original.call(stack, player);
        }
    }

    @WrapMethod(method = "formatActiveSpellTooltip")
    private static List<net.minecraft.network.chat.MutableComponent> apoth_activeTooltip(
            ItemStack stack, SpellData spellData, CastSource castSource, LocalPlayer player,
            Operation<List<net.minecraft.network.chat.MutableComponent>> original) {
        SpellCastHooks.Context context = apoth_context(stack, spellData, player);
        if (context == null) return original.call(stack, spellData, castSource, player);
        try (var scope = SpellCastHooks.enter(context)) {
            return original.call(stack, spellData, castSource, player);
        }
    }

    @Redirect(method = {"formatScrollTooltip", "formatActiveSpellTooltip"}, at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getLevelFor(ILnet/minecraft/world/entity/LivingEntity;)I"))
    private static int apoth_level(AbstractSpell spell, int level, LivingEntity caster) {
        int result = spell.getLevelFor(level, caster);
        var ctx = SpellCastHooks.get();
        return (ctx != null && ctx.data() != null && ctx.data().lvl() > 0) ? result + ctx.data().lvl() : result;
    }

    @Redirect(method = {"formatScrollTooltip", "formatActiveSpellTooltip"}, at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getManaCost(I)I"))
    private static int apoth_manaCost(AbstractSpell spell, int level) {
        int base = spell.getManaCost(level);
        var ctx = SpellCastHooks.get();
        float multiplier = ctx == null || ctx.data() == null ? 1 : ctx.data().mana();
        int cost = Math.max(0, Math.round(base * multiplier));
        boolean continuous = spell.getCastType() == io.redspace.ironsspellbooks.api.spells.CastType.CONTINUOUS;
        com.example.apotheosis_spells.ApotheosisSpells.Diagnostics.calculation("MANA_TOOLTIP", spell.getSpellId(),
                "level=" + level + " resolved=" + (ctx != null) + " physicalSlot=" + (ctx == null ? -1 : ctx.spellSlotIndex())
                        + " basePerCast=" + base + " multiplier=" + multiplier + " costPerCast=" + cost
                        + " displayed=" + (continuous ? cost * (20 / io.redspace.ironsspellbooks.capabilities.magic.MagicManager.CONTINUOUS_CAST_TICK_INTERVAL) : cost)
                        + " unit=" + (continuous ? "per_second" : "per_cast"));
        return cost;
    }

    @Redirect(method = {"formatScrollTooltip", "formatActiveSpellTooltip"}, at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getEffectiveCastTime(ILnet/minecraft/world/entity/LivingEntity;)I"))
    private static int apoth_castTime(AbstractSpell spell, int spellLevel, LivingEntity entity) {
        int base = spell.getEffectiveCastTime(spellLevel, entity);
        var ctx = SpellCastHooks.get();
        if (ctx == null || ctx.data() == null || ctx.data().cast() == 1f) return base;
        return Math.max(0, Math.round(base * ctx.data().cast()));
    }

    @Redirect(method = {"formatScrollTooltip", "getTitleComponent"}, at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/util/TooltipsUtils;getLevelComponenet(Lio/redspace/ironsspellbooks/api/spells/SpellData;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/network/chat/MutableComponent;"))
    private static net.minecraft.network.chat.MutableComponent apoth_levelComponent(SpellData spellData, LivingEntity caster) {
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

    @Unique
    private static SpellCastHooks.Context apoth_context(ItemStack stack, SpellData spellData, Player player) {
        ItemStack source = stack;
        if (source == null || source.isEmpty() || !ISpellContainer.isSpellContainer(source)) return null;
        if (source.getItem() instanceof Scroll) {
            SpellData actual = ISpellContainer.get(source).getSpellAtIndex(0);
            if (actual == null || actual == SpellData.EMPTY || actual.getSpell() == null) return null;
            return SpellCastHooks.buildContext(source, player, 0, actual.getLevel(), actual);
        }
        if (!(source.getItem() instanceof SpellBook) || spellData == null || spellData == SpellData.EMPTY) return null;
        SpellSlot match = null;
        for (SpellSlot slot : ISpellContainer.get(source).getActiveSpells()) {
            SpellData actual = slot.spellData();
            if (actual == spellData) {
                return SpellCastHooks.buildContext(source, player, slot.index(), spellData.getLevel(), actual);
            }
            if (actual.equals(spellData)) {
                if (match != null) return null;
                match = slot;
            }
        }
        return match == null ? null
                : SpellCastHooks.buildContext(source, player, match.index(), spellData.getLevel(), match.spellData());
    }
}
