package com.example.apotheosis_spells.handler;

import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.api.SpellEffects;
import io.netty.buffer.Unpooled;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.gui.overlays.SpellSelection;
import io.redspace.ironsspellbooks.spells.blood.RayOfSiphoningSpell;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder("apotheosis_spells")
@PrefixGameTestTemplate(false)
public class SiphoningBeamGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void startedRangeSurvivesSelectionChangesAndUsesNativeEntityMetadata(GameTestHelper helper) throws Exception {
        var caster = player(helper, "[SiphoningRange]");
        var receiver = player(helper, "[SiphoningReceiver]");
        var other = player(helper, "[OtherSiphoningRange]");
        var spell = SpellRegistry.getSpell("irons_spellbooks:ray_of_siphoning");
        var magic = MagicData.getPlayerMagicData(caster);
        var otherMagic = MagicData.getPlayerMagicData(other);
        magic.getSyncedData();
        otherMagic.getSyncedData();
        float original = RayOfSiphoningSpell.getRange(0);
        helper.assertTrue(near(renderedRange(caster, original), original), "An idle player did not retain the native range");
        try {
            try (var scope = SpellCastHooks.enter(snapshot(caster, 1.3f), caster)) {
                magic.initiateCast(spell, 1, 40, CastSource.SPELLBOOK, "spellbook");
                helper.assertTrue(near(RayOfSiphoningSpell.getRange(1), original * 1.3f),
                        "The real casting snapshot did not scale its range");
            }
            helper.assertTrue(near(renderedRange(caster, original), original * 1.3f),
                    "The beam did not retain the range captured by native cast startup");
            caster.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.BOOK));
            magic.getSyncedData().setSpellSelection(new SpellSelection("offhand", 0));
            BookAttributeHandler.refresh(caster);
            try (var scope = SpellCastHooks.enter(snapshot(other, 2), other)) {
                otherMagic.initiateCast(spell, 1, 40, CastSource.COMMAND, "");
                helper.assertTrue(near(renderedRange(caster, original), original * 1.3f)
                                && near(renderedRange(other, original), original * 2),
                        "Equipment, selection or another caster changed the started beam's range");
            }
            var values = caster.getEntityData().getNonDefaultValues();
            helper.assertTrue(values != null && values.stream().anyMatch(value -> value.value() instanceof Float amount
                            && near(amount, original * 1.3f)),
                    "The captured range was missing from normal entity metadata");
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                new ClientboundSetEntityDataPacket(caster.getId(), values).write(buffer);
                var packet = new ClientboundSetEntityDataPacket(buffer);
                helper.assertTrue(packet.id() == caster.getId(), "Metadata changed the caster identity");
                receiver.getEntityData().assignValues(packet.packedItems());
                helper.assertTrue(near(renderedRange(receiver, original), original * 1.3f),
                        "A tracking player's received metadata did not reproduce the caster's range");
            } finally {
                buffer.release();
            }
            magic.resetCastingState();
            helper.assertTrue(near(renderedRange(caster, original), original), "Ending a cast retained its range");
            var cleared = caster.getEntityData().packDirty();
            helper.assertTrue(cleared != null, "Ending a cast did not schedule metadata synchronization");
            receiver.getEntityData().assignValues(cleared);
            helper.assertTrue(near(renderedRange(receiver, original), original), "Tracking clients retained the ended range");
            try (var scope = SpellCastHooks.enter(snapshot(caster, 2), caster)) {
                magic.initiateCast(spell, 1, 40, CastSource.COMMAND, "");
            }
            helper.assertTrue(near(renderedRange(caster, original), original * 2), "A new cast retained the previous range");
            magic.getSyncedData().setIsCasting(true, SpellRegistry.FIREBALL_SPELL.get().getSpellId(), 1, "");
            helper.assertTrue(near(renderedRange(caster, original), original), "Another spell retained the siphoning range");
        } finally {
            magic.resetCastingState();
            otherMagic.resetCastingState();
            BookAttributeHandler.onLogout(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(caster));
            BookAttributeHandler.onLogout(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(other));
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nonPlayersKeepTheirNativeRange(GameTestHelper helper) throws Exception {
        var entity = net.minecraft.world.entity.EntityType.IRON_GOLEM.create(helper.getLevel());
        helper.assertTrue(near(renderedRange(entity, 19.5f), 19.5f), "A non-player beam replaced its native range");
        helper.succeed();
    }

    private static SpellCastHooks.Snapshot snapshot(ServerPlayer player, float multiplier) {
        return new SpellCastHooks.Snapshot(player.getUUID(), "irons_spellbooks:ray_of_siphoning", 1,
                new ReforgeCache.Data(1, 1, 1, 1, 0, multiplier, 1), SpellEffects.NONE);
    }

    private static ServerPlayer player(GameTestHelper helper, String name) {
        return FakePlayerFactory.get(helper.getLevel(), new com.mojang.authlib.GameProfile(UUID.randomUUID(), name));
    }

    private static float renderedRange(LivingEntity entity, float original) throws Exception {
        try {
            var type = Class.forName("com.example.apotheosis_spells.handler.SiphoningBeamRange");
            return (float) type.getMethod("renderedRange", LivingEntity.class, float.class).invoke(null, entity, original);
        } catch (ClassNotFoundException baseline) {
            return original;
        }
    }

    private static boolean near(float actual, float expected) { return Math.abs(actual - expected) < 0.00001; }
}
