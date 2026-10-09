package com.example.apotheosis_spells.handler;

import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.api.SpellEffects;
import com.example.apotheosis_spells.handler.SpellCastHooks;
import com.mojang.authlib.GameProfile;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.capabilities.magic.TargetEntityCastData;
import io.redspace.ironsspellbooks.entity.spells.AoeEntity;
import io.redspace.ironsspellbooks.entity.spells.HealingAoe;
import io.redspace.ironsspellbooks.entity.spells.black_hole.BlackHole;
import io.redspace.ironsspellbooks.entity.spells.magma_ball.FireField;
import io.redspace.ironsspellbooks.entity.spells.poison_cloud.PoisonSplash;
import io.redspace.ironsspellbooks.entity.spells.root.RootEntity;
import io.redspace.ironsspellbooks.spells.TargetAreaCastData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder("apotheosis_spells")
@PrefixGameTestTemplate(false)
public final class SpellScalingGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void rootDurationMatchesPreviewAndBinding(GameTestHelper helper) {
        var player = player(helper, "RootDuration");
        var spell = (io.redspace.ironsspellbooks.spells.nature.RootSpell) SpellRegistry.ROOT_SPELL.get();
        int original = spell.getDuration(1, player);
        var cleanup = new ArrayList<Entity>();
        try {
            for (float multiplier : new float[]{1, 1.5f}) {
                var data = scaling(1, multiplier);
                int expected = Math.round(original * multiplier);
                assertInfo(helper, spell, player, data, "ui.irons_spellbooks.effect_length", Utils.timeFromTicks(expected, 1));
                var target = target(helper, cleanup, helper.absoluteVec(new Vec3(1, 2, 1)));
                var magic = MagicData.getPlayerMagicData(player);
                magic.setAdditionalCastData(new TargetEntityCastData(target));
                RootEntity binding = capture(helper, RootEntity.class, cleanup, () -> {
                    try (var scope = SpellCastHooks.enter(snapshot(player, spell, data), player)) {
                        spell.onCast(helper.getLevel(), 1, player, CastSource.COMMAND, magic);
                    }
                });
                var saved = new CompoundTag();
                binding.addAdditionalSaveData(saved);
                helper.assertTrue(saved.getInt("Duration") == expected,
                        "Root binding ignored duration or applied it twice: " + saved.getInt("Duration") + " expected=" + expected);
                target.stopRiding();
                binding.discard();
                target.discard();
                magic.setAdditionalCastData(null);
            }
            helper.succeed();
        } finally {
            cleanup.forEach(Entity::discard);
            MagicData.getPlayerMagicData(player).setAdditionalCastData(null);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void scorchRadiusMatchesAimInitialHitAndFireField(GameTestHelper helper) {
        var player = player(helper, "ScorchRadius");
        player.setXRot(90);
        var spell = SpellRegistry.SCORCH_SPELL.get();
        var magic = MagicData.getPlayerMagicData(player);
        var cleanup = new ArrayList<Entity>();
        var testOrigin = helper.absolutePos(new BlockPos(1, 256, 1));
        var elevatedFloor = new BlockPos((testOrigin.getX() & ~15) + 8, testOrigin.getY(),
                (testOrigin.getZ() & ~15) + 8);
        var originalFloor = helper.getLevel().getBlockState(elevatedFloor);
        try {
            helper.getLevel().setBlockAndUpdate(elevatedFloor, Blocks.STONE.defaultBlockState());
            player.setPos(Vec3.atBottomCenterOf(elevatedFloor.above(3)));
            for (float multiplier : new float[]{1, 2, 16}) {
                var data = scaling(multiplier, 1);
                float expected = multiplier == 16 ? 32 : 2.5f * multiplier;
                assertInfo(helper, spell, player, data, "ui.irons_spellbooks.radius", Utils.stringTruncation(expected, 1));
                try (var scope = SpellCastHooks.enter(snapshot(player, spell, data), player)) {
                    helper.assertTrue(spell.checkPreCastConditions(helper.getLevel(), 1, player, magic), "Scorch targeting failed");
                    var area = (TargetAreaCastData) magic.getAdditionalCastData();
                    cleanup.add(area.getCastingEntity());
                    helper.assertTrue(Math.abs(area.getCastingEntity().getRadius() - expected) < 0.001,
                            "Scorch targeting radius did not match affixes");
                    var target = target(helper, cleanup, area.getCenter().add(3.75, 0, 0));
                    helper.assertTrue(helper.getLevel().getEntitiesOfClass(LivingEntity.class, target.getBoundingBox()).contains(target),
                            "Scorch target is unavailable to the native entity query: multiplier=" + multiplier);
                    helper.assertTrue(Utils.hasLineOfSight(helper.getLevel(), area.getCenter().add(0, 1.5, 0),
                                    target.getBoundingBox().getCenter(), true),
                            "Scorch fixture blocked the native damage sight line: multiplier=" + multiplier);
                    var damageBounds = new AABB(area.getCenter().subtract(expected, expected, expected),
                            area.getCenter().add(expected, expected, expected));
                    helper.assertTrue(helper.getLevel().getEntitiesOfClass(LivingEntity.class, damageBounds).contains(target)
                                    == (multiplier != 1),
                            "Scorch fixture disagrees with the native damage query: multiplier=" + multiplier);
                    float health = target.getHealth();
                    FireField field = capture(helper, FireField.class, cleanup,
                            () -> spell.onCast(helper.getLevel(), 1, player, CastSource.COMMAND, magic));
                    helper.assertTrue(multiplier == 1 ? target.getHealth() == health : target.getHealth() < health,
                            "Scorch initial hit did not use its targeting radius: multiplier=" + multiplier);
                    helper.assertTrue(Math.abs(field.getRadius() - expected) < 0.001,
                            "Scorch fire field ignored radius or applied it twice: " + field.getRadius());
                    area.getCastingEntity().discard();
                    field.discard();
                    target.discard();
                    magic.setAdditionalCastData(null);
                }
            }
            helper.succeed();
        } finally {
            cleanup.forEach(Entity::discard);
            magic.setAdditionalCastData(null);
            helper.getLevel().setBlockAndUpdate(elevatedFloor, originalFloor);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void healingCirclePreviewMatchesEntityWithOneMultiplier(GameTestHelper helper) {
        var player = player(helper, "HealingDimensions");
        var spell = SpellRegistry.HEALING_CIRCLE_SPELL.get();
        var cleanup = new ArrayList<Entity>();
        try {
            for (float multiplier : new float[]{1, 1.5f, 8}) {
                var data = scaling(multiplier, multiplier);
                float radius = multiplier == 8 ? 32 : 5 * multiplier;
                int duration = Math.round(200 * multiplier);
                assertInfo(helper, spell, player, data, "ui.irons_spellbooks.radius", Utils.stringTruncation(radius, 1));
                assertInfo(helper, spell, player, data, "ui.irons_spellbooks.duration", Utils.timeFromTicks(duration, 1));
                var target = target(helper, cleanup, helper.absoluteVec(new Vec3(1, 2, 1)));
                var magic = MagicData.getPlayerMagicData(player);
                magic.setAdditionalCastData(new TargetEntityCastData(target));
                HealingAoe field = capture(helper, HealingAoe.class, cleanup, () -> {
                    try (var scope = SpellCastHooks.enter(snapshot(player, spell, data), player)) {
                        spell.onCast(helper.getLevel(), 1, player, CastSource.COMMAND, magic);
                    }
                });
                helper.assertTrue(Math.abs(field.getRadius() - radius) < 0.001,
                        "Healing circle radius ignored multiplier or applied it twice: " + field.getRadius());
                helper.assertTrue(field.getDuration() == duration,
                        "Healing circle duration ignored multiplier or applied it twice: " + field.getDuration());
                field.discard();
                target.discard();
                magic.setAdditionalCastData(null);
            }
            helper.succeed();
        } finally {
            cleanup.forEach(Entity::discard);
            MagicData.getPlayerMagicData(player).setAdditionalCastData(null);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void poisonSplashPreviewMatchesEffectDurationWithOneMultiplier(GameTestHelper helper) {
        var player = player(helper, "PoisonDuration");
        var spell = SpellRegistry.POISON_SPLASH_SPELL.get();
        var cleanup = new ArrayList<Entity>();
        try {
            for (float multiplier : new float[]{1, 1.5f}) {
                var data = scaling(1, multiplier);
                int duration = Math.round(140 * multiplier);
                assertInfo(helper, spell, player, data, "ui.irons_spellbooks.effect_length", Utils.timeFromTicks(duration, 1));
                var target = target(helper, cleanup, helper.absoluteVec(new Vec3(1, 2, 1)));
                var magic = MagicData.getPlayerMagicData(player);
                magic.setAdditionalCastData(new TargetEntityCastData(target));
                PoisonSplash field = capture(helper, PoisonSplash.class, cleanup, () -> {
                    try (var scope = SpellCastHooks.enter(snapshot(player, spell, data), player)) {
                        spell.onCast(helper.getLevel(), 1, player, CastSource.COMMAND, magic);
                    }
                });
                helper.assertTrue(field.getEffectDuration() == duration,
                        "Poison splash duration ignored multiplier or applied it twice: " + field.getEffectDuration());
                field.discard();
                target.discard();
                magic.setAdditionalCastData(null);
            }
            helper.succeed();
        } finally {
            cleanup.forEach(Entity::discard);
            MagicData.getPlayerMagicData(player).setAdditionalCastData(null);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void directAoeRadiusPreviewMatchesBlackHoleAndEarthquake(GameTestHelper helper) {
        var player = player(helper, "DirectAoeRadius");
        var cleanup = new ArrayList<Entity>();
        try {
            for (var spell : List.of(SpellRegistry.BLACK_HOLE_SPELL.get(), SpellRegistry.EARTHQUAKE_SPELL.get())) {
                float original = Float.parseFloat(info(spell, player, "ui.irons_spellbooks.radius"));
                for (float multiplier : new float[]{1, 1.5f, 8}) {
                    var data = scaling(multiplier, 1);
                    var target = target(helper, cleanup, helper.absoluteVec(new Vec3(1, 2, 1)));
                    var magic = MagicData.getPlayerMagicData(player);
                    magic.setAdditionalCastData(new TargetEntityCastData(target));
                    boolean blackHole = spell == SpellRegistry.BLACK_HOLE_SPELL.get();
                    Class<? extends Entity> type = blackHole ? BlackHole.class : AoeEntity.class;
                    Entity field = capture(helper, type, cleanup, () -> {
                        try (var scope = SpellCastHooks.enter(snapshot(player, spell, data), player)) {
                            spell.onCast(helper.getLevel(), 1, player, CastSource.COMMAND, magic);
                        }
                    });
                    float radius = field instanceof BlackHole hole ? hole.getRadius() : ((AoeEntity) field).getRadius();
                    float expected = multiplier == 8 ? (blackHole ? 48 : 32) : original * multiplier;
                    assertInfo(helper, spell, player, data, "ui.irons_spellbooks.radius", Utils.stringTruncation(radius, 1));
                    helper.assertTrue(Math.abs(radius - expected) < 0.06,
                            "Direct AoE radius ignored multiplier or applied it twice: " + spell.getSpellId() + " radius=" + radius);
                    field.discard();
                    target.discard();
                    magic.setAdditionalCastData(null);
                }
            }
            helper.succeed();
        } finally {
            cleanup.forEach(Entity::discard);
            MagicData.getPlayerMagicData(player).setAdditionalCastData(null);
        }
    }

    private static ServerPlayer player(GameTestHelper helper, String name) {
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "[" + name + "]"));
        player.setPos(helper.absoluteVec(new Vec3(1, 2, -5)));
        MagicData.getPlayerMagicData(player).getSyncedData();
        return player;
    }

    private static LivingEntity target(GameTestHelper helper, List<Entity> cleanup, Vec3 position) {
        var target = EntityType.IRON_GOLEM.create(helper.getLevel());
        target.setNoAi(true);
        target.setNoGravity(true);
        target.setPos(position);
        helper.getLevel().addFreshEntity(target);
        cleanup.add(target);
        return target;
    }

    private static ReforgeCache.Data scaling(float radius, float duration) {
        return new ReforgeCache.Data(1, 1, 1, 1, 0, radius, duration);
    }

    private static SpellCastHooks.Snapshot snapshot(ServerPlayer player, AbstractSpell spell, ReforgeCache.Data data) {
        return new SpellCastHooks.Snapshot(player.getUUID(), spell.getSpellId(), 1, data, SpellEffects.NONE);
    }

    private static void assertInfo(GameTestHelper helper, AbstractSpell spell, ServerPlayer player, ReforgeCache.Data data,
                                   String key, String expected) {
        var context = new SpellCastHooks.Context(ItemStack.EMPTY, player, -1, 1, data, new SpellData(spell, 1));
        try (var scope = SpellCastHooks.enter(context)) {
            String actual = info(spell, player, key);
            helper.assertTrue(actual.equals(expected),
                    "Spell preview disagrees with execution: " + spell.getSpellId() + ' ' + key + " actual=" + actual + " expected=" + expected);
        }
    }

    private static String info(AbstractSpell spell, ServerPlayer player, String key) {
        for (var component : spell.getUniqueInfo(1, player)) {
            if (component.getContents() instanceof TranslatableContents contents && key.equals(contents.getKey())) {
                return String.valueOf(contents.getArgs()[0]);
            }
        }
        throw new AssertionError("Missing spell info: " + spell.getSpellId() + ' ' + key);
    }

    private static <T extends Entity> T capture(GameTestHelper helper, Class<T> type, List<Entity> cleanup, Runnable cast) {
        var spawned = new ArrayList<T>();
        Consumer<EntityJoinLevelEvent> listener = event -> {
            if (event.getLevel() == helper.getLevel() && type.isInstance(event.getEntity())) {
                var entity = type.cast(event.getEntity());
                spawned.add(entity);
                cleanup.add(entity);
            }
        };
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, EntityJoinLevelEvent.class, listener);
        try {
            cast.run();
            helper.assertTrue(spawned.size() == 1, "Expected one " + type.getSimpleName() + " but got " + spawned.size());
            return spawned.get(0);
        } finally {
            MinecraftForge.EVENT_BUS.unregister(listener);
        }
    }
}
