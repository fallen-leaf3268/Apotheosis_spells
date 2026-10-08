package com.example.apotheosis_spells.affix;

import com.example.apotheosis_spells.affix.spell.*;
import com.google.gson.JsonParser;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixType;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SpellAffixTest {
    @net.minecraftforge.gametest.GameTestHolder("apotheosis_spells")
    @net.minecraftforge.gametest.PrefixGameTestTemplate(false)
    public static class RuntimeTests {
        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void continuousCastingKeepsManaReductionAcrossTicks(net.minecraft.gametest.framework.GameTestHelper helper) throws Exception {
            var player = net.minecraftforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                    new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "[ContinuousManaTest]"));
            var spell = io.redspace.ironsspellbooks.api.registry.SpellRegistry.DRAGON_BREATH_SPELL.get();
            int baseCost = spell.getManaCost(10);
            int reduced = Math.round(baseCost * 0.5f);
            var magic = io.redspace.ironsspellbooks.api.magic.MagicData.getPlayerMagicData(player);
            magic.setServerPlayer(player);
            magic.setSyncedData(new io.redspace.ironsspellbooks.capabilities.magic.SyncedSpellData(player));
            magic.setMana(100);
            magic.initiateCast(spell, 10, 100, io.redspace.ironsspellbooks.api.spells.CastSource.SPELLBOOK, "");
            var snapshot = new com.example.apotheosis_spells.handler.SpellCastHooks.Snapshot(player.getUUID(), spell.getSpellId(),
                    10, new com.example.apotheosis_spells.api.ReforgeCache.Data(1, 0.5f, 1, 1, 0, 1, 1),
                    com.example.apotheosis_spells.api.SpellEffects.NONE);
            com.example.apotheosis_spells.handler.SpellCastHooks.begin(player, snapshot);
            var tick = io.redspace.ironsspellbooks.capabilities.magic.MagicManager.class.getDeclaredMethod(
                    "lambda$tick$0", boolean.class, net.minecraft.world.entity.player.Player.class);
            tick.setAccessible(true);
            try {
                for (int index = 0; index <= 20; index++) {
                    tick.invoke(new io.redspace.ironsspellbooks.capabilities.magic.MagicManager(), false, player);
                    helper.assertTrue(Math.abs(magic.getMana() - (100 - (index / 10 + 1) * reduced)) < 0.001,
                            "Continuous tick lost mana reduction at " + index + ": " + magic.getMana());
                    helper.assertTrue(magic.isCasting(), "Continuous cast ended early despite sufficient discounted mana");
                }
                magic.setMana(reduced * 2);
                magic.initiateCast(spell, 10, 100, io.redspace.ironsspellbooks.api.spells.CastSource.SPELLBOOK, "");
                tick.invoke(new io.redspace.ironsspellbooks.capabilities.magic.MagicManager(), false, player);
                helper.assertTrue(magic.isCasting() && magic.getMana() == reduced,
                        "Continuation check must use reduced mana cost");
            } finally {
                if (magic.getAdditionalCastData() instanceof io.redspace.ironsspellbooks.spells.EntityCastData data
                        && data.getCastingEntity() != null) data.getCastingEntity().discard();
                magic.resetCastingState();
                com.example.apotheosis_spells.handler.SpellCastHooks.forget(player);
            }
            helper.succeed();
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void legacyVoidBecomesTargetSlowness(net.minecraft.gametest.framework.GameTestHelper helper) {
            var rarity = dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry.INSTANCE.getValue(
                    net.minecraft.resources.ResourceLocation.parse("apotheosis:rare"));
            var oldConfig = SchoolSignatureAffix.C.parse(com.mojang.serialization.JsonOps.INSTANCE, JsonParser.parseString("""
                    {"modifier":"sig_ender","school":"ender","action":"blink","distance":5,
                     "types":["apotheosis_spells:scroll"],"values":{"rare":20}}
                    """)).result().orElseThrow();
            var legacy = com.example.apotheosis_spells.api.SpellEffects.ofSignature(5, 20);
            var roundTrip = com.example.apotheosis_spells.api.SpellEffects.read(legacy.write());
            for (var effects : List.of(oldConfig.resolveEffects(rarity, 0), legacy, roundTrip)) {
                var signature = effects.hitSignatures().get(0);
                helper.assertTrue(signature.action().equals("potion") && signature.effect().equals("minecraft:slowness")
                                && signature.target().equals("TARGET") && signature.duration() == 60
                                && signature.amplifier() == 0 && signature.chance() == 1,
                        "Legacy blink must migrate to guaranteed slowness I for 3 seconds");
                var player = net.minecraftforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                        new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "[VoidTest]"));
                var target = net.minecraft.world.entity.EntityType.ZOMBIE.create(helper.getLevel());
                var spell = io.redspace.ironsspellbooks.api.registry.SpellRegistry.FIREBALL_SPELL.get();
                effects.afterDamage(new com.example.apotheosis_spells.api.SpellEffects.DamageContext(player, target,
                        spell.getDamageSource(player), 1));
                helper.assertTrue(target.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN)
                        && !player.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN), "Void must slow the hit target");
                var position = player.position();
                var snapshot = new com.example.apotheosis_spells.handler.SpellCastHooks.Snapshot(player.getUUID(), spell.getSpellId(),
                        1, com.example.apotheosis_spells.api.ReforgeCache.Data.DEF, effects);
                com.example.apotheosis_spells.handler.SpellEffectHandler.afterCast(player, spell, 1,
                        io.redspace.ironsspellbooks.api.spells.CastSource.COMMAND,
                        io.redspace.ironsspellbooks.api.magic.MagicData.getPlayerMagicData(player), snapshot);
                helper.assertTrue(position.equals(player.position()), "Legacy void must not teleport the caster");
            }
            helper.assertTrue(oldConfig.getDescription(net.minecraft.world.item.ItemStack.EMPTY, rarity, 0).toString()
                    .contains("effect.minecraft.slowness"), "Migrated config tooltip must show slowness");
            helper.succeed();
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void manaRefundUsesNativePaidMana(net.minecraft.gametest.framework.GameTestHelper helper) {
            var player = net.minecraftforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                    new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "[RefundTest]"));
            var spell = io.redspace.ironsspellbooks.api.registry.SpellRegistry.FIREBALL_SPELL.get();
            var magic = io.redspace.ironsspellbooks.api.magic.MagicData.getPlayerMagicData(player);
            magic.setServerPlayer(player);
            var effects = com.example.apotheosis_spells.api.SpellEffects.ofSignature(7, 100);
            var snapshot = new com.example.apotheosis_spells.handler.SpellCastHooks.Snapshot(player.getUUID(), spell.getSpellId(),
                    1, com.example.apotheosis_spells.api.ReforgeCache.Data.DEF, effects);
            java.util.function.Consumer<io.redspace.ironsspellbooks.api.events.SpellOnCastEvent> costOverride = event -> {
                if (event.getEntity() == player) event.setManaCost(40);
            };
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.LOWEST,
                    false, io.redspace.ironsspellbooks.api.events.SpellOnCastEvent.class, costOverride);
            try {
                for (var source : io.redspace.ironsspellbooks.api.spells.CastSource.values()) {
                    for (float balance : new float[]{100, 10, 0}) {
                        magic.setMana(balance);
                        try (var scope = com.example.apotheosis_spells.handler.SpellCastHooks.enter(snapshot, player)) {
                            spell.castSpell(helper.getLevel(), 1, player, source, false);
                        }
                        float paid = source.consumesMana() ? Math.min(balance, 40) : 0;
                        helper.assertTrue(Math.abs(magic.getMana() - (balance - paid * 0.5f)) < 0.001f,
                                "Refund must follow final native deduction: " + source + "/" + balance + " actual=" + magic.getMana());
                    }
                }
                magic.setMana(100);
                try (var scope = com.example.apotheosis_spells.handler.SpellCastHooks.enter(snapshot.asEcho(), player)) {
                    spell.castSpell(helper.getLevel(), 1, player, io.redspace.ironsspellbooks.api.spells.CastSource.SPELLBOOK, false);
                }
                helper.assertTrue(magic.getMana() == 60, "Echo snapshot must not trigger refunds");
                int[] adjustment = {0};
                java.util.function.Consumer<io.redspace.ironsspellbooks.api.events.ChangeManaEvent> adjust = event -> {
                    if (event.getEntity() != player || event.getNewMana() >= event.getOldMana()) return;
                    if (adjustment[0] == 0) event.setCanceled(true);
                    else event.setNewMana(event.getOldMana() - 10);
                };
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.LOWEST,
                        false, io.redspace.ironsspellbooks.api.events.ChangeManaEvent.class, adjust);
                try {
                    for (int mode : new int[]{0, 1}) {
                        adjustment[0] = mode;
                        magic.setMana(100);
                        try (var scope = com.example.apotheosis_spells.handler.SpellCastHooks.enter(snapshot, player)) {
                            spell.castSpell(helper.getLevel(), 1, player, io.redspace.ironsspellbooks.api.spells.CastSource.SPELLBOOK, false);
                        }
                        helper.assertTrue(magic.getMana() == (mode == 0 ? 100 : 95), "Refund ignored cancelled or adjusted actual mana deduction");
                    }
                } finally {
                    net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(adjust);
                }
                magic.getPlayerRecasts().forceAddRecast(new io.redspace.ironsspellbooks.capabilities.magic.RecastInstance(
                        spell.getSpellId(), 1, 2, 100, io.redspace.ironsspellbooks.api.spells.CastSource.SPELLBOOK, null));
                magic.setMana(80);
                try (var scope = com.example.apotheosis_spells.handler.SpellCastHooks.enter(snapshot, player)) {
                    spell.castSpell(helper.getLevel(), 1, player, io.redspace.ironsspellbooks.api.spells.CastSource.SPELLBOOK, false);
                }
                helper.assertTrue(magic.getMana() == 80, "Free recast must not restore mana");
            } finally {
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(costOverride);
            }
            helper.succeed();
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void channelOnlyRefreshesWhileCasting(net.minecraft.gametest.framework.GameTestHelper helper) {
            var player = net.minecraftforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                    new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "[ChannelTest]"));
            var effects = com.example.apotheosis_spells.api.SpellEffects.of(new ChannelEffectAffix.Effect(List.of(
                    new com.example.apotheosis_spells.api.SpellEffects.Potion("minecraft:resistance", 120, 2, 160))));
            effects = com.example.apotheosis_spells.api.SpellEffects.read(effects.write());
            var spell = io.redspace.ironsspellbooks.api.registry.SpellRegistry.FIREBALL_SPELL.get();
            var snapshot = new com.example.apotheosis_spells.handler.SpellCastHooks.Snapshot(player.getUUID(), spell.getSpellId(),
                    1, com.example.apotheosis_spells.api.ReforgeCache.Data.DEF, effects);
            var magic = io.redspace.ironsspellbooks.api.magic.MagicData.getPlayerMagicData(player);
            magic.setSyncedData(new io.redspace.ironsspellbooks.capabilities.magic.SyncedSpellData(player));
            magic.initiateCast(spell, 1, 100, io.redspace.ironsspellbooks.api.spells.CastSource.COMMAND, "");
            com.example.apotheosis_spells.handler.SpellCastHooks.begin(player, snapshot);
            com.example.apotheosis_spells.handler.SpellEffectHandler.duringCast(player, snapshot);
            var resistance = net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE;
            helper.assertTrue(player.getEffect(resistance).getDuration() == 10, "Old snapshots must use fixed 10 tick protection");
            for (int tick = 1; tick <= 30; tick++) {
                player.baseTick();
                player.tickCount = tick;
                com.example.apotheosis_spells.handler.SpellEffectHandler.onPlayerTick(
                        new net.minecraftforge.event.TickEvent.PlayerTickEvent(net.minecraftforge.event.TickEvent.Phase.END, player));
                helper.assertTrue(player.hasEffect(resistance), "Channel effect expired between refresh checks");
            }
            magic.resetCastingState();
            for (int tick = 31; tick <= 40; tick++) {
                player.baseTick();
                player.tickCount = tick;
                com.example.apotheosis_spells.handler.SpellEffectHandler.onPlayerTick(
                        new net.minecraftforge.event.TickEvent.PlayerTickEvent(net.minecraftforge.event.TickEvent.Phase.END, player));
            }
            helper.assertTrue(!player.hasEffect(resistance), "Channel effect must expire naturally after casting ends");
            var instant = io.redspace.ironsspellbooks.api.registry.SpellRegistry.BLOOD_SLASH_SPELL.get();
            var instantSnapshot = new com.example.apotheosis_spells.handler.SpellCastHooks.Snapshot(player.getUUID(), instant.getSpellId(),
                    1, com.example.apotheosis_spells.api.ReforgeCache.Data.DEF, effects);
            com.example.apotheosis_spells.handler.SpellEffectHandler.duringCast(player, instantSnapshot);
            helper.assertTrue(!player.hasEffect(resistance), "Instant spells must not trigger channel protection");
            var noneSnapshot = new com.example.apotheosis_spells.handler.SpellCastHooks.Snapshot(player.getUUID(),
                    io.redspace.ironsspellbooks.api.registry.SpellRegistry.none().getSpellId(),
                    1, com.example.apotheosis_spells.api.ReforgeCache.Data.DEF, effects);
            com.example.apotheosis_spells.handler.SpellEffectHandler.duringCast(player, noneSnapshot);
            helper.assertTrue(!player.hasEffect(resistance), "Missing spells must not trigger channel protection");
            helper.succeed();
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void catalogListsAllBranchesWithoutRelaxingRealScrollRules(net.minecraft.gametest.framework.GameTestHelper helper) {
            var registry = dev.shadowsoffire.apotheosis.adventure.affix.AffixRegistry.INSTANCE;
            var rarity = dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry.INSTANCE.getValue(
                    net.minecraft.resources.ResourceLocation.parse("apotheosis:rare"));
            var category = com.example.apotheosis_spells.handler.ScrollLootCategory.SCROLL;
            helper.assertTrue(SpellAffix.supportsEcho(io.redspace.ironsspellbooks.api.registry.SpellRegistry.FIREBALL_SPELL.get()),
                    "Stateless fireball lost echo eligibility");
            helper.assertTrue(!SpellAffix.supportsEcho(io.redspace.ironsspellbooks.api.registry.SpellRegistry.BURNING_DASH_SPELL.get()),
                    "Stateful burning dash gained echo eligibility");
            var missing = new java.util.ArrayList<String>();
            int count = 0;
            for (var entry : registry.getValues()) {
                if (!(entry instanceof SpellAffix affix)) continue;
                count++;
                if (!affix.canApplyTo(net.minecraft.world.item.ItemStack.EMPTY, category, rarity)) missing.add(affix.getId().toString());
                helper.assertTrue(!affix.canApplyTo(net.minecraft.world.item.ItemStack.EMPTY,
                        dev.shadowsoffire.apotheosis.adventure.loot.LootCategory.SWORD, rarity), "Catalog bypassed category rules");
                for (var spell : io.redspace.ironsspellbooks.api.registry.SpellRegistry.REGISTRY.get().getValues()) {
                    var scroll = new net.minecraft.world.item.ItemStack(io.redspace.ironsspellbooks.registries.ItemRegistry.SCROLL.get());
                    io.redspace.ironsspellbooks.api.spells.ISpellContainer.createScrollContainer(spell, 1, scroll);
                    Boolean expected = null;
                    if (affix instanceof EchoAffix) expected = SpellAffix.supportsEcho(spell);
                    else if (affix instanceof ChannelEffectAffix) expected = spell.getCastType() == io.redspace.ironsspellbooks.api.spells.CastType.LONG
                            || spell.getCastType() == io.redspace.ironsspellbooks.api.spells.CastType.CONTINUOUS;
                    else if (affix instanceof SpellRadiusAffix) expected = SpellAffix.supportsRadius(spell);
                    else if (affix instanceof SpellDurationAffix) expected = SpellAffix.supportsDuration(spell);
                    else if (affix instanceof SchoolSignatureAffix signature) {
                        int school = signature.resolveEffects(rarity, 0).hitSignatures().get(0).school();
                        expected = school == 0 || com.example.apotheosis_spells.api.Schools.resource(school).equals(spell.getSchoolType().getId());
                    }
                    if (expected != null) helper.assertTrue(affix.canApplyTo(scroll, category, rarity) == expected,
                            "Catalog fix changed actual spell eligibility: " + affix.getId() + "/" + spell.getSpellId());
                }
                if (affix instanceof SchoolSignatureAffix) {
                    helper.assertTrue(affix.getAugmentingText(net.minecraft.world.item.ItemStack.EMPTY, rarity, 0.5f)
                            .toString().contains("catalog.school"), "Catalog omitted school restriction");
                    helper.assertTrue(affix.getAugmentingText(net.minecraft.world.item.ItemStack.EMPTY, rarity, 0.5f)
                            .getString().contains("\n"), "School restriction must start on a separate line");
                }
                if (affix instanceof EchoAffix || affix instanceof SpellRadiusAffix || affix instanceof SpellDurationAffix) {
                    helper.assertTrue(affix.getAugmentingText(net.minecraft.world.item.ItemStack.EMPTY, rarity, 0.5f)
                            .getString().contains("\n"), "Supported spell notice must start on a separate line");
                }
            }
            helper.assertTrue(count == 24 && missing.isEmpty(), "Missing catalog branches: " + missing + ", total=" + count);
            helper.succeed();
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void descriptionsRespectExistingEffectCaps(net.minecraft.gametest.framework.GameTestHelper helper) {
            var rarity = dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry.INSTANCE.getValue(
                    net.minecraft.resources.ResourceLocation.parse("apotheosis:rare"));
            var types = Set.of("apotheosis_spells:scroll");
            var values = Map.of("rare", new SpellAffix.Fn(80, 1, 40));
            for (var affix : List.of(new ManaCostAffix("mana_cost", values, types), new CooldownAffix("cooldown", values, types),
                    new CastTimeAffix("cast_time", values, types), new EchoAffix("echo", values, types), new CdSkipAffix("cd_skip", values, types))) {
                int cap = affix instanceof EchoAffix || affix instanceof CdSkipAffix ? 100 : 95;
                var empty = net.minecraft.world.item.ItemStack.EMPTY;
                helper.assertTrue(affix.getDescription(empty, rarity, 1).equals(
                        net.minecraft.network.chat.Component.translatable(affix.getModifierKey(), cap)), "Displayed configured value exceeds actual effect cap");
                assertBounds(helper, affix.getAugmentingText(empty, rarity, 1), "80", Integer.toString(cap));
            }
            var negative = Map.of("rare", new SpellAffix.Fn(-10, 1, 0));
            for (var affix : List.of(new WardAffix("ward", negative, types), new ManaStealAffix("mana_steal", negative, types))) {
                helper.assertTrue(affix.resolveEffects(rarity, 0).isEmpty(), "Negative effect unexpectedly active");
                helper.assertTrue(affix.getDescription(net.minecraft.world.item.ItemStack.EMPTY, rarity, 0).equals(
                        net.minecraft.network.chat.Component.translatable(affix.getModifierKey(), 0)), "Inactive effect displayed negative value");
            }
            var execute = new ExecuteAffix("execute", negative, types, Map.of("rare", 25));
            helper.assertTrue(execute.getDescription(net.minecraft.world.item.ItemStack.EMPTY, rarity, 0).equals(
                    net.minecraft.network.chat.Component.translatable(execute.getModifierKey(), 25, "0")), "Inactive execute displayed negative bonus");
            helper.succeed();
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void augmentingApiExposesSpellAndPotionRanges(net.minecraft.gametest.framework.GameTestHelper helper) {
            var rarity = dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry.INSTANCE.getValue(
                    net.minecraft.resources.ResourceLocation.parse("apotheosis:rare"));
            var empty = net.minecraft.world.item.ItemStack.EMPTY;
            var types = Set.of("apotheosis_spells:scroll");
            var values = Map.of("rare", new SpellAffix.Fn(10, 1, 5));
            var echo = new EchoAffix("echo", values, types);
            assertBounds(helper, echo.getAugmentingText(empty, rarity, 0.5f), "10", "15");
            var execute = new ExecuteAffix("execute", values, types, Map.of("rare", 25));
            assertBounds(helper, execute.getAugmentingText(empty, rarity, 0.5f), "10", "15");
            helper.assertTrue(!echo.getDescription(empty, rarity, 0.5f).toString().contains("affix_bounds"),
                    "Normal item description must remain concise");
            var fixed = new WardAffix("ward", Map.of("rare", new SpellAffix.Fn(4, 1, 0)), types);
            helper.assertTrue(fixed.getAugmentingText(empty, rarity, 0.5f).equals(fixed.getDescription(empty, rarity, 0.5f)),
                    "Fixed values must not acquire redundant bounds");
            var post = PostCastEffectAffix.C.parse(com.mojang.serialization.JsonOps.INSTANCE, JsonParser.parseString("""
                    {"types":["apotheosis_spells:scroll"],"effect":"minecraft:speed","values":{"rare":{
                     "amplifier":{"min":0,"steps":2,"step":1},"duration":{"min":100,"steps":3,"step":20},
                     "chance":{"min":0.25,"steps":3,"step":0.25}}}}
                    """)).result().orElseThrow();
            var tooltip = post.getAugmentingText(empty, rarity, 0.5f);
            assertBounds(helper, tooltip, "5s", "8s");
            assertBounds(helper, tooltip, "25%", "100%");
            helper.assertTrue(tooltip.toString().contains("potion.potency.2"), "Potion level range missing");
            assertPotionRangeBesideName(helper, tooltip);
            var channel = ChannelEffectAffix.C.parse(com.mojang.serialization.JsonOps.INSTANCE, JsonParser.parseString("""
                    {"types":["apotheosis_spells:scroll"],"effect":"minecraft:resistance","values":{"rare":{
                     "amplifier":{"min":0,"steps":2,"step":1},"duration":{"min":10,"steps":2,"step":5},
                     "instant_duration":{"min":60,"steps":3,"step":20}}}}
                    """)).result().orElseThrow();
            var scroll = new net.minecraft.world.item.ItemStack(io.redspace.ironsspellbooks.registries.ItemRegistry.SCROLL.get());
            io.redspace.ironsspellbooks.api.spells.ISpellContainer.createScrollContainer(
                    io.redspace.ironsspellbooks.api.registry.SpellRegistry.BLOOD_SLASH_SPELL.get(), 1, scroll);
            assertPotionRangeBesideName(helper, channel.getAugmentingText(scroll, rarity, 0.5f));
            assertPotionRangeBesideName(helper, channel.getAugmentingText(empty, rarity, 0.5f));
            helper.assertTrue(!channel.getAugmentingText(empty, rarity, 0.5f).toString().contains("duration"),
                    "Channel tooltip must not expose internal refresh duration");
            var encodedChannel = ChannelEffectAffix.C.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, channel).result().orElseThrow();
            helper.assertTrue(!encodedChannel.toString().contains("duration"), "Old channel time fields must not be written back");
            var signature = SchoolSignatureAffix.C.parse(com.mojang.serialization.JsonOps.INSTANCE, JsonParser.parseString("""
                    {"modifier":"sig_fire","action":"potion","effect":"minecraft:weakness","types":["apotheosis_spells:scroll"],
                     "values":{"rare":{"amplifier":{"min":0,"steps":1,"step":1},
                     "duration":{"min":60,"steps":2,"step":20},"chance":{"min":0.25,"steps":1,"step":0.25}}}}
                    """)).result().orElseThrow();
            assertBounds(helper, signature.getAugmentingText(empty, rarity, 0.5f), "3s", "5s");
            assertBounds(helper, signature.getAugmentingText(empty, rarity, 0.5f), "25%", "50%");
            assertPotionRangeBesideName(helper, signature.getAugmentingText(empty, rarity, 0.5f));
            var mana = SchoolSignatureAffix.C.parse(com.mojang.serialization.JsonOps.INSTANCE, JsonParser.parseString("""
                    {"modifier":"sig_evocation","action":"mana_refund","types":["apotheosis_spells:scroll"],"values":{"rare":{
                     "chance":{"min":0.2,"steps":1,"step":0.2},"amount":{"min":0.25,"steps":1,"step":0.25}}}}
                    """)).result().orElseThrow();
            assertBounds(helper, mana.getAugmentingText(empty, rarity, 0.5f), "20%", "40%");
            assertBounds(helper, mana.getAugmentingText(empty, rarity, 0.5f), "25%", "50%");
            var holy = SchoolSignatureAffix.C.parse(com.mojang.serialization.JsonOps.INSTANCE, JsonParser.parseString("""
                    {"modifier":"sig_holy","action":"undead_damage","types":["apotheosis_spells:scroll"],"values":{"rare":{
                     "value":{"min":0.001,"steps":1,"step":0.001}}}}
                    """)).result().orElseThrow();
            assertBounds(helper, holy.getAugmentingText(empty, rarity, 0.5f), "0.001%", "0.002%");
            helper.succeed();
        }

        private static void assertPotionRangeBesideName(net.minecraft.gametest.framework.GameTestHelper helper,
                net.minecraft.network.chat.Component text) {
            var contents = (net.minecraft.network.chat.contents.TranslatableContents) text.getContents();
            helper.assertTrue(contents.getArgs()[0] instanceof net.minecraft.network.chat.Component name
                            && name.toString().contains("misc.apotheosis.affix_bounds"),
                    "Potion level bounds must stay beside the potion name, before the duration: " + text);
            helper.assertTrue(text.getSiblings().stream().noneMatch(sibling -> sibling.toString().contains("potion.potency.")),
                    "Potion level bounds leaked to the end of the sentence");
        }

        private static void assertBounds(net.minecraft.gametest.framework.GameTestHelper helper,
                net.minecraft.network.chat.Component text, String min, String max) {
            String expected = dev.shadowsoffire.apotheosis.adventure.affix.Affix.valueBounds(
                    net.minecraft.network.chat.Component.literal(min), net.minecraft.network.chat.Component.literal(max)).getString();
            helper.assertTrue(text.getString().contains(expected), "Missing augmenting range " + min + " - " + max + ": " + text);
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void sharedTemplatesKeepIndependentPotionAffixes(net.minecraft.gametest.framework.GameTestHelper helper) throws Exception {
            var manager = io.redspace.ironsspellbooks.api.config.SpellConfigManager.INSTANCE;
            var configField = manager.getClass().getDeclaredField("config");
            var dirtyField = manager.getClass().getDeclaredField("dirty");
            configField.setAccessible(true);
            dirtyField.setAccessible(true);
            var originalConfig = configField.get(manager);
            var originalDirty = dirtyField.get(manager);
            try {
            io.redspace.ironsspellbooks.api.config.SpellConfigManager.onDatapackSync(
                    new net.minecraftforge.event.OnDatapackSyncEvent(helper.getLevel().getServer().getPlayerList(), null));
            var registry = dev.shadowsoffire.apotheosis.adventure.affix.AffixRegistry.INSTANCE;
            var rarity = dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry.INSTANCE.getValue(
                    net.minecraft.resources.ResourceLocation.parse("apotheosis:rare"));
            var category = com.example.apotheosis_spells.handler.ScrollLootCategory.SCROLL;
            var spells = List.of(io.redspace.ironsspellbooks.api.registry.SpellRegistry.FIREBALL_SPELL.get(),
                    io.redspace.ironsspellbooks.api.registry.SpellRegistry.BLOOD_SLASH_SPELL.get());
            var legacyIds = List.of("postcast_regeneration", "postcast_speed", "sig_fire", "sig_ice",
                    "sig_lightning", "sig_blood", "sig_nature", "sig_eldritch");
            var unifiedIds = Set.of("postcast_effect", "hit_effect_target", "hit_effect_self");
            String prefix = "apotheosis_spells:scroll/spell_modifier/";
            for (var spell : spells) {
                var scroll = new net.minecraft.world.item.ItemStack(io.redspace.ironsspellbooks.registries.ItemRegistry.SCROLL.get());
                io.redspace.ironsspellbooks.api.spells.ISpellContainer.createScrollContainer(spell, 1, scroll);
                for (String id : unifiedIds) {
                    var affix = registry.getValue(net.minecraft.resources.ResourceLocation.parse(prefix + id));
                    helper.assertTrue(affix == null, "Unexpected merged potion definition: " + id);
                }
                var available = dev.shadowsoffire.apotheosis.adventure.loot.LootController.getAvailableAffixes(scroll, rarity, Set.of(), AffixType.ABILITY);
                var activePotions = available.stream().map(holder -> holder.get())
                        .filter(affix -> affix instanceof PostCastEffectAffix
                                || affix instanceof SchoolSignatureAffix signature
                                && signature.contributeEffect(0).hitSignatures().stream().anyMatch(s -> "potion".equals(s.action())))
                        .map(affix -> registry.getKey(affix).getPath()).collect(java.util.stream.Collectors.toSet());
                var expectedPotions = Set.of("scroll/spell_modifier/postcast_regeneration", "scroll/spell_modifier/postcast_speed",
                        "scroll/spell_modifier/sig_" + spell.getSchoolType().getId().getPath());
                helper.assertTrue(activePotions.equals(expectedPotions), "Shared templates changed independent potion rolls: " + activePotions);
                for (String id : legacyIds) {
                    var affix = (SpellAffix) registry.getValue(net.minecraft.resources.ResourceLocation.parse(prefix + id));
                    helper.assertTrue(affix != null, "Independent potion definition missing: " + id);
                    var data = new net.minecraft.nbt.CompoundTag();
                    data.putString("rarity", "apotheosis:rare");
                    var entries = new net.minecraft.nbt.CompoundTag();
                    entries.putFloat(prefix + id, 0);
                    data.put(dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper.AFFIXES, entries);
                    var expected = affix.resolveEffects(rarity, 0);
                    helper.assertTrue(!expected.isEmpty(), "Legacy definition lost its effect: " + id);
                    var legacySpell = id.startsWith("sig_")
                            ? io.redspace.ironsspellbooks.api.registry.SpellRegistry.REGISTRY.get().getValues().stream()
                            .filter(candidate -> candidate.getSchoolType().getId().getPath().equals(id.substring(4))).findFirst()
                            .orElseThrow(() -> new IllegalStateException("No registered spell for " + id))
                            : spell;
                    var restoredScroll = com.example.apotheosis_spells.api.ReforgeCache.createAffixedScroll(legacySpell, 1, data);
                    var candidates = dev.shadowsoffire.apotheosis.adventure.loot.LootController.getAvailableAffixes(
                            restoredScroll, rarity, Set.of(), AffixType.ABILITY);
                    helper.assertTrue(candidates.stream().anyMatch(holder -> holder.getId().toString().equals(prefix + id)),
                            "Independent potion was removed from its school's pool: " + id);
                    var restored = com.example.apotheosis_spells.api.ReforgeCache.computeEffects(
                            com.example.apotheosis_spells.api.ReforgeCache.scrollAffixData(restoredScroll));
                    helper.assertTrue(restored.equals(expected), "Legacy scroll changed its effect: " + id);
                    var nativeAffixes = dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper.getAffixes(restoredScroll);
                    helper.assertTrue(nativeAffixes.keySet().stream().anyMatch(holder -> holder.getId().toString().equals(prefix + id)),
                            "Native tooltip hid legacy affix: " + id);
                    var echoHolder = registry.holder(net.minecraft.resources.ResourceLocation.parse(prefix + "echo"));
                    dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper.applyAffix(restoredScroll,
                            new dev.shadowsoffire.apotheosis.adventure.affix.AffixInstance(echoHolder, restoredScroll,
                                    dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry.INSTANCE.holder(rarity), 0));
                    helper.assertTrue(restoredScroll.getTagElement(dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper.AFFIX_DATA)
                            .getCompound(dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper.AFFIXES).contains(prefix + id),
                            "Native augmenting removed legacy affix: " + id);
                    var book = new net.minecraft.world.item.ItemStack(io.redspace.ironsspellbooks.registries.ItemRegistry.LEGENDARY_SPELL_BOOK.get());
                    io.redspace.ironsspellbooks.api.spells.ISpellContainer.createImbuedContainer(legacySpell, 1, book);
                    com.example.apotheosis_spells.api.ReforgeCache.setBookAffix(book, 0, data);
                    helper.assertTrue(com.example.apotheosis_spells.api.ReforgeCache.getEffectsFromSpellBook(book, 0).equals(expected),
                            "Legacy book changed its effect: " + id);
                }
            }
            var targetAffix = (SpellAffix) registry.getValue(net.minecraft.resources.ResourceLocation.parse(prefix + "sig_fire"));
            var description = targetAffix.getDescription(net.minecraft.world.item.ItemStack.EMPTY, rarity, 0).toString();
            helper.assertTrue(description.contains("potion.target.guaranteed") && !description.contains("100"),
                    "Guaranteed potion should omit probability");
            var selfAffix = (SpellAffix) registry.getValue(net.minecraft.resources.ResourceLocation.parse(prefix + "sig_blood"));
            description = selfAffix.getDescription(net.minecraft.world.item.ItemStack.EMPTY, rarity, 0).toString();
            helper.assertTrue(description.contains("potion.attacker") && description.contains("30") && !description.contains("guaranteed"),
                    "Probabilistic potion lost probability or recipient");
            helper.succeed();
            } finally {
                configField.set(manager, originalConfig);
                dirtyField.set(manager, originalDirty);
            }
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void rarityCurvesReachTooltipsAndLivePotions(net.minecraft.gametest.framework.GameTestHelper helper) throws Exception {
            var registry = dev.shadowsoffire.apotheosis.adventure.affix.AffixRegistry.INSTANCE;
            var player = net.minecraftforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                    new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "[RarityPotionTest]"));
            for (String id : List.of("channel_resistance", "postcast_regeneration", "postcast_speed",
                    "sig_fire", "sig_ice", "sig_lightning", "sig_blood", "sig_nature", "sig_eldritch", "sig_ender")) {
                String key = "apotheosis_spells:scroll/spell_modifier/" + id;
                var affix = (SpellAffix) registry.getValue(net.minecraft.resources.ResourceLocation.parse(key));
                try (var stream = SpellAffixTest.class.getResourceAsStream(
                        "/data/apotheosis_spells/affixes/scroll/spell_modifier/" + id + ".json")) {
                    var config = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                    for (var grade : config.getAsJsonObject("values").entrySet()) {
                        var rarity = dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry.INSTANCE.getValue(
                                net.minecraft.resources.ResourceLocation.parse("apotheosis:" + grade.getKey()));
                        if (rarity == null) continue;
                        for (float level : new float[] {0, 0.02f, 0.5f, 1}) {
                            var fields = grade.getValue().getAsJsonObject();
                            int duration = id.startsWith("channel") ? 10 : dev.shadowsoffire.placebo.util.StepFunction.CODEC.parse(
                                    com.mojang.serialization.JsonOps.INSTANCE, fields.get("duration")).result().orElseThrow().getInt(level);
                            int amplifier = dev.shadowsoffire.placebo.util.StepFunction.CODEC.parse(
                                    com.mojang.serialization.JsonOps.INSTANCE, fields.get("amplifier")).result().orElseThrow().getInt(level);
                            var data = new net.minecraft.nbt.CompoundTag();
                            data.putString("rarity", "apotheosis:" + grade.getKey());
                            var entries = new net.minecraft.nbt.CompoundTag();
                            entries.putFloat(key, level);
                            data.put(dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper.AFFIXES, entries);
                            var effects = com.example.apotheosis_spells.api.ReforgeCache.computeEffects(data);
                            helper.assertTrue(effects.equals(affix.resolveEffects(rarity, level)), "Scroll roll lost rarity parameters: " + id);
                            helper.assertTrue(effects.equals(com.example.apotheosis_spells.api.SpellEffects.read(effects.write())),
                                    "Frozen effect lost rarity parameters: " + id);
                            if (id.startsWith("sig_")) {
                                var signature = effects.hitSignatures().get(0);
                                helper.assertTrue(signature.duration() == duration && signature.amplifier() == amplifier
                                                && signature.chance() == fields.get("chance").getAsFloat(),
                                        "Hit potion differs from native steps: " + id);
                            } else {
                                player.removeAllEffects();
                                if (id.startsWith("channel")) effects.duringCast(player);
                                else effects.afterCompletion(player);
                                var effectId = net.minecraft.resources.ResourceLocation.parse(config.get("effect").getAsString());
                                var applied = player.getEffect(net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.get(effectId));
                                helper.assertTrue(duration <= 0 ? applied == null
                                                : applied != null && applied.getAmplifier() == amplifier && applied.getDuration() == duration,
                                        "Live potion differs from native steps: " + id + "/" + grade.getKey() + "/" + level
                                                + " expected=" + amplifier + ":" + duration + " actual=" + applied);
                            }
                            String description = affix.getDescription(net.minecraft.world.item.ItemStack.EMPTY, rarity, level).toString();
                            helper.assertTrue(amplifier == 0 || description.contains("potion.potency." + amplifier),
                                    "Tooltip disagrees with applied potion level: " + id);
                        }
                    }
                }
            }
            var rarity = dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry.INSTANCE.getValue(
                    net.minecraft.resources.ResourceLocation.parse("apotheosis:rare"));
            var config = JsonParser.parseString("""
                    {"types":["apotheosis_spells:scroll"],"effect":"minecraft:haste",
                     "values":{"rare":{"duration":47,"chance":{"min":0,"steps":1,"step":1}}}}
                    """);
            var postcast = PostCastEffectAffix.C.parse(com.mojang.serialization.JsonOps.INSTANCE, config).result().orElseThrow();
            player.removeAllEffects();
            postcast.resolveEffects(rarity, 0).afterCompletion(player);
            helper.assertTrue(!player.hasEffect(net.minecraft.world.effect.MobEffects.DIG_SPEED), "Zero chance potion triggered");
            postcast.resolveEffects(rarity, 1).afterCompletion(player);
            var applied = player.getEffect(net.minecraft.world.effect.MobEffects.DIG_SPEED);
            helper.assertTrue(applied != null && applied.getAmplifier() == 0 && applied.getDuration() == 47,
                    "Omitted amplifier must default to level I and use configured chance");
            var channel = ChannelEffectAffix.C.parse(com.mojang.serialization.JsonOps.INSTANCE,
                    JsonParser.parseString("""
                    {"types":["apotheosis_spells:scroll"],"effect":"minecraft:resistance",
                     "values":{"rare":{"duration":19,"instant_duration":{"min":60,"steps":3,"step":20}}}}
                    """)).result().orElseThrow();
            player.removeAllEffects();
            var instant = io.redspace.ironsspellbooks.api.registry.SpellRegistry.BLOOD_SLASH_SPELL.get();
            var effects = channel.resolveEffects(rarity, 1);
            var snapshot = new com.example.apotheosis_spells.handler.SpellCastHooks.Snapshot(player.getUUID(), instant.getSpellId(),
                    1, com.example.apotheosis_spells.api.ReforgeCache.Data.DEF, effects);
            com.example.apotheosis_spells.handler.SpellEffectHandler.afterCast(player, instant, 1,
                    io.redspace.ironsspellbooks.api.spells.CastSource.COMMAND,
                    io.redspace.ironsspellbooks.api.magic.MagicData.getPlayerMagicData(player), snapshot);
            applied = player.getEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
            helper.assertTrue(applied == null, "Legacy channel config must not protect instant spells");
            effects.duringCast(player);
            applied = player.getEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
            helper.assertTrue(applied != null && applied.getAmplifier() == 0 && applied.getDuration() == 10,
                    "Legacy time-only channel config must retain level I with fixed duration");
            var decimal = SchoolSignatureAffix.C.parse(com.mojang.serialization.JsonOps.INSTANCE,
                    JsonParser.parseString("""
                    {"modifier":"sig_holy","action":"undead_damage","types":["apotheosis_spells:scroll"],
                     "values":{"rare":{"value":{"min":1.5,"steps":2,"step":0.5}}}}
                    """)).result().orElseThrow();
            helper.assertTrue(decimal.resolveEffects(rarity, 0).hitSignatures().get(0).value() == 1.5f,
                    "Custom fractional damage bonus was truncated");
            player.removeAllEffects();
            helper.succeed();
        }

        private record CastProbe() implements com.example.apotheosis_spells.api.SpellEffects.Module {
            private static final com.mojang.serialization.Codec<CastProbe> CODEC = com.mojang.serialization.Codec.unit(new CastProbe());
            @Override public String type() { return "apotheosis_spells_test:cast_probe"; }
            @Override public CastProbe merge(com.example.apotheosis_spells.api.SpellEffects.Module other) { return this; }
            @Override public void beforeCast(io.redspace.ironsspellbooks.api.events.SpellOnCastEvent event) {
                event.setSpellLevel(event.getSpellLevel() + 2);
            }
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void genericBeforeCastReachesFreeCastsWithoutApplyingManaRefund(
                net.minecraft.gametest.framework.GameTestHelper helper) {
            com.example.apotheosis_spells.api.SpellEffects.register("apotheosis_spells_test:cast_probe", CastProbe.CODEC);
            var player = net.minecraftforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                    new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "[CastProbeTest]"));
            var spell = io.redspace.ironsspellbooks.api.registry.SpellRegistry.FIREBALL_SPELL.get();
            var effects = com.example.apotheosis_spells.api.SpellEffects.of(new CastProbe())
                    .merge(com.example.apotheosis_spells.api.SpellEffects.ofSignature(7, 100));
            var snapshot = new com.example.apotheosis_spells.handler.SpellCastHooks.Snapshot(player.getUUID(),
                    spell.getSpellId(), 1, com.example.apotheosis_spells.api.ReforgeCache.Data.DEF, effects);
            for (var source : java.util.List.of(io.redspace.ironsspellbooks.api.spells.CastSource.COMMAND,
                    io.redspace.ironsspellbooks.api.spells.CastSource.SCROLL,
                    io.redspace.ironsspellbooks.api.spells.CastSource.SPELLBOOK)) {
                int cost = source == io.redspace.ironsspellbooks.api.spells.CastSource.COMMAND ? 0 : 10;
                var event = new io.redspace.ironsspellbooks.api.events.SpellOnCastEvent(player, spell.getSpellId(),
                        1, cost, spell.getSchoolType(), source);
                try (var scope = com.example.apotheosis_spells.handler.SpellCastHooks.enter(snapshot, player)) {
                    com.example.apotheosis_spells.handler.SpellEffectHandler.onSpellCast(event);
                }
                helper.assertTrue(event.getSpellLevel() == 3, "Generic beforeCast missed " + source);
                helper.assertTrue(event.getManaCost() == cost,
                        "Post-cast mana refund must not discount the pre-cast cost: " + source);
            }
            helper.succeed();
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void configuredPotionChangesBehaviorAndDescriptionWithoutChangingDispatcher(
                net.minecraft.gametest.framework.GameTestHelper helper) throws Exception {
            var player = net.minecraftforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                    new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "[ModuleConfigTest]"));
            var rarity = dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry.INSTANCE.getValue(
                    net.minecraft.resources.ResourceLocation.parse("apotheosis:rare"));
            try (var stream = SpellAffixTest.class.getResourceAsStream(
                    "/data/apotheosis_spells/affixes/scroll/spell_modifier/postcast_regeneration.json")) {
                var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                json.addProperty("effect", "minecraft:haste");
                json.getAsJsonObject("values").getAsJsonObject("rare").addProperty("duration", 47);
                json.getAsJsonObject("values").getAsJsonObject("rare").addProperty("amplifier", 2);
                var affix = PostCastEffectAffix.C.parse(com.mojang.serialization.JsonOps.INSTANCE, json).result().orElseThrow();
                var frozen = affix.resolveEffects(rarity, 0);
                var description = affix.getDescription(net.minecraft.world.item.ItemStack.EMPTY, rarity, 0).toString();
                helper.assertTrue(description.contains("effect.minecraft.haste") && !description.contains("effect.minecraft.regeneration"),
                        "Description ignored configured potion id");
                helper.assertTrue(description.contains("2.35") && description.contains("potion.potency.2")
                        && !description.contains("potion.withDuration"), "Potion description must use configured level and seconds");
                json.addProperty("effect", "minecraft:strength");
                var changed = PostCastEffectAffix.C.parse(com.mojang.serialization.JsonOps.INSTANCE, json).result().orElseThrow();
                com.example.apotheosis_spells.handler.SpellEffectHandler.afterCompletion(player,
                        com.example.apotheosis_spells.api.SpellEffects.read(frozen.write()));
                var haste = player.getEffect(net.minecraft.world.effect.MobEffects.DIG_SPEED);
                helper.assertTrue(haste != null && haste.getAmplifier() == 2 && haste.getDuration() == 47,
                        "Configured potion level or duration was not applied");
                helper.assertTrue(!player.hasEffect(net.minecraft.world.effect.MobEffects.DAMAGE_BOOST),
                        "A frozen cast borrowed changed configuration");
                com.example.apotheosis_spells.handler.SpellEffectHandler.afterCompletion(player,
                        changed.resolveEffects(rarity, 0));
                helper.assertTrue(player.hasEffect(net.minecraft.world.effect.MobEffects.DAMAGE_BOOST),
                        "New configuration did not apply to a new effect snapshot");
            }
            try (var stream = SpellAffixTest.class.getResourceAsStream(
                    "/data/apotheosis_spells/affixes/scroll/spell_modifier/sig_holy.json")) {
                var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                json.addProperty("action", "potion");
                json.addProperty("effect", "minecraft:speed");
                json.addProperty("target", "ATTACKER");
                json.addProperty("duration", 41);
                json.addProperty("amplifier", 2);
                json.addProperty("chance", 1);
                var affix = SchoolSignatureAffix.C.parse(com.mojang.serialization.JsonOps.INSTANCE, json).result().orElseThrow();
                var effects = affix.resolveEffects(rarity, 0);
                var target = net.minecraft.world.entity.EntityType.ZOMBIE.create(helper.getLevel());
                var source = io.redspace.ironsspellbooks.damage.SpellDamageSource.source(
                        player, io.redspace.ironsspellbooks.api.registry.SpellRegistry.FIREBALL_SPELL.get());
                var context = new com.example.apotheosis_spells.api.SpellEffects.DamageContext(player, target, source, 10);
                helper.assertTrue(effects.modifyDamage(context, 10) == 10, "Potion action still triggered old undead bonus");
                effects.afterDamage(context);
                var speed = player.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED);
                helper.assertTrue(speed != null && speed.getAmplifier() == 2 && speed.getDuration() == 41,
                        "School potion config was not applied to its configured recipient");
                var description = affix.getDescription(net.minecraft.world.item.ItemStack.EMPTY, rarity, 0).toString();
                helper.assertTrue(description.contains("effect.minecraft.speed"), "School description ignored configured effect");
            }
            player.removeAllEffects();
            var channel = new ChannelEffectAffix(Map.of("rare", new SpellAffix.Fn(2, 0, 0)),
                    Set.of("apotheosis_spells:scroll"), "minecraft:resistance", 19, 73);
            var channelEffects = com.example.apotheosis_spells.api.SpellEffects.read(channel.contributeEffect(2).write());
            channelEffects.duringCast(player);
            helper.assertTrue(player.getEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE).getDuration() == 10,
                    "Legacy channel constructor must use fixed refresh duration");
            player.removeAllEffects();
            var instant = io.redspace.ironsspellbooks.api.registry.SpellRegistry.BLOOD_SLASH_SPELL.get();
            var snapshot = new com.example.apotheosis_spells.handler.SpellCastHooks.Snapshot(player.getUUID(), instant.getSpellId(),
                    1, com.example.apotheosis_spells.api.ReforgeCache.Data.DEF, channelEffects);
            com.example.apotheosis_spells.handler.SpellEffectHandler.afterCast(player, instant, 1,
                    io.redspace.ironsspellbooks.api.spells.CastSource.COMMAND,
                    io.redspace.ironsspellbooks.api.magic.MagicData.getPlayerMagicData(player), snapshot);
            helper.assertTrue(!player.hasEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE),
                    "Persisted channel modules must not protect instant spells");
            player.removeAllEffects();
            helper.succeed();
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void absentAncientPoolPreservesOrdinaryRulesAndRestoresWhenAvailable(
                net.minecraft.gametest.framework.GameTestHelper helper) throws Exception {
            var registry = dev.shadowsoffire.apotheosis.adventure.affix.AffixRegistry.INSTANCE;
            var ancient = dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry.INSTANCE.getValue(
                    net.minecraft.resources.ResourceLocation.parse("apotheosis:ancient"));
            var rulesField = ancient.getClass().getDeclaredField("rules");
            rulesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var originalRules = (List<dev.shadowsoffire.apotheosis.adventure.loot.LootRarity.LootRule>) rulesField.get(ancient);
            var typeMapField = registry.getClass().getDeclaredField("byType");
            typeMapField.setAccessible(true);
            var originalTypes = typeMapField.get(registry);
            try {
                typeMapField.set(registry, com.google.common.collect.ImmutableMultimap.of());
                var expected = originalRules.stream()
                        .filter(rule -> rule.type() != AffixType.ANCIENT || rule.backup() != null).toList();
                helper.assertTrue(expected.size() < originalRules.size(), "Fixture has an unsupported ancient slot");
                helper.assertTrue(ancient.getRules().equals(expected),
                        "Empty ANCIENT pool must omit only unavailable exclusive slots, preserving ordinary budgets");
                helper.assertTrue(rulesField.get(ancient) == originalRules,
                        "Filtering must preserve the source rules for subsequent reloads");
                typeMapField.set(registry, com.google.common.collect.ImmutableMultimap.of(
                        AffixType.ANCIENT, registry.holder(net.minecraft.resources.ResourceLocation.parse("apotheosis:durable"))));
                helper.assertTrue(ancient.getRules().equals(originalRules),
                        "An available ancient pool must immediately restore native exclusive rules");
            } finally {
                typeMapField.set(registry, originalTypes);
            }
            helper.succeed();
        }

    }

    @Test
    void affixNumbersUseNativeStepsAndAcceptNativeConstants() {
        var nativeFunction = new dev.shadowsoffire.placebo.util.StepFunction(80, 3, 20);
        var function = new SpellAffix.Fn(80, 3, 20);
        for (int i = 0; i <= 200; i++) {
            float level = i / 200f;
            assertEquals(nativeFunction.getInt(level), function.get(level), "Native step mismatch at " + level);
        }
        var constant = SpellAffix.Fn.C.parse(com.mojang.serialization.JsonOps.INSTANCE, new com.google.gson.JsonPrimitive(3));
        assertTrue(constant.result().isPresent());
        assertEquals(3, constant.result().orElseThrow().get(0.7f));
        var legacy = SpellAffix.Fn.C.parse(com.mojang.serialization.JsonOps.INSTANCE,
                JsonParser.parseString("{\"min\":2,\"steps\":0,\"step\":5}")).result().orElseThrow();
        assertEquals(2, legacy.get(1));
        var encoded = SpellAffix.Fn.C.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, legacy).result().orElseThrow();
        assertTrue(dev.shadowsoffire.placebo.util.StepFunction.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, encoded).result().isPresent());
    }

    @Test
    void potionTemplatesAcceptPerRarityEffectParameters() {
        var json = JsonParser.parseString("""
                {"modifier":"sig_fire","school":"fire","action":"potion","effect":"minecraft:weakness",
                 "types":["apotheosis_spells:scroll"],"values":{"rare":{
                   "amplifier":{"min":0,"steps":2,"step":1},
                   "duration":{"min":60,"steps":3,"step":20},
                   "chance":{"min":0.2,"steps":4,"step":0.2}}}}
                """);
        var result = SchoolSignatureAffix.C.parse(com.mojang.serialization.JsonOps.INSTANCE, json);
        assertTrue(result.result().isPresent(), "Per-rarity potion parameters rejected");
    }

    @Test
    void rarityParametersRejectInvalidRangesAndMixedSchemas() {
        var codec = SpellAffix.Values.codec("value", "duration", "amplifier", "chance");
        for (String invalid : List.of(
                "{\"rare\":{\"min\":1,\"steps\":1,\"step\":0,\"chance\":2}}",
                "{\"rare\":{\"chance\":{\"min\":0.5,\"steps\":3,\"step\":0.2}}}",
                "{\"rare\":{\"duration\":-1}}",
                "{\"rare\":{\"amplifier\":{\"min\":0,\"steps\":2,\"step\":-1}}}",
                "{\"rare\":{\"duration\":{\"min\":1,\"steps\":-1,\"step\":1}}}",
                "{\"rare\":{\"duration\":{\"min\":1e39,\"steps\":1,\"step\":1}}}",
                "{\"rare\":{\"duraton\":100}}", "{\"rare\":{}}")) {
            assertTrue(codec.parse(com.mojang.serialization.JsonOps.INSTANCE, JsonParser.parseString(invalid)).error().isPresent(), invalid);
        }
        var legacy = codec.parse(com.mojang.serialization.JsonOps.INSTANCE,
                JsonParser.parseString("{\"rare\":{\"min\":2,\"steps\":0,\"step\":0}}")).result().orElseThrow();
        var encoded = codec.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, legacy).result().orElseThrow();
        assertEquals(legacy, codec.parse(com.mojang.serialization.JsonOps.INSTANCE, encoded).result().orElseThrow());
        var potion = new com.example.apotheosis_spells.api.SpellEffects.Potion("minecraft:speed", 100, 2, 60, 0.3f);
        var effects = com.example.apotheosis_spells.api.SpellEffects.of(new PostCastEffectAffix.Effect(List.of(potion), 0));
        assertEquals(effects, com.example.apotheosis_spells.api.SpellEffects.read(effects.write()));
        var oldPotion = com.example.apotheosis_spells.api.SpellEffects.Potion.CODEC.parse(
                com.mojang.serialization.JsonOps.INSTANCE, JsonParser.parseString(
                        "{\"effect\":\"minecraft:speed\",\"duration\":100,\"amplifier\":2}")).result().orElseThrow();
        assertEquals(1, oldPotion.chance());
    }

    @Test
    void disabledPostcastPotionCannotSuppressAnActiveBranch() {
        var active = new PostCastEffectAffix.Effect(List.of(
                new com.example.apotheosis_spells.api.SpellEffects.Potion("minecraft:speed", 100, 0, 60, 1)), 0);
        var disabled = new PostCastEffectAffix.Effect(List.of(
                new com.example.apotheosis_spells.api.SpellEffects.Potion("minecraft:speed", 200, 3, 60, 0)), 0);
        assertEquals(active, active.merge(disabled));
        assertEquals(active, disabled.merge(active));
    }

    @Test
    void genericHitPotionAcceptsAnySchoolAndPersistsItsConfiguration() throws Exception {
        try (var stream = getClass().getResourceAsStream("/data/apotheosis_spells/affixes/scroll/spell_modifier/sig_fire.json")) {
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            json.remove("school");
            json.addProperty("duration", 60);
            json.add("values", JsonParser.parseString("{\"rare\":{\"min\":0,\"steps\":0,\"step\":0}}"));
            var decoded = SchoolSignatureAffix.C.parse(com.mojang.serialization.JsonOps.INSTANCE, json);
            assertTrue(decoded.result().isPresent(), "Generic hit potion must not require a school");
            var effect = decoded.result().orElseThrow().contributeEffect(0);
            assertFalse(effect.isEmpty());
            assertEquals(0, effect.hitSignatures().get(0).school());
            assertEquals(effect, com.example.apotheosis_spells.api.SpellEffects.read(effect.write()));
            assertEquals(effect, com.example.apotheosis_spells.api.SpellEffects.ofSchoolSignature(
                    0, 0, "TARGET", "minecraft:weakness", 60, 0, 1));
            json.addProperty("school", "unknown_school");
            assertTrue(SchoolSignatureAffix.C.parse(com.mojang.serialization.JsonOps.INSTANCE, json).error().isPresent());
        }
    }

    @Test
    void schoolPotionConfigurationRejectsInvalidParameters() throws Exception {
        try (var stream = getClass().getResourceAsStream("/data/apotheosis_spells/affixes/scroll/spell_modifier/sig_fire.json")) {
            var valid = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(SchoolSignatureAffix.C.parse(com.mojang.serialization.JsonOps.INSTANCE, valid).result().isPresent());
            var badAction = valid.deepCopy();
            badAction.addProperty("action", "unknown_action");
            assertTrue(SchoolSignatureAffix.C.parse(com.mojang.serialization.JsonOps.INSTANCE, badAction).error().isPresent());
            for (var entry : Map.<String, Number>of("chance", 1.1f, "amount", -1, "distance", 129,
                    "duration", -1, "amplifier", -1).entrySet()) {
                var invalid = valid.deepCopy();
                invalid.addProperty(entry.getKey(), entry.getValue());
                assertTrue(SchoolSignatureAffix.C.parse(com.mojang.serialization.JsonOps.INSTANCE, invalid).error().isPresent(), entry.getKey());
            }
        }
    }

    @Test
    void everySpellPrefixSharesTheVanillaAbilityPool() {
        Map<String, SpellAffix.Fn> values = Map.of("rare", new SpellAffix.Fn(10, 1, 5));
        Set<String> types = Set.of("apotheosis_spells:scroll");
        List<SpellAffix> prefixes = List.of(
                new CastTimeAffix("cast_time", values, types),
                new CooldownAffix("cooldown", values, types),
                new ManaCostAffix("mana_cost", values, types),
                new SpellPowerAffix("spell_power", values, types),
                new SpellLevelAffix("spell_level", values, types),
                new SpellRadiusAffix("spell_radius", values, types),
                new SpellDurationAffix("spell_duration", values, types),
                new ManaStealAffix("mana_steal", values, types),
                new ExecuteAffix("execute", values, types, Map.of("rare", 25)),
                new EchoAffix("echo", values, types),
                new CdSkipAffix("cd_skip", values, types),
                new WardAffix("ward", values, types),
                new ChannelEffectAffix(values, types, "minecraft:regeneration"),
                new PostCastEffectAffix(values, types, "minecraft:speed", 100),
                new SchoolSignatureAffix("signature", values, types, "fire",
                        "TARGET", "minecraft:slowness", 60, 0, 0.3f));
        assertAll(prefixes.stream().map(prefix -> () ->
                assertEquals(AffixType.ABILITY, prefix.getType(), prefix.getClass().getSimpleName())));
    }

    @Test
    void scrollsDoNotInstallAnExtraPrefixBudget() throws Exception {
        try (var stream = getClass().getResourceAsStream("/apotheosis_spells.mixins.json")) {
            assertNotNull(stream);
            var config = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var mixin : config.getAsJsonArray("mixins")) {
                assertNotEquals("LootRarityRulesMixin", mixin.getAsString());
                assertNotEquals("LootControllerMixin", mixin.getAsString());
            }
        }
    }
}
