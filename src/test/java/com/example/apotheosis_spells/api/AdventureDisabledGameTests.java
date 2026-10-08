package com.example.apotheosis_spells.api;

import dev.shadowsoffire.apotheosis.Apotheosis;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper;
import dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.registries.ItemRegistry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

@GameTestHolder("apotheosis_spells")
@PrefixGameTestTemplate(false)
public final class AdventureDisabledGameTests {

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void disabledAdventurePreservesScrollAndBookAffixes(GameTestHelper helper) {
        CompoundTag data = affixData("apotheosis:ancient");
        ItemStack scroll = ReforgeCache.createAffixedScroll(SpellRegistry.FIREBALL_SPELL.get(), 1, data);
        ItemStack book = book(data);
        boolean previous = Apotheosis.enableAdventure;
        try {
            Apotheosis.enableAdventure = true;
            helper.assertTrue(!ReforgeCache.getFromScroll(scroll).isDefault()
                    && !ReforgeCache.getEffectsFromSpellBook(book, 2).isEmpty(), "Fixture must have active modifiers and effects");
            CompoundTag originalScroll = scroll.getTag().copy();
            CompoundTag originalBook = book.getTag().copy();
            Apotheosis.enableAdventure = false;
            assertNeutral(helper, data);
            helper.assertTrue(ReforgeCache.getFromScroll(scroll).equals(ReforgeCache.Data.DEF)
                    && ReforgeCache.getEffectsFromScroll(scroll).equals(SpellEffects.NONE), "Disabled scroll must have neutral bonuses");
            helper.assertTrue(ReforgeCache.getFromSpellBook(book, 2).equals(ReforgeCache.Data.DEF)
                    && ReforgeCache.getEffectsFromSpellBook(book, 2).equals(SpellEffects.NONE), "Disabled book slot must have neutral bonuses");
            helper.assertTrue(originalScroll.equals(scroll.getTag()) && originalBook.equals(book.getTag()),
                    "Disabled adventure must preserve stored affixes, gems, sockets and third-party NBT");
        } finally {
            Apotheosis.enableAdventure = previous;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void unavailableRarityRegistryPreservesStoredBonuses(GameTestHelper helper) throws ReflectiveOperationException {
        var ordered = RarityRegistry.class.getDeclaredField("ordered");
        ordered.setAccessible(true);
        Object previousRarities = ordered.get(RarityRegistry.INSTANCE);
        boolean previousAdventure = Apotheosis.enableAdventure;
        try {
            Apotheosis.enableAdventure = true;
            ordered.set(RarityRegistry.INSTANCE, List.of());
            assertNeutral(helper, affixData("apotheosis:not_registered"));
            CompoundTag missingRarity = affixData("apotheosis:common");
            missingRarity.remove(AffixHelper.RARITY);
            assertNeutral(helper, missingRarity);
            assertNeutral(helper, affixData("apotheosis:ancient"));
        } finally {
            ordered.set(RarityRegistry.INSTANCE, previousRarities);
            Apotheosis.enableAdventure = previousAdventure;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void missingAndInvalidRaritiesKeepTheMinimumFallback(GameTestHelper helper) {
        boolean previous = Apotheosis.enableAdventure;
        try {
            Apotheosis.enableAdventure = true;
            var minimum = RarityRegistry.getMinRarity().get();
            CompoundTag expected = affixData(RarityRegistry.INSTANCE.getKey(minimum).toString());
            ReforgeCache.Data expectedData = ReforgeCache.computeData(expected);
            SpellEffects expectedEffects = ReforgeCache.computeEffects(expected);
            helper.assertTrue(!expectedData.isDefault() && !expectedEffects.isEmpty(), "Minimum rarity must retain ordinary affix bonuses");
            for (String rarity : List.of("", "apotheosis:not_registered", "invalid namespace:rarity")) {
                CompoundTag data = affixData(rarity);
                if (rarity.isEmpty()) data.remove(AffixHelper.RARITY);
                CompoundTag original = data.copy();
                helper.assertTrue(ReforgeCache.resolveRarity(data) == minimum, "Missing or invalid rarity must use the loaded minimum");
                helper.assertTrue(ReforgeCache.computeData(data).equals(expectedData)
                        && ReforgeCache.computeEffects(data).equals(expectedEffects), "Fallback changed normal affix calculation");
                helper.assertTrue(original.equals(data), "Rarity resolution must preserve the original NBT");
            }
            CompoundTag legacy = affixData("mythic");
            CompoundTag modern = affixData("apotheosis:mythic");
            helper.assertTrue(ReforgeCache.resolveRarity(legacy) == ReforgeCache.resolveRarity(modern)
                    && ReforgeCache.computeData(legacy).equals(ReforgeCache.computeData(modern))
                    && ReforgeCache.computeEffects(legacy).equals(ReforgeCache.computeEffects(modern)),
                    "Legacy unqualified rarity must retain the same bonuses");
        } finally {
            Apotheosis.enableAdventure = previous;
        }
        helper.succeed();
    }

    private static void assertNeutral(GameTestHelper helper, CompoundTag data) {
        CompoundTag original = data.copy();
        helper.assertTrue(ReforgeCache.computeData(data).equals(ReforgeCache.Data.DEF), "Unavailable adventure must return neutral modifiers");
        helper.assertTrue(ReforgeCache.computeEffects(data).equals(SpellEffects.NONE), "Unavailable adventure must return no effects");
        helper.assertTrue(original.equals(data), "Unavailable adventure must preserve the input affix data");
    }

    private static ItemStack book(CompoundTag data) {
        ItemStack book = new ItemStack(ItemRegistry.LEGENDARY_SPELL_BOOK.get());
        var spells = ISpellContainer.create(4, true, true).mutableCopy();
        spells.addSpellAtIndex(SpellRegistry.FIREBALL_SPELL.get(), 1, 2, false);
        ISpellContainer.set(book, spells.toImmutable());
        ReforgeCache.setBookAffix(book, 2, data);
        return book;
    }

    private static CompoundTag affixData(String rarity) {
        CompoundTag data = new CompoundTag();
        data.putString(AffixHelper.RARITY, rarity);
        CompoundTag affixes = new CompoundTag();
        affixes.putFloat("apotheosis_spells:scroll/spell_modifier/spell_power", 0);
        affixes.putFloat("apotheosis_spells:scroll/spell_modifier/mana_steal", 0);
        data.put(AffixHelper.AFFIXES, affixes);
        data.putInt("sockets", 3);
        data.putIntArray("tiered_socket_tiers", new int[]{0, 1, 2});
        CompoundTag gem = new CompoundTag();
        gem.putString("id", "apotheosis:gem");
        gem.putByte("Count", (byte) 1);
        CompoundTag gemTag = new CompoundTag();
        gemTag.putString("gem", "apotheosis_spells:arcane");
        gem.put("tag", gemTag);
        ListTag gems = new ListTag();
        gems.add(gem);
        data.put("gems", gems);
        CompoundTag custom = new CompoundTag();
        custom.putInt("kept", 7);
        data.put("third_party", custom);
        return data;
    }
}
