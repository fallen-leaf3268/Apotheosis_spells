package com.example.apotheosis_spells.handler;

import com.example.apotheosis_spells.api.ReforgeCache;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.gui.overlays.SpellSelection;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.UUID;

@GameTestHolder("apotheosis_spells")
@PrefixGameTestTemplate(false)
public class PreviewAttributeGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void selectedGemAttributesDeactivateAndRecoverWithoutChangingBook(GameTestHelper helper) throws Exception {
        var player = player(helper, "[SelectedGemRecovery]");
        var book = book();
        Utils.setPlayerSpellbookStack(player, book);
        MagicData.getPlayerMagicData(player).getSyncedData().setSpellSelection(new SpellSelection("spellbook", 0));
        var power = player.getAttributes().getInstance(AttributeRegistry.SPELL_POWER.get());
        var mana = player.getAttributes().getInstance(AttributeRegistry.MAX_MANA.get());
        var otherPower = new AttributeModifier(UUID.randomUUID(), "other power", 0.1, AttributeModifier.Operation.ADDITION);
        var otherMana = new AttributeModifier(UUID.randomUUID(), "other mana", 20, AttributeModifier.Operation.ADDITION);
        power.addTransientModifier(otherPower);
        mana.addTransientModifier(otherMana);
        double neutralPower = power.getValue();
        double neutralMana = mana.getValue();
        var original = book.getTag().copy();
        var ordered = dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry.class.getDeclaredField("ordered");
        ordered.setAccessible(true);
        var registry = dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry.INSTANCE;
        Object previousRarities = ordered.get(registry);
        boolean previousAdventure = dev.shadowsoffire.apotheosis.Apotheosis.enableAdventure;
        try {
            dev.shadowsoffire.apotheosis.Apotheosis.enableAdventure = true;
            BookAttributeHandler.refresh(player);
            helper.assertTrue(near(power.getValue(), neutralPower + 0.05) && near(mana.getValue(), neutralMana + 120),
                    "Selected gem fixture did not apply real attributes");
            dev.shadowsoffire.apotheosis.Apotheosis.enableAdventure = false;
            BookAttributeHandler.refresh(player);
            helper.assertTrue(near(power.getValue(), neutralPower) && near(mana.getValue(), neutralMana)
                            && power.getModifier(otherPower.getId()) != null && mana.getModifier(otherMana.getId()) != null,
                    "Disabling adventure retained book bonuses or removed another source's modifiers");
            helper.assertTrue(original.equals(book.getTag()), "Disabling adventure changed the stored gem data");
            dev.shadowsoffire.apotheosis.Apotheosis.enableAdventure = true;
            BookAttributeHandler.refresh(player);
            helper.assertTrue(near(power.getValue(), neutralPower + 0.05) && near(mana.getValue(), neutralMana + 120),
                    "Re-enabling adventure did not restore selected gem attributes");
            ordered.set(registry, java.util.List.of());
            BookAttributeHandler.refresh(player);
            helper.assertTrue(near(power.getValue(), neutralPower) && near(mana.getValue(), neutralMana),
                    "Unavailable rarities left the cached selected gem attributes active");
            helper.assertTrue(original.equals(book.getTag()), "Unavailable rarities changed the stored gem data");
            ordered.set(registry, previousRarities);
            BookAttributeHandler.refresh(player);
            helper.assertTrue(near(power.getValue(), neutralPower + 0.05) && near(mana.getValue(), neutralMana + 120)
                            && original.equals(book.getTag()),
                    "Restoring rarities did not recover gem attributes from the unchanged book");
        } finally {
            ordered.set(registry, previousRarities);
            dev.shadowsoffire.apotheosis.Apotheosis.enableAdventure = previousAdventure;
            BookAttributeHandler.onLogout(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void previewUsesTargetSlotWithoutChangingPlayer(GameTestHelper helper) throws Exception {
        var player = player(helper, "[PreviewAttributes]");
        var book = book();
        Utils.setPlayerSpellbookStack(player, book);
        MagicData magic = MagicData.getPlayerMagicData(player);
        magic.getSyncedData().setSpellSelection(new SpellSelection("spellbook", 0));
        var power = AttributeRegistry.SPELL_POWER.get();
        AttributeInstance raw = player.getAttributes().getInstance(power);
        addOtherModifiers(raw);
        double base = raw.getBaseValue();
        BookAttributeHandler.refresh(player);
        double selectedValue = raw.getValue();
        helper.assertTrue(near(selectedValue, (base + 0.15) * 1.25 * 1.5), "Selected gem fixture inactive");
        var manaAttribute = AttributeRegistry.MAX_MANA.get();
        AttributeInstance rawMana = player.getAttributes().getInstance(manaAttribute);
        double selectedMaxMana = rawMana.getValue();
        var modifiers = java.util.Set.copyOf(raw.getModifiers());
        var manaModifiers = java.util.Set.copyOf(rawMana.getModifiers());
        var dirty = java.util.Set.copyOf(player.getAttributes().getDirtyAttributes());
        var castTime = AttributeRegistry.CAST_TIME_REDUCTION.get();
        double baseCastTime = player.getAttributes().getValue(castTime);
        magic.setMana(100);
        float health = player.getHealth();
        var slots = ISpellContainer.get(book).getAllSpells();
        try {
            SpellCastHooks.withPageContext(book, player, slots[1], slot -> {
                helper.assertTrue(near(player.getAttributeValue(power), (base + 0.5) * 1.25 * 1.5),
                        "Preview borrowed the selected slot instead of the target gem");
                helper.assertTrue(player.getAttribute(power) != raw
                                && near(player.getAttribute(power).getValue(), player.getAttributeValue(power)),
                        "Direct attribute access did not use a detached preview");
                helper.assertTrue(near(player.getAttributeValue(manaAttribute), rawMana.getBaseValue()),
                        "Preview retained selected-slot maximum mana");
                helper.assertTrue(near(player.getAttributeValue(castTime), baseCastTime + 0.3),
                        "Preview did not include the target casting-speed gem");
                var target = SpellCastHooks.get();
                try {
                    SpellCastHooks.withPageContext(book, player, slots[2], nested -> {
                        helper.assertTrue(near(player.getAttributeValue(power), (base + 0.1) * 1.25 * 1.5),
                                "Nested plain slot retained parent or selected bonuses");
                        throw new IllegalStateException("preview restoration");
                    });
                } catch (IllegalStateException expected) {
                    helper.assertTrue(SpellCastHooks.get() == target
                                    && near(player.getAttributeValue(power), (base + 0.5) * 1.25 * 1.5),
                            "Nested failure did not restore the outer preview");
                }
                helper.assertTrue(near(raw.getValue(), selectedValue) && near(rawMana.getValue(), selectedMaxMana)
                                && raw.getModifiers().equals(modifiers) && rawMana.getModifiers().equals(manaModifiers),
                        "Preview changed the real attribute map");
                helper.assertTrue(player.getHealth() == health && magic.getMana() == 100
                                && player.getAttributes().getDirtyAttributes().equals(dirty),
                        "Preview changed gameplay state or scheduled attribute synchronization");
                return null;
            });
            helper.assertTrue(SpellCastHooks.get() == null && player.getAttribute(power) == raw
                            && near(player.getAttributeValue(power), selectedValue),
                    "Preview leaked after scope close");
            helper.succeed();
        } finally {
            BookAttributeHandler.onLogout(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void previewAndCastingRestoreNestedAttributeScopes(GameTestHelper helper) throws Exception {
        var player = player(helper, "[NestedPreviewCast]");
        var book = book();
        Utils.setPlayerSpellbookStack(player, book);
        MagicData.getPlayerMagicData(player).getSyncedData().setSpellSelection(new SpellSelection("spellbook", 0));
        var power = AttributeRegistry.SPELL_POWER.get();
        AttributeInstance raw = player.getAttributes().getInstance(power);
        double base = raw.getBaseValue();
        BookAttributeHandler.refresh(player);
        var slots = ISpellContainer.get(book).getAllSpells();
        try {
            SpellCastHooks.withPageContext(book, player, slots[1], slot -> {
                var previewContext = SpellCastHooks.get();
                helper.assertTrue(near(player.getAttributeValue(power), base + 0.4), "Outer preview missing target bonus");
                var snapshot = SpellCastHooks.capture(player, slot.getSpell(), slot.getLevel(), previewContext);
                try (var cast = SpellCastHooks.enter(snapshot, player)) {
                    helper.assertTrue(player.getAttribute(power) == raw && near(raw.getValue(), base + 0.4),
                            "Gameplay scope operated on a detached preview instance");
                    try {
                        SpellCastHooks.withPageContext(book, player, slots[2], plain -> {
                            helper.assertTrue(near(player.getAttributeValue(power), base) && near(raw.getValue(), base + 0.4),
                                    "Nested preview changed the real casting attributes");
                            throw new IllegalStateException("nested preview");
                        });
                    } catch (IllegalStateException expected) {
                        helper.assertTrue(SpellCastHooks.currentSnapshot() == snapshot
                                        && player.getAttribute(power) == raw && near(raw.getValue(), base + 0.4),
                                "Failed preview did not restore gameplay scope");
                    }
                }
                helper.assertTrue(SpellCastHooks.get() == previewContext
                                && near(player.getAttributeValue(power), base + 0.4) && near(raw.getValue(), base + 0.05),
                        "Gameplay close did not restore preview and selected attributes");
                return null;
            });
            helper.assertTrue(near(player.getAttributeValue(power), base + 0.05), "Nested scopes leaked after rendering");
            helper.succeed();
        } finally {
            BookAttributeHandler.onLogout(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void previewSupportsNonServerPlayersAndUnnamedSyncedModifiers(GameTestHelper helper) throws Exception {
        var serverPlayer = player(helper, "[PreviewSyncSource]");
        var book = book();
        Utils.setPlayerSpellbookStack(serverPlayer, book);
        MagicData.getPlayerMagicData(serverPlayer).getSyncedData().setSpellSelection(new SpellSelection("spellbook", 0));
        var power = AttributeRegistry.SPELL_POWER.get();
        AttributeInstance source = serverPlayer.getAttributes().getInstance(power);
        addOtherModifiers(source);
        BookAttributeHandler.refresh(serverPlayer);
        var player = helper.makeMockPlayer();
        AttributeInstance raw = player.getAttributes().getInstance(power);
        raw.setBaseValue(source.getBaseValue());
        for (var modifier : source.getModifiers()) {
            raw.addTransientModifier(new AttributeModifier(modifier.getId(), "Unknown synced attribute modifier",
                    modifier.getAmount(), modifier.getOperation()));
        }
        double before = raw.getValue();
        var modifiers = java.util.Set.copyOf(raw.getModifiers());
        var slot = ISpellContainer.get(book).getAllSpells()[1];
        try {
            SpellCastHooks.withPageContext(book, player, slot, current -> {
                helper.assertTrue(near(player.getAttributeValue(power), (raw.getBaseValue() + 0.5) * 1.25 * 1.5),
                        "Non-server preview could not replace unnamed synchronized book bonuses");
                helper.assertTrue(near(source.getValue(), before) && near(raw.getValue(), before)
                                && raw.getModifiers().equals(modifiers),
                        "Preview modified either player");
                helper.assertTrue(near(serverPlayer.getAttributeValue(power), before), "Preview affected another caster");
                return null;
            });
            helper.assertTrue(near(player.getAttributeValue(power), before), "Non-server preview leaked");
            helper.succeed();
        } finally {
            BookAttributeHandler.onLogout(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(serverPlayer));
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void inscribedScrollPreviewMatchesBookWithoutChangingDirectCasting(GameTestHelper helper) throws Exception {
        var player = player(helper, "[InscribedScrollPreview]");
        var spell = io.redspace.ironsspellbooks.api.registry.SpellRegistry.getSpell("irons_spellbooks:magic_missile");
        var affixes = TagParser.parseTag("""
                {rarity:"apotheosis:ancient",sockets:1,
                affixes:{"apotheosis_spells:scroll/spell_modifier/spell_level":1.0f,
                "apotheosis_spells:scroll/spell_modifier/spell_power":1.0f,
                "apotheosis_spells:scroll/attribute/ender_spell_power":1.0f},
                gems:[{id:"apotheosis:gem",Count:1b,tag:{gem:"apotheosis_spells:arcane",
                affix_data:{rarity:"apotheosis:ancient"}}}]}
                """);
        var scroll = ReforgeCache.createAffixedScroll(spell, 10, affixes);
        var book = book();
        var targetTag = book.getOrCreateTag().getCompound("irons_spellbooks:spell_container")
                .getList("data", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(1);
        targetTag.putString("id", spell.getSpellId());
        targetTag.putInt("level", 10);
        ReforgeCache.setBookAffix(book, 1, affixes);
        Utils.setPlayerSpellbookStack(player, book);
        var magic = MagicData.getPlayerMagicData(player);
        magic.getSyncedData().setSpellSelection(new SpellSelection("spellbook", 0));
        var power = AttributeRegistry.SPELL_POWER.get();
        var ender = ForgeRegistries.ATTRIBUTES.getValue(ResourceLocation.parse("irons_spellbooks:ender_spell_power"));
        var rawPower = player.getAttributes().getInstance(power);
        var rawEnder = player.getAttributes().getInstance(ender);
        var rawMana = player.getAttributes().getInstance(AttributeRegistry.MAX_MANA.get());
        addOtherModifiers(rawPower);
        rawEnder.addTransientModifier(new AttributeModifier(UUID.randomUUID(), "other school", 0.2,
                AttributeModifier.Operation.MULTIPLY_TOTAL));
        var hand = net.minecraft.world.entity.EquipmentSlot.MAINHAND;
        var handModifiers = scroll.getAttributeModifiers(hand);
        player.setItemSlot(hand, scroll);
        player.getAttributes().addTransientAttributeModifiers(handModifiers);
        double directPower = (rawPower.getBaseValue() + 0.1 + 0.4) * 1.25 * 1.5;
        double directEnder = (rawEnder.getBaseValue() + 0.28) * 1.2;
        helper.assertTrue(near(rawPower.getValue(), directPower) && near(rawEnder.getValue(), directEnder),
                "The real held scroll did not supply its native affix and gem attributes");
        BookAttributeHandler.refresh(player);
        double selectedPower = rawPower.getValue();
        double selectedMana = rawMana.getValue();
        helper.assertTrue(near(selectedPower, (rawPower.getBaseValue() + 0.1 + 0.4 + 0.05) * 1.25 * 1.5)
                        && near(selectedMana, rawMana.getBaseValue() + 120),
                "The selected book fixture did not retain its independent gem bonuses");
        var powerModifiers = java.util.Set.copyOf(rawPower.getModifiers());
        var enderModifiers = java.util.Set.copyOf(rawEnder.getModifiers());
        var manaModifiers = java.util.Set.copyOf(rawMana.getModifiers());
        var dirty = java.util.Set.copyOf(player.getAttributes().getDirtyAttributes());
        var scrollTag = scroll.getTag().copy();
        var bookTag = book.getTag().copy();
        magic.setMana(100);
        float health = player.getHealth();
        var scrollData = ISpellContainer.get(scroll).getSpellAtIndex(0);
        var context = SpellCastHooks.buildContext(scroll, player, 0, scrollData.getLevel(), scrollData);
        var target = ISpellContainer.get(book).getAllSpells()[1];
        var bookContext = SpellCastHooks.buildContext(book, player, target.index(), target.getLevel(), target.spellData());
        try {
            helper.assertTrue(context.data().lvl() == 5 && context.data().dmg() == 1.5f,
                    "The real scroll lost its level or spell-power reforge affixes");
            try (var direct = SpellCastHooks.enter(context)) {
                int directLevel = spell.getLevelFor(scrollData.getLevel(), player) + context.data().lvl();
                var directInfo = spell.getUniqueInfo(directLevel, player).get(0).getString();
                helper.assertTrue(directLevel == 15 && near(player.getAttributeValue(power), directPower)
                                && near(player.getAttributeValue(ender), directEnder),
                        "Direct scroll preview reapplied its held attributes or retained selected-book bonuses");
                helper.assertTrue(BookAttributeHandler.capture(context).isEmpty()
                                && SpellCastHooks.capture(player, spell, directLevel, context).attributes().isEmpty(),
                        "Direct scroll casting captured attributes that native equipment already supplies");
                try (var inscribed = SpellCastHooks.enterInscribedScroll(context)) {
                    double inscribedPower = (rawPower.getBaseValue() + 0.1 + 0.4 + 0.4) * 1.25 * 1.5;
                    double inscribedEnder = (rawEnder.getBaseValue() + 0.28 + 0.28) * 1.2;
                    int inscribedLevel = spell.getLevelFor(scrollData.getLevel(), player) + context.data().lvl();
                    var inscribedInfo = spell.getUniqueInfo(inscribedLevel, player).get(0).getString();
                    helper.assertTrue(SpellCastHooks.get() == context && inscribedLevel == directLevel
                                    && near(player.getAttributeValue(power), inscribedPower)
                                    && near(player.getAttributeValue(ender), inscribedEnder)
                                    && near(player.getAttributeValue(AttributeRegistry.MAX_MANA.get()), rawMana.getBaseValue()),
                            "Inscribed scroll preview missed target affix/gem attributes or discarded real equipment");
                    helper.assertTrue(!inscribedInfo.equals(directInfo)
                                    && SpellCastHooks.capture(player, spell, inscribedLevel, context).attributes().isEmpty(),
                            "Inscribed display did not remain distinct from direct scroll casting");
                    try {
                        try (var page = SpellCastHooks.enter(bookContext)) {
                            int bookLevel = spell.getLevelFor(target.getLevel(), player) + bookContext.data().lvl();
                            helper.assertTrue(bookLevel == inscribedLevel
                                            && near(player.getAttributeValue(power), inscribedPower)
                                            && near(player.getAttributeValue(ender), inscribedEnder)
                                            && spell.getUniqueInfo(bookLevel, player).get(0).getString().equals(inscribedInfo),
                                    "Inscribed scroll forecast disagreed with the corresponding real book page");
                            throw new IllegalStateException("nested inscribed preview");
                        }
                    } catch (IllegalStateException expected) {
                        helper.assertTrue(SpellCastHooks.get() == context
                                        && near(player.getAttributeValue(power), inscribedPower)
                                        && near(player.getAttributeValue(ender), inscribedEnder),
                                "A failed book-page preview did not restore the outer inscribed-scroll forecast");
                    }
                }
                helper.assertTrue(SpellCastHooks.get() == context && near(player.getAttributeValue(power), directPower)
                                && near(player.getAttributeValue(ender), directEnder)
                                && spell.getUniqueInfo(directLevel, player).get(0).getString().equals(directInfo),
                        "Closing the inscribed forecast did not restore direct-scroll preview");
            }
            helper.assertTrue(SpellCastHooks.get() == null && SpellCastHooks.currentSnapshot() == null
                            && player.getAttribute(power) == rawPower && player.getAttribute(ender) == rawEnder
                            && near(rawPower.getValue(), selectedPower) && near(rawMana.getValue(), selectedMana)
                            && rawPower.getModifiers().equals(powerModifiers) && rawEnder.getModifiers().equals(enderModifiers)
                            && rawMana.getModifiers().equals(manaModifiers)
                            && player.getAttributes().getDirtyAttributes().equals(dirty)
                            && player.getHealth() == health && magic.getMana() == 100
                            && scrollTag.equals(scroll.getTag()) && bookTag.equals(book.getTag()),
                    "Scroll forecasts changed live attributes, gameplay state, synchronization, or stored affixes");
            helper.succeed();
        } finally {
            BookAttributeHandler.onLogout(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
            player.getAttributes().removeAttributeModifiers(handModifiers);
            player.setItemSlot(hand, ItemStack.EMPTY);
        }
    }

    private static ServerPlayer player(GameTestHelper helper, String name) {
        return FakePlayerFactory.get(helper.getLevel(), new com.mojang.authlib.GameProfile(UUID.randomUUID(), name));
    }

    private static void addOtherModifiers(AttributeInstance attribute) {
        attribute.addTransientModifier(new AttributeModifier(UUID.randomUUID(), "apotheosis_spells:book_affix", 0.1,
                AttributeModifier.Operation.ADDITION));
        attribute.addTransientModifier(new AttributeModifier(UUID.randomUUID(), "other base", 0.25,
                AttributeModifier.Operation.MULTIPLY_BASE));
        attribute.addTransientModifier(new AttributeModifier(UUID.randomUUID(), "other total", 0.5,
                AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    private static boolean near(double actual, double expected) { return Math.abs(actual - expected) < 0.00001; }

    private static ItemStack book() throws Exception {
        var book = new ItemStack(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("irons_spellbooks:netherite_spell_book")));
        book.setTag(TagParser.parseTag("""
                {"irons_spellbooks:spell_container":{maxSpells:12,mustEquip:1b,spellWheel:1b,
                data:[{index:0,id:"irons_spellbooks:acupuncture",level:1},{index:1,id:"irons_spellbooks:acupuncture",level:1},
                {index:2,id:"irons_spellbooks:acupuncture",level:1}]},
                apoth_book_affixes:{"0":{spell_id:"irons_spellbooks:acupuncture",affix_data:{rarity:"apotheosis:ancient",sockets:2,
                gems:[{id:"apotheosis:gem",Count:1b,tag:{gem:"apotheosis_spells:arcane",affix_data:{rarity:"apotheosis:common"}}},
                {id:"apotheosis:gem",Count:1b,tag:{gem:"apotheosis_spells:mana",affix_data:{rarity:"apotheosis:ancient"}}}]}},
                "1":{spell_id:"irons_spellbooks:acupuncture",affix_data:{rarity:"apotheosis:ancient",sockets:2,
                gems:[{id:"apotheosis:gem",Count:1b,tag:{gem:"apotheosis_spells:arcane",affix_data:{rarity:"apotheosis:ancient"}}},
                {id:"apotheosis:gem",Count:1b,tag:{gem:"apotheosis_spells:swiftcast",affix_data:{rarity:"apotheosis:ancient"}}}]}}}}
                """));
        return book;
    }
}
