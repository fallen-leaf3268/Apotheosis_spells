package com.example.apotheosis_spells.handler;

import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.api.SpellEffects;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SpellCastHooksTest {
    @net.minecraftforge.gametest.GameTestHolder("apotheosis_spells")
    @net.minecraftforge.gametest.PrefixGameTestTemplate(false)
    public static class RuntimeTests {
        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void normalAndQuickCastingKeepDuplicatePhysicalSlots(net.minecraft.gametest.framework.GameTestHelper helper) throws Exception {
            var player = net.minecraftforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "[DuplicateCastSlots]"));
            var book = new net.minecraft.world.item.ItemStack(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                    net.minecraft.resources.ResourceLocation.parse("irons_spellbooks:netherite_spell_book")));
            book.setTag(net.minecraft.nbt.TagParser.parseTag("""
                    {"irons_spellbooks:spell_container":{maxSpells:12,mustEquip:1b,spellWheel:1b,
                    data:[{index:0,id:"irons_spellbooks:fireball",level:1},{index:2,id:"irons_spellbooks:fireball",level:2},
                    {index:4,id:"irons_spellbooks:scorch",level:1}]},
                    apoth_book_affixes:{"2":{spell_id:"irons_spellbooks:fireball",affix_data:{rarity:"apotheosis:ancient",
                    affixes:{"apotheosis_spells:scroll/spell_modifier/mana_cost":1.0f}}}}}
                    """));
            io.redspace.ironsspellbooks.api.util.Utils.setPlayerSpellbookStack(player, book);
            var magic = io.redspace.ironsspellbooks.api.magic.MagicData.getPlayerMagicData(player);
            magic.setMana(100);
            var observed = new java.util.ArrayList<Float>();
            java.util.function.Consumer<io.redspace.ironsspellbooks.api.events.SpellPreCastEvent> listener = event -> {
                if (event.getEntity() != player) return;
                var snapshot = SpellCastHooks.currentSnapshot();
                helper.assertTrue(snapshot != null, "Cast request lost its snapshot");
                observed.add(snapshot.data().mana());
                event.setCanceled(true);
            };
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                    net.minecraftforge.eventbus.api.EventPriority.LOWEST, false,
                    io.redspace.ironsspellbooks.api.events.SpellPreCastEvent.class, listener);
            try {
                magic.getSyncedData().setSpellSelection(new io.redspace.ironsspellbooks.gui.overlays.SpellSelection("spellbook", 1));
                var options = new io.redspace.ironsspellbooks.api.magic.SpellSelectionManager(player).getAllSpells();
                var duplicate = options.stream().filter(option -> option.slot.equals("spellbook") && option.slotIndex == 1).findFirst().orElseThrow();
                var other = options.stream().filter(option -> option.slot.equals("spellbook") && option.slotIndex == 2).findFirst().orElseThrow();
                helper.assertTrue(SpellCastHooks.resolveSelectionSlot(book, duplicate).index() == 2,
                        "Native duplicate selection must keep the higher level's sparse physical slot");
                io.redspace.ironsspellbooks.api.util.Utils.serverSideInitiateCast(player);
                helper.assertTrue(observed.size() == 1 && observed.get(0) == 0.5f,
                        "Normal cast used the first duplicate's affixes: " + observed);
                magic.getSyncedData().setSpellSelection(new io.redspace.ironsspellbooks.gui.overlays.SpellSelection("spellbook", 2));
                io.redspace.ironsspellbooks.api.util.Utils.serverSideInitiateQuickCast(player, duplicate.globalIndex);
                helper.assertTrue(observed.size() == 2 && observed.get(1) == 0.5f,
                        "Quick cast lost its own sparse physical slot: " + observed);
                magic.getSyncedData().setSpellSelection(new io.redspace.ironsspellbooks.gui.overlays.SpellSelection("spellbook", 1));
                io.redspace.ironsspellbooks.api.util.Utils.serverSideInitiateQuickCast(player, other.globalIndex);
                helper.assertTrue(observed.size() == 3 && observed.get(2) == 1f,
                        "Quick cast borrowed the currently selected duplicate's affixes: " + observed);
                helper.assertTrue(SpellCastHooks.get() == null && SpellCastHooks.currentSnapshot() == null,
                        "Cancelled request leaked a cast scope");
                helper.succeed();
            } finally {
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(listener);
                BookAttributeHandler.onLogout(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
            }
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void bookPagesKeepTheirOwnBookAndDuplicateSpellSlot(net.minecraft.gametest.framework.GameTestHelper helper) throws Exception {
            var book = new net.minecraft.world.item.ItemStack(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                    net.minecraft.resources.ResourceLocation.parse("irons_spellbooks:netherite_spell_book")));
            book.setTag(net.minecraft.nbt.TagParser.parseTag("""
                    {"irons_spellbooks:spell_container":{maxSpells:12,mustEquip:1b,spellWheel:1b,
                    data:[{index:0,id:"irons_spellbooks:dragon_breath",level:10},{index:1,id:"irons_spellbooks:dragon_breath",level:10}]},
                    apoth_book_affixes:{"0":{spell_id:"irons_spellbooks:dragon_breath",affix_data:{rarity:"apotheosis:ancient",
                    affixes:{"apotheosis_spells:scroll/spell_modifier/mana_cost":1.0f}}}}}
                    """));
            var plain = book.copy();
            plain.getOrCreateTag().remove(ReforgeCache.BOOK_AFFIXES);
            var slots = io.redspace.ironsspellbooks.api.spells.ISpellContainer.get(book).getActiveSpells();
            helper.assertTrue(slots.size() == 2, "Duplicate spell fixture must preserve both slots");
            for (var source : java.util.List.of(book, plain)) {
                for (var slot : slots) {
                    var returned = SpellCastHooks.withPageContext(source, null, slot, current -> {
                        var context = SpellCastHooks.get();
                        helper.assertTrue(context != null && context.stack() == source && context.spellSlotIndex() == current.index(),
                                "Book page lost its actual book or physical slot");
                        float expected = source == book && slot.index() == 0 ? 0.5f : 1;
                        helper.assertTrue(context.data().mana() == expected && !context.castContext(),
                                "Book page inherited another page's affixes");
                        return current.index();
                    });
                    helper.assertTrue(returned == slot.index() && SpellCastHooks.get() == null, "Page scope leaked after rendering");
                }
            }
            try {
                SpellCastHooks.withPageContext(book, null, slots.get(0), slot -> { throw new IllegalStateException("page"); });
            } catch (IllegalStateException expected) {
                helper.assertTrue(SpellCastHooks.get() == null, "Failed page render leaked its affixes");
            }
            helper.succeed();
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void echoSendsNativeReleaseAnimationWithoutInterruptingCast(net.minecraft.gametest.framework.GameTestHelper helper) {
            var captures = new java.util.ArrayList<EchoPacketCapture>();
            var shots = new java.util.ArrayList<net.minecraft.world.entity.Entity>();
            java.util.function.Consumer<net.minecraftforge.event.entity.EntityJoinLevelEvent> listener = event -> {
                if (event.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile shot
                        && captures.stream().anyMatch(capture -> capture.player == shot.getOwner())) shots.add(shot);
            };
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.LOWEST,
                    false, net.minecraftforge.event.entity.EntityJoinLevelEvent.class, listener);
            Runnable cleanup = () -> {
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(listener);
                captures.forEach(EchoPacketCapture::close);
                shots.forEach(net.minecraft.world.entity.Entity::discard);
            };
            var spells = java.util.List.of(io.redspace.ironsspellbooks.api.registry.SpellRegistry.BLOOD_SLASH_SPELL.get(),
                    io.redspace.ironsspellbooks.api.registry.SpellRegistry.FIREBALL_SPELL.get(),
                    io.redspace.ironsspellbooks.api.registry.SpellRegistry.BLOOD_SLASH_SPELL.get());
            var expected = new java.util.ArrayList<byte[]>();
            try {
                for (int i = 0; i < spells.size(); i++) {
                    var player = net.minecraftforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                            new com.mojang.authlib.GameProfile(UUID.randomUUID(), "[EchoAnimation" + i + "]"));
                    player.setPos(helper.absoluteVec(new net.minecraft.world.phys.Vec3(1, 2, -5)));
                    var capture = new EchoPacketCapture(player);
                    captures.add(capture);
                    var spell = spells.get(i);
                    var animation = i == 1 ? spell.getCastFinishAnimation().getForPlayer().orElseThrow()
                            : spell.getCastStartAnimation().getForPlayer().orElseThrow();
                    SpellEffectHandler.EchoAnimation.send(player, animation);
                    expected.add(capture.packets.get(capture.packets.size() - 1));
                    capture.packets.clear();
                    var magic = io.redspace.ironsspellbooks.api.magic.MagicData.getPlayerMagicData(player);
                    var snapshot = new SpellCastHooks.Snapshot(player.getUUID(), spell.getSpellId(), 1,
                            ReforgeCache.Data.DEF, SpellEffects.ofEcho(1));
                    SpellEffectHandler.afterCast(player, spell, 1, io.redspace.ironsspellbooks.api.spells.CastSource.COMMAND, magic, snapshot);
                    if (i == 2) magic.initiateCast(spells.get(1), 2, 40,
                            io.redspace.ironsspellbooks.api.spells.CastSource.SPELLBOOK, "offhand");
                }
                helper.runAfterDelay(4, () -> {
                    try {
                        for (int i = 0; i < captures.size(); i++) {
                            var capture = captures.get(i);
                            SpellEffectHandler.onPlayerTick(new net.minecraftforge.event.TickEvent.PlayerTickEvent(
                                    net.minecraftforge.event.TickEvent.Phase.END, capture.player));
                            var bytes = expected.get(i);
                            long count = capture.packets.stream().filter(packet -> java.util.Arrays.equals(packet, bytes)).count();
                            helper.assertTrue(count == (i == 2 ? 0 : 1),
                                    "Echo animation packet count incorrect for case " + i + ": " + count);
                            var magic = io.redspace.ironsspellbooks.api.magic.MagicData.getPlayerMagicData(capture.player);
                            helper.assertTrue(magic.isCasting() == (i == 2), "Echo changed casting state");
                            if (i == 2) helper.assertTrue(magic.getCastingSpellId().equals(spells.get(1).getSpellId())
                                    && magic.getCastDurationRemaining() == 40 && magic.getCastingEquipmentSlot().equals("offhand"),
                                    "Echo interrupted the next spell");
                        }
                        helper.succeed();
                    } finally { cleanup.run(); }
                });
            } catch (Throwable failure) { cleanup.run(); throw failure; }
        }

        private static final class EchoPacketCapture implements AutoCloseable {
            private final net.minecraft.server.level.ServerPlayer player;
            private final net.minecraft.server.network.ServerGamePacketListenerImpl original;
            private final java.util.List<byte[]> packets = new java.util.ArrayList<>();

            private EchoPacketCapture(net.minecraft.server.level.ServerPlayer player) {
                this.player = player;
                this.original = player.connection;
                player.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(player.server,
                        new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND) {
                    @Override
                    public void send(net.minecraft.network.protocol.Packet<?> packet) {
                        if (packet instanceof net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket payload) {
                            packets.add(io.netty.buffer.ByteBufUtil.getBytes(payload.getData()));
                        }
                    }
                }, player);
            }

            @Override
            public void close() {
                SpellEffectHandler.onLogout(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
                player.connection = original;
            }
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void delayedEchoDealsSecondHit(net.minecraft.gametest.framework.GameTestHelper helper) {
            var player = net.minecraftforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "[EchoTest]"));
            player.getAttribute(dev.shadowsoffire.attributeslib.api.ALObjects.Attributes.CRIT_CHANCE.get()).setBaseValue(0);
            player.setPos(helper.absoluteVec(new net.minecraft.world.phys.Vec3(1, 2, -5)));
            var magic = io.redspace.ironsspellbooks.api.magic.MagicData.getPlayerMagicData(player);
            magic.setMana(80);
            var spell = io.redspace.ironsspellbooks.api.registry.SpellRegistry.BLOOD_SLASH_SPELL.get();
            var snapshot = new SpellCastHooks.Snapshot(player.getUUID(), spell.getSpellId(), 3,
                    new ReforgeCache.Data(1.5f, 1, 1, 1, 0, 1, 1), SpellEffects.ofEcho(1),
                    java.util.List.of(new SpellCastHooks.AttributeBonus("irons_spellbooks:blood_spell_power", 0.4,
                            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADDITION)));
            var shots = new java.util.ArrayList<io.redspace.ironsspellbooks.entity.spells.blood_slash.BloodSlashProjectile>();
            java.util.function.Consumer<net.minecraftforge.event.entity.EntityJoinLevelEvent> listener = event -> {
                if (event.getEntity() instanceof io.redspace.ironsspellbooks.entity.spells.blood_slash.BloodSlashProjectile shot
                        && shot.getOwner() == player) {
                    shots.add(shot);
                    helper.assertTrue(Math.abs(player.getAttributeValue(io.redspace.ironsspellbooks.api.registry.AttributeRegistry.BLOOD_SPELL_POWER.get()) - 1.4) < 0.0001,
                            "Projectile spawn lost original blood attribute");
                }
            };
            var target = net.minecraft.world.entity.EntityType.IRON_GOLEM.create(helper.getLevel());
            target.setNoAi(true);
            target.setNoGravity(true);
            target.setPos(helper.absoluteVec(new net.minecraft.world.phys.Vec3(1, 2, 1)));
            helper.getLevel().addFreshEntity(target);
            Runnable cleanup = () -> {
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(listener);
                SpellEffectHandler.onLogout(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
                shots.forEach(net.minecraft.world.entity.Entity::discard);
                target.discard();
            };
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.LOWEST,
                    false, net.minecraftforge.event.entity.EntityJoinLevelEvent.class, listener);
            try {
                try (var scope = SpellCastHooks.enter(snapshot, player)) {
                    spell.castSpell(helper.getLevel(), 3, player, io.redspace.ironsspellbooks.api.spells.CastSource.COMMAND, false);
                }
                helper.assertTrue(shots.size() == 1, "Echo must wait before spawning: shots=" + shots.size());
                float before = target.getHealth();
                shots.get(0).setPos(target.position().add(0, 1, 0));
                shots.get(0).setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                shots.get(0).tick();
                float first = target.getHealth();
                helper.assertTrue(first < before, "Original projectile did not damage target");
                float mana = magic.getMana();
                var cooldowns = magic.getPlayerCooldowns().saveNBTData().copy();
                helper.runAfterDelay(4, () -> {
                    try {
                        SpellEffectHandler.onPlayerTick(new net.minecraftforge.event.TickEvent.PlayerTickEvent(
                                net.minecraftforge.event.TickEvent.Phase.END, player));
                        helper.assertTrue(shots.size() == 2, "Delayed echo did not spawn exactly once: " + shots.size());
                        helper.assertTrue(SpellCastHooks.entitySnapshot(shots.get(1)).equals(snapshot.asEcho()), "Echo lost original snapshot");
                        shots.get(1).setPos(target.position().add(0, 1, 0));
                        shots.get(1).setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                        shots.get(1).tick();
                        helper.assertTrue(target.getHealth() < first, "Delayed echo was rejected by hurt immunity");
                        helper.assertTrue(Math.abs((before - first) - (first - target.getHealth())) < 0.001,
                                "Delayed echo lost original damage bonuses");
                        float afterEcho = target.getHealth();
                        shots.get(1).tick();
                        helper.assertTrue(target.getHealth() == afterEcho, "Echo bypassed the projectile's own once-per-target rule");
                        helper.assertTrue(magic.getMana() == mana, "Echo consumed extra mana");
                        helper.assertTrue(magic.getPlayerCooldowns().saveNBTData().equals(cooldowns), "Echo modified cooldowns");
                        helper.runAfterDelay(15, () -> {
                            try {
                                SpellEffectHandler.onPlayerTick(new net.minecraftforge.event.TickEvent.PlayerTickEvent(
                                        net.minecraftforge.event.TickEvent.Phase.END, player));
                                helper.assertTrue(shots.size() == 2, "Echo recursively scheduled another cast");
                                helper.succeed();
                            } finally { cleanup.run(); }
                        });
                    } catch (Throwable failure) { cleanup.run(); throw failure; }
                });
            } catch (Throwable failure) { cleanup.run(); throw failure; }
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void delayedAcupunctureKeepsTargetAndCurrentCast(net.minecraft.gametest.framework.GameTestHelper helper) {
            var player = net.minecraftforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "[EchoTargetTest]"));
            var magic = io.redspace.ironsspellbooks.api.magic.MagicData.getPlayerMagicData(player);
            var spell = io.redspace.ironsspellbooks.api.registry.SpellRegistry.ACUPUNCTURE_SPELL.get();
            var otherSpell = io.redspace.ironsspellbooks.api.registry.SpellRegistry.BLOOD_SLASH_SPELL.get();
            var target = net.minecraft.world.entity.EntityType.IRON_GOLEM.create(helper.getLevel());
            target.setNoAi(true);
            target.setNoGravity(true);
            target.setPos(helper.absoluteVec(new net.minecraft.world.phys.Vec3(1, 2, 1)));
            helper.getLevel().addFreshEntity(target);
            var other = net.minecraft.world.entity.EntityType.IRON_GOLEM.create(helper.getLevel());
            other.setNoAi(true);
            other.setNoGravity(true);
            other.setPos(target.position().add(30, 0, 0));
            helper.getLevel().addFreshEntity(other);
            var snapshot = new SpellCastHooks.Snapshot(player.getUUID(), spell.getSpellId(), 1,
                    ReforgeCache.Data.DEF, SpellEffects.ofEcho(1));
            var shots = new java.util.ArrayList<io.redspace.ironsspellbooks.entity.spells.blood_needle.BloodNeedle>();
            java.util.function.Consumer<net.minecraftforge.event.entity.EntityJoinLevelEvent> listener = event -> {
                if (event.getEntity() instanceof io.redspace.ironsspellbooks.entity.spells.blood_needle.BloodNeedle shot
                        && shot.getOwner() == player) {
                    shots.add(shot);
                    helper.assertTrue(shot.distanceTo(target) < 8, "Acupuncture echo borrowed another cast's target");
                    helper.assertTrue("mainhand".equals(magic.getCastingEquipmentSlot()), "Echo borrowed another cast's equipment slot");
                }
            };
            Runnable cleanup = () -> {
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(listener);
                SpellEffectHandler.onLogout(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
                shots.forEach(net.minecraft.world.entity.Entity::discard);
                target.discard();
                other.discard();
            };
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.LOWEST,
                    false, net.minecraftforge.event.entity.EntityJoinLevelEvent.class, listener);
            try {
                magic.getSyncedData();
                magic.initiateCast(spell, 1, 0, io.redspace.ironsspellbooks.api.spells.CastSource.SPELLBOOK, "mainhand");
                magic.setAdditionalCastData(new io.redspace.ironsspellbooks.capabilities.magic.TargetEntityCastData(target));
                try (var scope = SpellCastHooks.enter(snapshot, player)) {
                    spell.castSpell(helper.getLevel(), 1, player, io.redspace.ironsspellbooks.api.spells.CastSource.COMMAND, false);
                }
                int firstCount = shots.size();
                helper.assertTrue(firstCount > 0, "Original acupuncture did not spawn projectiles");
                magic.resetCastingState();
                magic.initiateCast(otherSpell, 3, 40, io.redspace.ironsspellbooks.api.spells.CastSource.SPELLBOOK, "offhand");
                var currentData = new io.redspace.ironsspellbooks.capabilities.magic.TargetEntityCastData(other);
                magic.setAdditionalCastData(currentData);
                helper.runAfterDelay(4, () -> {
                    try {
                        SpellEffectHandler.onPlayerTick(new net.minecraftforge.event.TickEvent.PlayerTickEvent(
                                net.minecraftforge.event.TickEvent.Phase.END, player));
                        helper.assertTrue(shots.size() == firstCount * 2, "Acupuncture echo lost its original target data: " + shots.size());
                        helper.assertTrue(magic.getAdditionalCastData() == currentData, "Echo replaced current spell's target");
                        helper.assertTrue(magic.isCasting() && magic.getCastingSpellId().equals(otherSpell.getSpellId())
                                        && magic.getCastingSpellLevel() == 3 && magic.getCastDurationRemaining() == 40
                                        && magic.getCastingEquipmentSlot().equals("offhand")
                                        && magic.getSyncedData().getCastingEquipmentSlot().equals("offhand"),
                                "Echo changed another spell's casting state");
                        helper.succeed();
                    } finally { cleanup.run(); }
                });
            } catch (Throwable failure) { cleanup.run(); throw failure; }
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void delayedEchoCancelsInvalidPlayers(net.minecraft.gametest.framework.GameTestHelper helper) {
            var players = new java.util.ArrayList<net.minecraftforge.common.util.FakePlayer>();
            var spell = io.redspace.ironsspellbooks.api.registry.SpellRegistry.BLOOD_SLASH_SPELL.get();
            var shots = new java.util.ArrayList<net.minecraft.world.entity.Entity>();
            java.util.function.Consumer<net.minecraftforge.event.entity.EntityJoinLevelEvent> listener = event -> {
                if (event.getEntity() instanceof io.redspace.ironsspellbooks.entity.spells.blood_slash.BloodSlashProjectile shot
                        && players.contains(shot.getOwner())) shots.add(shot);
            };
            Runnable cleanup = () -> {
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(listener);
                for (var player : players) SpellEffectHandler.onLogout(
                        new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
                shots.forEach(net.minecraft.world.entity.Entity::discard);
            };
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.LOWEST,
                    false, net.minecraftforge.event.entity.EntityJoinLevelEvent.class, listener);
            try {
                for (int i = 0; i < 4; i++) {
                    var player = net.minecraftforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                            new com.mojang.authlib.GameProfile(UUID.randomUUID(), "[EchoCancel" + i + "]"));
                    players.add(player);
                    var magic = io.redspace.ironsspellbooks.api.magic.MagicData.getPlayerMagicData(player);
                    if (i == 3) magic.setAdditionalCastData(() -> {});
                    var snapshot = new SpellCastHooks.Snapshot(player.getUUID(), spell.getSpellId(), 1,
                            ReforgeCache.Data.DEF, SpellEffects.ofEcho(1));
                    SpellEffectHandler.afterCast(player, spell, 1, io.redspace.ironsspellbooks.api.spells.CastSource.COMMAND, magic, snapshot);
                    if (i == 0) SpellEffectHandler.onLogout(
                            new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
                    if (i == 1) SpellEffectHandler.onChangedDimension(
                            new net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent(player,
                                    net.minecraft.world.level.Level.OVERWORLD, net.minecraft.world.level.Level.NETHER));
                    if (i == 2) {
                        player.setHealth(0);
                        SpellEffectHandler.onPlayerTick(new net.minecraftforge.event.TickEvent.PlayerTickEvent(
                                net.minecraftforge.event.TickEvent.Phase.END, player));
                        player.setHealth(20);
                    }
                }
                helper.runAfterDelay(4, () -> {
                    try {
                        for (var player : players) SpellEffectHandler.onPlayerTick(new net.minecraftforge.event.TickEvent.PlayerTickEvent(
                                net.minecraftforge.event.TickEvent.Phase.END, player));
                        helper.assertTrue(shots.isEmpty(), "Cancelled or unsupported echo still spawned projectiles");
                        helper.succeed();
                    } finally { cleanup.run(); }
                });
            } catch (Throwable failure) { cleanup.run(); throw failure; }
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void delayedAreaEchoKeepsRadiusAndTarget(net.minecraft.gametest.framework.GameTestHelper helper) {
            var player = net.minecraftforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "[EchoAreaTest]"));
            var magic = io.redspace.ironsspellbooks.api.magic.MagicData.getPlayerMagicData(player);
            magic.getSyncedData();
            var scorch = io.redspace.ironsspellbooks.api.registry.SpellRegistry.SCORCH_SPELL.get();
            var slow = io.redspace.ironsspellbooks.api.registry.SpellRegistry.SLOW_SPELL.get();
            var center = helper.absoluteVec(new net.minecraft.world.phys.Vec3(1, 2, 1));
            var area = io.redspace.ironsspellbooks.entity.spells.target_area.TargetedAreaEntity.createTargetAreaEntity(
                    helper.getLevel(), center, 4.5f, 0);
            var target = net.minecraft.world.entity.EntityType.IRON_GOLEM.create(helper.getLevel());
            target.setNoAi(true);
            target.setNoGravity(true);
            target.setPos(center.add(20, 0, 0));
            helper.getLevel().addFreshEntity(target);
            var targetedArea = io.redspace.ironsspellbooks.entity.spells.target_area.TargetedAreaEntity.createTargetAreaEntity(
                    helper.getLevel(), target.position(), 3, 0, target);
            var fires = new java.util.ArrayList<io.redspace.ironsspellbooks.entity.spells.magma_ball.FireField>();
            java.util.function.Consumer<net.minecraftforge.event.entity.EntityJoinLevelEvent> listener = event -> {
                if (event.getEntity() instanceof io.redspace.ironsspellbooks.entity.spells.magma_ball.FireField fire
                        && fire.getOwner() == player) fires.add(fire);
            };
            Runnable cleanup = () -> {
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(listener);
                SpellEffectHandler.onLogout(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
                fires.forEach(net.minecraft.world.entity.Entity::discard);
                area.discard();
                targetedArea.discard();
                target.discard();
            };
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.LOWEST,
                    false, net.minecraftforge.event.entity.EntityJoinLevelEvent.class, listener);
            try {
                magic.setAdditionalCastData(new io.redspace.ironsspellbooks.spells.TargetAreaCastData(center, area));
                var snapshot = new SpellCastHooks.Snapshot(player.getUUID(), scorch.getSpellId(), 1,
                        ReforgeCache.Data.DEF, SpellEffects.ofEcho(1));
                try (var scope = SpellCastHooks.enter(snapshot, player)) {
                    scorch.castSpell(helper.getLevel(), 1, player, io.redspace.ironsspellbooks.api.spells.CastSource.COMMAND, false);
                }
                helper.assertTrue(fires.size() == 1, "Original scorch did not create a fire field");
                magic.resetCastingState();
                area.setRadius(1);
                magic.setAdditionalCastData(new io.redspace.ironsspellbooks.spells.TargetedTargetAreaCastData(target, targetedArea));
                var slowSnapshot = new SpellCastHooks.Snapshot(player.getUUID(), slow.getSpellId(), 1,
                        ReforgeCache.Data.DEF, SpellEffects.ofEcho(1));
                try (var scope = SpellCastHooks.enter(slowSnapshot, player)) {
                    slow.castSpell(helper.getLevel(), 1, player, io.redspace.ironsspellbooks.api.spells.CastSource.COMMAND, false);
                }
                helper.assertTrue(target.hasEffect(io.redspace.ironsspellbooks.registries.MobEffectRegistry.SLOWED.get()),
                        "Original slow did not affect target");
                target.removeEffect(io.redspace.ironsspellbooks.registries.MobEffectRegistry.SLOWED.get());
                magic.resetCastingState();
                helper.runAfterDelay(4, () -> {
                    try {
                        SpellEffectHandler.onPlayerTick(new net.minecraftforge.event.TickEvent.PlayerTickEvent(
                                net.minecraftforge.event.TickEvent.Phase.END, player));
                        helper.assertTrue(fires.size() == 2, "Area echo lost its cast data");
                        helper.assertTrue(fires.get(1).position().distanceTo(center) < 0.001
                                        && Math.abs(fires.get(1).getRadius() - 4.5f) < 0.001,
                                "Area echo lost original center or radius");
                        helper.assertTrue(target.hasEffect(io.redspace.ironsspellbooks.registries.MobEffectRegistry.SLOWED.get()),
                                "Targeted-area echo lost original target");
                        helper.assertTrue(magic.getAdditionalCastData() == null && !magic.isCasting(), "Area echo left casting state behind");
                        helper.assertTrue(!com.example.apotheosis_spells.affix.SpellAffix.supportsEcho(
                                io.redspace.ironsspellbooks.api.registry.SpellRegistry.SACRIFICE_SPELL.get()),
                                "Sacrifice can still roll an echo that cannot reuse its consumed target");
                        helper.succeed();
                    } finally { cleanup.run(); }
                });
            } catch (Throwable failure) { cleanup.run(); throw failure; }
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void echoBypassesOnlyHurtCooldown(net.minecraft.gametest.framework.GameTestHelper helper) {
            var player = net.minecraftforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "[EchoImmunityTest]"));
            player.getAttribute(dev.shadowsoffire.attributeslib.api.ALObjects.Attributes.CRIT_CHANCE.get()).setBaseValue(0);
            var spell = io.redspace.ironsspellbooks.api.registry.SpellRegistry.BLOOD_SLASH_SPELL.get();
            var snapshot = new SpellCastHooks.Snapshot(player.getUUID(), spell.getSpellId(), 1,
                    ReforgeCache.Data.DEF, SpellEffects.ofEcho(1).merge(SpellEffects.ofManaLeech(0.5f)));
            var original = new io.redspace.ironsspellbooks.entity.spells.blood_slash.BloodSlashProjectile(helper.getLevel(), player);
            var echo = new io.redspace.ironsspellbooks.entity.spells.blood_slash.BloodSlashProjectile(helper.getLevel(), player);
            SpellCastHooks.attach(original, snapshot);
            SpellCastHooks.attach(echo, SpellCastHooks.Snapshot.read(snapshot.asEcho().write()));
            var originalSource = spell.getDamageSource(original, player);
            var echoSource = spell.getDamageSource(echo, player);
            helper.assertTrue(!originalSource.is(net.minecraft.tags.DamageTypeTags.BYPASSES_COOLDOWN), "Original cast bypasses hurt cooldown");
            helper.assertTrue(echoSource.is(net.minecraft.tags.DamageTypeTags.BYPASSES_COOLDOWN), "Persisted echo lost hurt cooldown bypass");
            for (var tag : java.util.List.of(net.minecraft.tags.DamageTypeTags.BYPASSES_ARMOR,
                    net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY,
                    net.minecraft.tags.DamageTypeTags.BYPASSES_RESISTANCE,
                    net.minecraft.tags.DamageTypeTags.BYPASSES_SHIELD)) {
                helper.assertTrue(echoSource.is(tag) == originalSource.is(tag), "Echo changed an unrelated damage tag: " + tag);
            }
            var other = net.minecraftforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "[EchoOtherTest]"));
            helper.assertTrue(!spell.getDamageSource(echo, other).is(net.minecraft.tags.DamageTypeTags.BYPASSES_COOLDOWN),
                    "Echo bypass crossed caster ownership");
            try (var scope = SpellCastHooks.enter(snapshot.asEcho(), player)) {
                helper.assertTrue(!originalSource.is(net.minecraft.tags.DamageTypeTags.BYPASSES_COOLDOWN),
                        "Original projectile borrowed echo context");
                helper.assertTrue(spell.getDamageSource(player).is(net.minecraft.tags.DamageTypeTags.BYPASSES_COOLDOWN),
                        "Direct echo damage lost its context");
            }
            var target = net.minecraft.world.entity.EntityType.IRON_GOLEM.create(helper.getLevel());
            var magic = io.redspace.ironsspellbooks.api.magic.MagicData.getPlayerMagicData(player);
            magic.setMana(0);
            helper.assertTrue(io.redspace.ironsspellbooks.damage.DamageSources.applyDamage(target, 8, originalSource), "Original damage failed");
            helper.assertTrue(!io.redspace.ironsspellbooks.damage.DamageSources.applyDamage(target, 8, originalSource), "Original repeats bypassed immunity");
            for (int i = 0; i < 2; i++) {
                float health = target.getHealth();
                helper.assertTrue(io.redspace.ironsspellbooks.damage.DamageSources.applyDamage(target, 8, echoSource), "Echo damage was rejected");
                helper.assertTrue(Math.abs(health - target.getHealth() - 8) < 0.001, "Echo damage was reduced to the cooldown difference");
            }
            helper.assertTrue(target.invulnerableTime > 10, "Echo cleared the target's global hurt cooldown");
            helper.assertTrue(!io.redspace.ironsspellbooks.damage.DamageSources.applyDamage(target, 8, originalSource), "Echo granted immunity bypass to original damage");
            target.getAttribute(io.redspace.ironsspellbooks.api.registry.AttributeRegistry.SPELL_RESIST.get()).setBaseValue(1.5);
            float beforeResist = target.getHealth();
            helper.assertTrue(io.redspace.ironsspellbooks.damage.DamageSources.applyDamage(target, 8, echoSource), "Resisted echo damage failed");
            helper.assertTrue(beforeResist - target.getHealth() > 0 && beforeResist - target.getHealth() < 8,
                    "Echo bypassed native magic resistance");
            float before = target.getHealth();
            float mana = magic.getMana();
            target.setInvulnerable(true);
            helper.assertTrue(!io.redspace.ironsspellbooks.damage.DamageSources.applyDamage(target, 8, echoSource), "Echo bypassed actual invulnerability");
            target.setInvulnerable(false);
            java.util.function.Consumer<net.minecraftforge.event.entity.living.LivingDamageEvent> cancel = event -> {
                if (event.getEntity() == target) event.setCanceled(true);
            };
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.LOWEST,
                    false, net.minecraftforge.event.entity.living.LivingDamageEvent.class, cancel);
            try {
                io.redspace.ironsspellbooks.damage.DamageSources.applyDamage(target, 8, echoSource);
                helper.assertTrue(target.getHealth() == before && magic.getMana() == mana,
                        "Invulnerable or cancelled echo caused damage or mana leech");
            } finally {
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(cancel);
                SpellEffectHandler.onLogout(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
                original.discard();
                echo.discard();
                target.discard();
            }
            helper.succeed();
        }

        public static class FixedHealthCow extends net.minecraft.world.entity.animal.Cow {
            private boolean fixed;

            public FixedHealthCow(net.minecraft.world.level.Level level) {
                super(net.minecraft.world.entity.EntityType.COW, level);
                fixed = true;
            }

            @Override
            public void setHealth(float health) {
                if (!fixed || health >= getHealth()) super.setHealth(health);
            }
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void manaLeechUsesSettledDamageInsteadOfHealthLoss(net.minecraft.gametest.framework.GameTestHelper helper) {
            var player = net.minecraftforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "[ManaDamageTest]"));
            player.getAttribute(dev.shadowsoffire.attributeslib.api.ALObjects.Attributes.CRIT_CHANCE.get()).setBaseValue(0);
            var magic = io.redspace.ironsspellbooks.api.magic.MagicData.getPlayerMagicData(player);
            var spell = io.redspace.ironsspellbooks.api.registry.SpellRegistry.BLOOD_NEEDLES_SPELL.get();
            var source = spell.getDamageSource(player);
            var snapshot = new SpellCastHooks.Snapshot(player.getUUID(), spell.getSpellId(), 1,
                    ReforgeCache.Data.DEF, SpellEffects.ofManaLeech(0.5f));
            var target = new FixedHealthCow(helper.getLevel());
            float health = target.getHealth();
            magic.setMana(0);
            try (var scope = SpellCastHooks.enter(snapshot, player)) {
                helper.assertTrue(io.redspace.ironsspellbooks.damage.DamageSources.applyDamage(target, 8, source),
                        "Fixed-health target rejected damage");
            }
            helper.assertTrue(target.getHealth() == health, "Test target did not retain health");
            helper.assertTrue(Math.abs(magic.getMana() - 4) < 0.0001, "Damage 8 with unchanged health must restore 4 mana, got " + magic.getMana());
            var normal = net.minecraft.world.entity.EntityType.COW.create(helper.getLevel());
            normal.setHealth(1);
            magic.setMana(0);
            try (var scope = SpellCastHooks.enter(snapshot, player)) {
                io.redspace.ironsspellbooks.damage.DamageSources.applyDamage(normal, 8, source);
            }
            helper.assertTrue(Math.abs(magic.getMana() - 4) < 0.0001, "Overkill damage was capped to remaining health");
            target.invulnerableTime = 0;
            target.setInvulnerable(true);
            magic.setMana(0);
            try (var scope = SpellCastHooks.enter(snapshot, player)) {
                helper.assertTrue(!io.redspace.ironsspellbooks.damage.DamageSources.applyDamage(target, 8, source),
                        "Invulnerable target accepted damage");
            }
            helper.assertTrue(magic.getMana() == 0, "Rejected damage restored mana");
            target.setInvulnerable(false);
            float[] adjusted = {2};
            java.util.function.Consumer<net.minecraftforge.event.entity.living.LivingDamageEvent> modifier = event -> {
                if (event.getEntity() != target) return;
                if (adjusted[0] < 0) event.setCanceled(true);
                else event.setAmount(adjusted[0]);
            };
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.LOWEST,
                    false, net.minecraftforge.event.entity.living.LivingDamageEvent.class, modifier);
            try {
                for (float finalDamage : new float[]{2, 0, -1}) {
                    adjusted[0] = finalDamage;
                    target.invulnerableTime = 0;
                    magic.setMana(0);
                    try (var scope = SpellCastHooks.enter(snapshot, player)) {
                        io.redspace.ironsspellbooks.damage.DamageSources.applyDamage(target, 8, source);
                    }
                    helper.assertTrue(Math.abs(magic.getMana() - Math.max(0, finalDamage) * 0.5f) < 0.0001,
                            "Mana leech ignored final damage modification/cancellation: " + finalDamage + " mana=" + magic.getMana());
                }
            } finally {
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(modifier);
            }
            helper.succeed();
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void sharedProjectileRetainsOriginatingAffixes(net.minecraft.gametest.framework.GameTestHelper helper) {
            var player = net.minecraftforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "[ProjectileTest]"));
            var needle = new io.redspace.ironsspellbooks.entity.spells.blood_needle.BloodNeedle(helper.getLevel(), player);
            var spell = io.redspace.ironsspellbooks.api.registry.SpellRegistry.BLOOD_NEEDLES_SPELL.get();
            var source = spell.getDamageSource(needle, player);
            var snapshot = new SpellCastHooks.Snapshot(player.getUUID(), "irons_spellbooks:acupuncture", 6,
                    new ReforgeCache.Data(1.5f, 0.5f, 0.5f, 1, 5, 1, 1),
                    SpellEffects.ofExecute(1, 50).merge(SpellEffects.ofManaLeech(0.5f)));
            try (var scope = SpellCastHooks.enter(snapshot, player)) {
                helper.assertTrue(helper.getLevel().addFreshEntity(needle), "Could not spawn test projectile");
            }
            helper.assertTrue(SpellCastHooks.entitySnapshot(needle) == snapshot, "Projectile spawn did not inherit cast snapshot");
            helper.assertTrue(SpellCastHooks.forDamage(source) == snapshot,
                    "Shared blood needle damage lost acupuncture affixes");
            var target = net.minecraft.world.entity.EntityType.COW.create(helper.getLevel());
            target.setHealth(target.getMaxHealth() * 0.4f);
            float healthBefore = target.getHealth();
            var magic = io.redspace.ironsspellbooks.api.magic.MagicData.getPlayerMagicData(player);
            magic.setMana(0);
            boolean applied = io.redspace.ironsspellbooks.damage.DamageSources.applyDamage(target, 1, source);
            helper.assertTrue(applied && healthBefore - target.getHealth() > 1, "Shared projectile execute bonus did not apply");
            helper.assertTrue(magic.getMana() > 0, "Shared projectile mana leech did not apply");
            var unrelated = new io.redspace.ironsspellbooks.entity.spells.blood_needle.BloodNeedle(helper.getLevel(), player);
            try (var scope = SpellCastHooks.enter(snapshot, player)) {
                helper.assertTrue(SpellCastHooks.forDamage(spell.getDamageSource(unrelated, player)) == null,
                        "Untracked projectile inherited another spell's context");
            }
            var otherPlayer = net.minecraftforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "[OtherCaster]"));
            helper.assertTrue(SpellCastHooks.forDamage(spell.getDamageSource(needle, otherPlayer)) == null,
                    "Projectile affixes crossed caster ownership");
            needle.discard();
            helper.succeed();
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 100)
        public static void inscribedBookAppliesAffixesAndAttributes(net.minecraft.gametest.framework.GameTestHelper helper) throws Exception {
            var player = net.minecraftforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "[SpellAffixTest]"));
            var book = new net.minecraft.world.item.ItemStack(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                    net.minecraft.resources.ResourceLocation.tryParse("irons_spellbooks:netherite_spell_book")));
            book.setTag(net.minecraft.nbt.TagParser.parseTag("""
                    {"irons_spellbooks:spell_container":{maxSpells:12,mustEquip:1b,spellWheel:1b,
                    data:[{index:0,id:"irons_spellbooks:acupuncture",level:1},{index:1,id:"irons_spellbooks:blood_slash",level:1}]},
                    apoth_book_affixes:{"0":{spell_id:"irons_spellbooks:acupuncture",affix_data:{rarity:"apotheosis:ancient",
                    affixes:{"apotheosis_spells:scroll/spell_modifier/mana_cost":1.0f,
                    "apotheosis_spells:scroll/spell_modifier/cooldown":1.0f,
                    "apotheosis_spells:scroll/spell_modifier/spell_level":1.0f,
                    "apotheosis_spells:scroll/spell_modifier/spell_power":1.0f,
                    "apotheosis_spells:scroll/attribute/blood_spell_power":1.0f}}}}}
                    """));
            io.redspace.ironsspellbooks.api.util.Utils.setPlayerSpellbookStack(player, book);
            var magic = io.redspace.ironsspellbooks.api.magic.MagicData.getPlayerMagicData(player);
            magic.getSyncedData().setSpellSelection(new io.redspace.ironsspellbooks.gui.overlays.SpellSelection("spellbook", 0));
            var manager = new io.redspace.ironsspellbooks.api.magic.SpellSelectionManager(player);
            helper.assertTrue(manager.getSelection() != null, "No selected spell: Curios=" +
                    top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve().map(x -> x.getCurios().keySet()));
            var context = SpellCastHooks.resolveSelection(player, manager.getSelection());
            helper.assertTrue(context != null, "Selected book could not be resolved");
            helper.assertTrue(context.data().mana() < 1 && context.data().cd() < 1
                    && context.data().lvl() > 0 && context.data().dmg() > 1, "Affixes inactive: " + context.data());
            var attribute = net.minecraftforge.registries.ForgeRegistries.ATTRIBUTES.getValue(
                    net.minecraft.resources.ResourceLocation.tryParse("irons_spellbooks:blood_spell_power"));
            double before = player.getAttributeValue(attribute);
            BookAttributeHandler.refresh(player);
            helper.assertTrue(player.getAttributeValue(attribute) > before, "Book attribute inactive: before="
                    + before + ", after=" + player.getAttributeValue(attribute));
            var spell = context.spellData().getSpell();
            int baseMana = spell.getManaCost(1);
            float basePower = spell.getSpellPower(1, player);
            int baseCooldown = io.redspace.ironsspellbooks.capabilities.magic.MagicManager.getEffectiveSpellCooldown(
                    spell, player, io.redspace.ironsspellbooks.api.spells.CastSource.SPELLBOOK);
            try (var scope = SpellCastHooks.enter(SpellCastHooks.capture(player, spell, 1, context), player)) {
                helper.assertTrue(spell.getManaCost(1) < baseMana, "Mana calculation hook did not apply");
                helper.assertTrue(spell.getSpellPower(1, player) > basePower, "Spell power calculation hook did not apply");
                helper.assertTrue(io.redspace.ironsspellbooks.capabilities.magic.MagicManager.getEffectiveSpellCooldown(
                        spell, player, io.redspace.ironsspellbooks.api.spells.CastSource.SPELLBOOK) < baseCooldown,
                        "Cooldown calculation hook did not apply");
                spell.getEffectiveCastTime(1, player);
            }
            magic.getSyncedData().setSpellSelection(new io.redspace.ironsspellbooks.gui.overlays.SpellSelection("spellbook", 1));
            BookAttributeHandler.refresh(player);
            helper.assertTrue(Math.abs(player.getAttributeValue(attribute) - before) < 0.00001,
                    "Book attribute was not removed after selecting another spell");
            var bookSnapshot = SpellCastHooks.capture(player, spell, 1, context);
            var persisted = SpellCastHooks.Snapshot.read(bookSnapshot.write());
            var otherSelection = new io.redspace.ironsspellbooks.api.magic.SpellSelectionManager(player).getSelection();
            var otherContext = SpellCastHooks.resolveSelection(player, otherSelection);
            var otherSnapshot = SpellCastHooks.capture(player, otherContext.spellData().getSpell(), 1, otherContext);
            try (var scope = SpellCastHooks.enter(persisted, player)) {
                helper.assertTrue(player.getAttributeValue(attribute) > before,
                        "Casting acupuncture while blood slash is selected lost its own attributes");
                helper.assertTrue(spell.getSpellPower(1, player) > basePower,
                        "Cross-selection cast did not include its own attribute and power affixes");
                double[] observed = {Double.NaN};
                java.util.function.Consumer<io.redspace.ironsspellbooks.api.events.SpellOnCastEvent> listener = event -> {
                    if (event.getEntity() == player && event.getSpellId().equals(spell.getSpellId())) {
                        observed[0] = player.getAttributeValue(attribute);
                    }
                };
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                        net.minecraftforge.eventbus.api.EventPriority.LOWEST, false,
                        io.redspace.ironsspellbooks.api.events.SpellOnCastEvent.class, listener);
                try {
                    spell.castSpell(helper.getLevel(), 1, player,
                            io.redspace.ironsspellbooks.api.spells.CastSource.COMMAND, false);
                } finally {
                    net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(listener);
                }
                helper.assertTrue(observed[0] > before,
                        "Direct castSpell lost the valid surrounding attribute snapshot");
                BookAttributeHandler.refresh(player);
                helper.assertTrue(player.getAttributeValue(attribute) > before, "Tick refresh replaced active cast attributes");
                try {
                    try (var nested = SpellCastHooks.enter(otherSnapshot, player)) {
                        helper.assertTrue(Math.abs(player.getAttributeValue(attribute) - before) < 0.00001,
                                "Nested other spell borrowed acupuncture attributes");
                        throw new IllegalStateException("test nested cast restoration");
                    }
                } catch (IllegalStateException expected) {
                    helper.assertTrue(player.getAttributeValue(attribute) > before,
                            "Nested cast did not restore outer attributes after exception");
                }
            }
            helper.assertTrue(Math.abs(player.getAttributeValue(attribute) - before) < 0.00001,
                    "Cast attributes leaked into the selected spell after scope closed");
            magic.getSyncedData().setSpellSelection(new io.redspace.ironsspellbooks.gui.overlays.SpellSelection("spellbook", 0));
            BookAttributeHandler.refresh(player);
            helper.assertTrue(player.getAttributeValue(attribute) > before, "Book attribute was not restored on reselection");
            int dumped = helper.getLevel().getServer().getCommands().performPrefixedCommand(
                    player.createCommandSourceStack().withPermission(4), "apothspells_debug dump");
            helper.assertTrue(dumped == 1, "Diagnostic dump command failed");
            helper.succeed();
        }
    }

    @Test
    void nestedDisplayScopeRestoresOuterContextAfterException() {
        var outer = new SpellCastHooks.Context(null, null, 0, 1, null, null);
        var inner = new SpellCastHooks.Context(null, null, 1, 2, null, null);
        try (var ignored = SpellCastHooks.enter(outer)) {
            assertThrows(IllegalStateException.class, () -> {
                try (var nested = SpellCastHooks.enter(inner)) {
                    assertSame(inner, SpellCastHooks.get());
                    throw new IllegalStateException();
                }
            });
            assertSame(outer, SpellCastHooks.get());
        }
        assertNull(SpellCastHooks.get());
    }

    @Test
    void cancellationCannotCompleteUnsuccessfulCast() {
        var session = new SpellCastHooks.CastSession(null);
        assertFalse(session.complete(false));
        session.markSuccessful();
        assertFalse(session.complete(true));
    }

    @Test
    void successfulCastCompletesOnlyOnce() {
        var session = new SpellCastHooks.CastSession(null);
        session.markSuccessful();
        assertTrue(session.complete(false));
        assertFalse(session.complete(false));
    }

    @Test
    void cancellingAfterAContinuousPulseDoesNotGrantCompletionEffects() {
        var session = new SpellCastHooks.CastSession(null);
        session.markSuccessful();
        assertFalse(session.complete(true));
        assertFalse(session.complete(false));
    }

    @Test
    void persistentSnapshotPreservesOriginalCasterAndEffects() {
        var snapshot = new SpellCastHooks.Snapshot(UUID.randomUUID(), "irons_spellbooks:fireball", 3,
                new ReforgeCache.Data(1.2f, 0.8f, 0.9f, 1, 2, 1.5f, 1),
                SpellEffects.ofExecute(0.3f, 50).merge(SpellEffects.ofPostcast(2, 100, "minecraft:speed")),
                java.util.List.of(new SpellCastHooks.AttributeBonus("irons_spellbooks:fire_spell_power", 0.4,
                        net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADDITION)));
        assertEquals(snapshot, SpellCastHooks.Snapshot.read(snapshot.write()));
        assertEquals(snapshot.asEcho(), SpellCastHooks.Snapshot.read(snapshot.asEcho().write()));
        assertFalse(SpellCastHooks.Snapshot.read(snapshot.write()).echo());
        assertNull(SpellCastHooks.Snapshot.read(new CompoundTag()));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.attributes().clear());
        var legacy = snapshot.write();
        legacy.remove("attributes");
        assertTrue(SpellCastHooks.Snapshot.read(legacy).attributes().isEmpty());
    }
}
