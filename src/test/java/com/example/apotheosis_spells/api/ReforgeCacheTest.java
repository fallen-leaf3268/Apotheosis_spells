package com.example.apotheosis_spells.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReforgeCacheTest {

    @Test
    void topLevelScrollAffixesOverrideLegacySlotData() {
        CompoundTag top = affixData("new");
        CompoundTag slot = new CompoundTag();
        slot.put(ReforgeCache.SLOT_AFFIX_DATA, affixData("old"));

        assertEquals(top, ReforgeCache.preferredScrollAffixData(top, slot));
        assertEquals(slot.getCompound(ReforgeCache.SLOT_AFFIX_DATA),
                ReforgeCache.preferredScrollAffixData(null, slot));
    }

    @Test
    void presentEmptyTopLevelStillSuppressesStaleLegacySlotData() {
        CompoundTag slot = new CompoundTag();
        slot.put(ReforgeCache.SLOT_AFFIX_DATA, affixData("old"));

        assertEquals(new CompoundTag(), ReforgeCache.preferredScrollAffixData(new CompoundTag(), slot));
    }

    @Test
    void bookEntryRequiresMatchingSpellIdAndPreservesCompleteAffixData() {
        CompoundTag affixData = affixData("kept");
        CompoundTag gem = new CompoundTag();
        gem.putString("id", "apotheosis_spells:test_gem");
        ListTag gems = new ListTag();
        gems.add(gem);
        affixData.put("gems", gems);
        CompoundTag entry = ReforgeCache.createBookEntry("irons_spellbooks:firebolt", affixData);

        assertEquals(affixData, ReforgeCache.readBookEntry(entry, "irons_spellbooks:firebolt"));
        assertNull(ReforgeCache.readBookEntry(entry, "irons_spellbooks:ice_spikes"));
        assertEquals(affixData, ReforgeCache.readBookEntry(affixData, "irons_spellbooks:firebolt"));

        CompoundTag incompleteBoundEntry = new CompoundTag();
        incompleteBoundEntry.putString("spell_id", "irons_spellbooks:firebolt");
        assertNull(ReforgeCache.readBookEntry(incompleteBoundEntry, "irons_spellbooks:firebolt"));
    }

    @Test
    void authoritativeBookEntryDoesNotReviveLegacyAffixesAfterSlotReplacement() {
        CompoundTag slot = new CompoundTag();
        slot.put(ReforgeCache.SLOT_AFFIX_DATA, affixData("stale_soulbound"));
        CompoundTag bound = ReforgeCache.createBookEntry("irons_spellbooks:firebolt", affixData("bound"));

        assertNull(ReforgeCache.preferredBookAffixData(bound, slot, "irons_spellbooks:ice_spikes"));
        assertNull(ReforgeCache.preferredBookAffixData(new CompoundTag(), slot, "irons_spellbooks:firebolt"));
        assertEquals(affixData("stale_soulbound"), ReforgeCache.preferredBookAffixData(null, slot, "irons_spellbooks:firebolt"));
    }

    @Test
    void bookRoundTripPreservesSocketTiersAndDoesNotShareMutableTags() {
        CompoundTag original = affixData("source");
        original.putInt("sockets", 3);
        original.putIntArray("tiered_socket_tiers", new int[]{0, 1, 2});
        CompoundTag extra = new CompoundTag();
        extra.putInt("custom", 7);
        original.put("third_party", extra);
        CompoundTag entry = ReforgeCache.createBookEntry("irons_spellbooks:firebolt", original);
        original.getCompound("third_party").putInt("custom", 99);

        CompoundTag extracted = ReforgeCache.readBookEntry(entry, "irons_spellbooks:firebolt");
        assertEquals(7, extracted.getCompound("third_party").getInt("custom"));
        assertEquals(3, extracted.getInt("sockets"));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{0, 1, 2}, extracted.getIntArray("tiered_socket_tiers"));
        extracted.remove("sockets");
        assertEquals(3, ReforgeCache.readBookEntry(entry, "irons_spellbooks:firebolt").getInt("sockets"));
    }

    @Test
    void legacyBookAffixesSurviveTheFirstContainerReadAndBindToPhysicalSlots() {
        CompoundTag root = legacyRoot();
        ReforgeCache.preserveLegacyAffixes(root, true);
        root.remove(ISpellContainer.LEGACY_NBT);
        assertEquals(affixData("legacy"), ReforgeCache.readBookEntry(
                root.getCompound(ReforgeCache.BOOK_AFFIXES).getCompound("3"), "irons_spellbooks:firebolt"));
        assertNull(ReforgeCache.readBookEntry(
                root.getCompound(ReforgeCache.BOOK_AFFIXES).getCompound("0"), "irons_spellbooks:firebolt"));
    }

    @Test
    void modernContainerSuppressesLegacyMigrationEvenWhenMalformed() {
        CompoundTag root = legacyRoot();
        root.putString(ISpellContainer.NBT, "malformed");
        CompoundTag original = root.copy();
        ReforgeCache.preserveLegacyAffixes(root, true);
        assertEquals(original, root);
    }

    @Test
    void legacyMigrationCannotOverwriteABoundEntryOrRetainAnInvalidSlot() {
        CompoundTag root = legacyRoot();
        CompoundTag map = new CompoundTag();
        map.put("3", ReforgeCache.createBookEntry("irons_spellbooks:ice_spikes", affixData("current")));
        root.put(ReforgeCache.BOOK_AFFIXES, map.copy());
        ReforgeCache.preserveLegacyAffixes(root, true);
        assertEquals(map, root.getCompound(ReforgeCache.BOOK_AFFIXES));

        CompoundTag invalid = legacyRoot();
        invalid.getCompound(ISpellContainer.LEGACY_NBT).getList("data", 10).getCompound(0).putInt("index", -1);
        ReforgeCache.preserveLegacyAffixes(invalid, true);
        assertEquals(new CompoundTag(), invalid.getCompound(ReforgeCache.BOOK_AFFIXES));
    }

    @Test
    void legacyScrollMigrationKeepsExplicitEmptyTopLevelAuthoritative() {
        CompoundTag root = legacyRoot();
        root.getCompound(ISpellContainer.LEGACY_NBT).getList("data", 10).getCompound(0).putInt("index", 0);
        ReforgeCache.preserveLegacyAffixes(root, false);
        assertEquals(affixData("legacy"), root.getCompound("affix_data"));
        root.put("affix_data", new CompoundTag());
        ReforgeCache.preserveLegacyAffixes(root, false);
        assertEquals(new CompoundTag(), root.getCompound("affix_data"));
    }

    private static CompoundTag legacyRoot() {
        CompoundTag slot = new CompoundTag();
        slot.putInt("index", 3);
        slot.putInt("level", 1);
        slot.putString("id", "irons_spellbooks:firebolt");
        slot.put(ReforgeCache.SLOT_AFFIX_DATA, affixData("legacy"));
        ListTag slots = new ListTag();
        slots.add(slot);
        CompoundTag container = new CompoundTag();
        container.putInt("maxSpells", 5);
        container.put("data", slots);
        CompoundTag root = new CompoundTag();
        root.put(ISpellContainer.LEGACY_NBT, container);
        return root;
    }

    @Test
    void snapshotDataDefaultsMissingAndInvalidMultipliers() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("d", Float.NaN);
        tag.putFloat("m", -1);
        tag.putFloat("c", Float.POSITIVE_INFINITY);
        tag.putFloat("t", 0.5f);
        tag.putInt("l", -2);

        assertEquals(new ReforgeCache.Data(1, 1, 1, 0.5f, 0, 1, 1), ReforgeCache.Data.read(tag));
        assertEquals(ReforgeCache.Data.DEF, ReforgeCache.Data.read(new CompoundTag()));
    }

    private static CompoundTag affixData(String marker) {
        CompoundTag tag = new CompoundTag();
        tag.putString("marker", marker);
        return tag;
    }
}
