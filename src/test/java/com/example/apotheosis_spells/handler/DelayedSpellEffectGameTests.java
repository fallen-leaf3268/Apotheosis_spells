package com.example.apotheosis_spells.handler;

import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.api.SpellEffects;
import com.mojang.authlib.GameProfile;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.effect.EchoingStrikesEffect;
import io.redspace.ironsspellbooks.effect.FrostbiteEffect;
import io.redspace.ironsspellbooks.effect.ThunderstormEffect;
import io.redspace.ironsspellbooks.entity.mobs.frozen_humanoid.FrozenHumanoid;
import io.redspace.ironsspellbooks.entity.spells.EchoingStrikeEntity;
import io.redspace.ironsspellbooks.entity.spells.LightningStrike;
import io.redspace.ironsspellbooks.entity.spells.icicle.IcicleProjectile;
import io.redspace.ironsspellbooks.registries.MobEffectRegistry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

@GameTestHolder("apotheosis_spells")
@PrefixGameTestTemplate(false)
public final class DelayedSpellEffectGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void delayedPreviewsAndCastsMatchNativePower(GameTestHelper helper) {
        var player = player(helper, "DelayedPreview");
        for (var spell : spells()) {
            var effect = effect(spell);
            for (int level : new int[]{1, 4}) {
                for (float multiplier : new float[]{1, 1.5f}) {
                    player.removeEffect(effect);
                    var expectedInfo = nativePower(player, multiplier,
                            () -> spell.getUniqueInfo(level, player).stream().map(c -> c.getString()).toList());
                    var expected = nativePower(player, multiplier, () -> cast(helper, player, spell, level, null));
                    player.removeEffect(effect);
                    var source = source(player, spell, level, multiplier, List.of());
                    var preview = new SpellCastHooks.Context(ItemStack.EMPTY, player, -1, level,
                            source.data(), new SpellData(spell, level));
                    try (var scope = SpellCastHooks.enter(preview)) {
                        helper.assertTrue(spell.getUniqueInfo(level, player).stream().map(c -> c.getString()).toList().equals(expectedInfo),
                                "Delayed preview differs from native power: " + spell.getSpellId() + " level=" + level);
                    }
                    var actual = cast(helper, player, spell, level, source);
                    helper.assertTrue(actual.getAmplifier() == expected.getAmplifier(), "Delayed amplifier differs from native power");
                    helper.assertTrue(actual.getDuration() == expected.getDuration(), "Delayed duration differs from native power");
                    sameSource(helper, actual, source, "Cast did not retain its complete source");
                    player.removeEffect(effect);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void delayedTriggersRestoreSourceOutsideCast(GameTestHelper helper) {
        var player = player(helper, "DelayedTrigger");
        var target = target(helper, new Vec3(2, 2, 3));
        var cleanup = new ArrayList<Entity>();
        cleanup.add(target);
        helper.runAfterDelay(4, () -> {
            try {
                for (var spell : spells()) {
                    for (float multiplier : new float[]{1, 1.5f}) {
                        player.removeEffect(effect(spell));
                        var source = source(player, spell, 4, multiplier, List.of());
                        var active = cast(helper, player, spell, 4, source);
                        float expected = nativePower(player, multiplier,
                                () -> damage(spell, active.getAmplifier(), player));
                        var other = source(player, SpellRegistry.GUST_SPELL.get(), 1, 7, List.of());
                        try (var scope = SpellCastHooks.enter(other, player)) {
                            var spawned = capture(helper, cleanup, () -> trigger(player, target, active));
                            var child = child(helper, spawned, spell);
                            near(helper, childDamage(child), expected, "Delayed trigger borrowed another cast or lost power");
                            sameSource(helper, SpellCastHooks.entitySnapshot(child), source, "Delayed child lost its complete source");
                            assertManaLeech(helper, player, child, spell);
                            helper.assertTrue(SpellCastHooks.currentSnapshot() == other, "Delayed trigger did not restore surrounding scope");
                            spawned.forEach(Entity::discard);
                        }
                        player.removeEffect(effect(spell));
                    }
                }
                helper.succeed();
            } finally {
                player.removeAllEffects();
                cleanup.forEach(Entity::discard);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void echoingRadiusReachesNativeBurstAndPersistsSource(GameTestHelper helper) {
        var player = player(helper, "EchoRadius");
        var spell = SpellRegistry.ECHOING_STRIKES_SPELL.get();
        var victim = target(helper, new Vec3(2, 2, 3));
        var inner = target(helper, new Vec3(4.5, 2, 3));
        var outer = target(helper, new Vec3(5.5, 2, 3));
        var cleanup = new ArrayList<Entity>(List.of(victim, inner, outer));
        helper.runAfterDelay(4, () -> {
            try {
                for (float radius : new float[]{1, 1.5f, 2, 20}) {
                    player.removeEffect(effect(spell));
                    var source = echoSource(player, radius);
                    var active = cast(helper, player, spell, 4, source);
                    player.removeEffect(effect(spell));
                    var restoredEffect = MobEffectInstance.load(active.save(new CompoundTag()));
                    player.forceAddEffect(restoredEffect, player);
                    float expectedDamage = nativePower(player, 1.5f,
                            () -> EchoingStrikesEffect.getDamageModifier(restoredEffect.getAmplifier(), player));
                    var other = echoSource(player, 7);
                    try (var scope = SpellCastHooks.enter(other, player)) {
                        var echo = (EchoingStrikeEntity) child(helper,
                                capture(helper, cleanup, () -> trigger(player, victim, restoredEffect)), spell);
                        float expectedRadius = radius == 20 ? 32 : radius == 2 ? 4 : radius == 1.5f ? 3 : 2;
                        near(helper, echo.getRadius(), expectedRadius, "Echo's delayed radius did not use its saved source");
                        near(helper, echo.getDamage(), expectedDamage, "Echo's constructor multiplied damage again");
                        sameSource(helper, SpellCastHooks.entitySnapshot(echo), source, "Echo lost its complete radius source");
                        helper.assertTrue(echo.getDuration() == 600 && waitTime(echo) == 20,
                                "Echo's duration affix changed its native child lifetime or burst delay");
                        var saved = new CompoundTag();
                        helper.assertTrue(echo.save(saved), "Echo did not save");
                        var restored = (EchoingStrikeEntity) EntityType.loadEntityRecursive(saved, helper.getLevel(), entity -> entity);
                        helper.assertTrue(restored != null, "Echo did not load");
                        near(helper, restored.getRadius(), expectedRadius, "Echo's radius did not survive NBT");
                        sameSource(helper, SpellCastHooks.entitySnapshot(restored), source, "Echo's NBT lost its complete source");
                        helper.assertTrue(restored.getDuration() == 600 && waitTime(restored) == 20,
                                "Echo's NBT changed its native child timing");
                        restored.discard();
                        if (radius <= 2) {
                            inner.setHealth(inner.getMaxHealth());
                            outer.setHealth(outer.getMaxHealth());
                            inner.invulnerableTime = 0;
                            outer.invulnerableTime = 0;
                            echo.tickCount = 20;
                            echo.tick();
                            helper.assertTrue((inner.getHealth() < inner.getMaxHealth()) == (radius >= 1.5f),
                                    "Echo's native burst did not reach the first expanded annulus: " + radius);
                            helper.assertTrue((outer.getHealth() < outer.getMaxHealth()) == (radius == 2),
                                    "Echo's native burst did not reach the second expanded annulus: " + radius);
                        }
                        assertManaLeech(helper, player, echo, spell);
                        helper.assertTrue(SpellCastHooks.currentSnapshot() == other, "Echo construction leaked its saved source");
                        echo.discard();
                    }
                }
                helper.succeed();
            } finally {
                player.removeAllEffects();
                cleanup.forEach(Entity::discard);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void echoingRadiusPreviewAndNativeEffectsStayIsolated(GameTestHelper helper) {
        var player = player(helper, "EchoRadiusPreview");
        var foreignOwner = player(helper, "EchoRadiusOwner");
        var spell = SpellRegistry.ECHOING_STRIKES_SPELL.get();
        var victim = target(helper, new Vec3(2, 2, 3));
        var cleanup = new ArrayList<Entity>(List.of(victim));
        try {
            for (float radius : new float[]{1, 1.5f, 2, 20}) {
                var source = echoSource(player, radius);
                var preview = new SpellCastHooks.Context(ItemStack.EMPTY, player, -1, 4,
                        source.data(), new SpellData(spell, 4));
                try (var scope = SpellCastHooks.enter(preview)) {
                    near(helper, displayedRadius(spell, player), radius == 20 ? 32 : radius == 2 ? 4 : radius == 1.5f ? 3 : 2,
                            "Echo preview did not show its scaled native radius");
                    near(helper, displayedRadius(spell, foreignOwner), 2, "Echo preview borrowed another caster's radius");
                }
                near(helper, displayedRadius(spell, player), 2, "Echo radius leaked outside preview scope");
            }
            for (var source : new SpellCastHooks.Snapshot[]{null, echoSource(foreignOwner, 2)}) {
                player.removeEffect(effect(spell));
                var active = instance(helper, effect(spell), 100, 2, source);
                player.forceAddEffect(active, player);
                float expectedDamage = EchoingStrikesEffect.getDamageModifier(active.getAmplifier(), player);
                var other = echoSource(player, 7);
                try (var scope = SpellCastHooks.enter(other, player)) {
                    var echo = (EchoingStrikeEntity) child(helper,
                            capture(helper, cleanup, () -> trigger(player, victim, active)), spell);
                    near(helper, echo.getRadius(), 2, "A native or foreign-owner Echo borrowed the outer radius");
                    near(helper, echo.getDamage(), expectedDamage, "A native or foreign-owner Echo borrowed outer power");
                    helper.assertTrue(echo.getDuration() == 600 && waitTime(echo) == 20,
                            "A native or foreign-owner Echo borrowed outer timing");
                    helper.assertTrue(SpellCastHooks.entitySnapshot(echo) == null, "A native or foreign-owner Echo acquired a source");
                    helper.assertTrue(SpellCastHooks.currentSnapshot() == other, "Native Echo did not restore its outer scope");
                    echo.discard();
                }
            }
            helper.succeed();
        } finally {
            player.removeAllEffects();
            cleanup.forEach(Entity::discard);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void frozenShardsKeepFrostbiteSourceAndDamageModules(GameTestHelper helper) {
        var player = player(helper, "FrozenSource");
        var victim = target(helper, new Vec3(2, 2, 3));
        var cleanup = new ArrayList<Entity>();
        cleanup.add(victim);
        var source = source(player, SpellRegistry.FROSTBITE_SPELL.get(), 4, 1.5f, List.of());
        var active = cast(helper, player, SpellRegistry.FROSTBITE_SPELL.get(), 4, source);
        var frozen = (FrozenHumanoid) child(helper, capture(helper, cleanup, () -> trigger(player, victim, active)),
                SpellRegistry.FROSTBITE_SPELL.get());
        helper.runAfterDelay(4, () -> {
            try {
                var other = source(player, SpellRegistry.GUST_SPELL.get(), 1, 7, List.of());
                try (var scope = SpellCastHooks.enter(other, player)) {
                    frozen.invulnerableTime = 0;
                    var shards = capture(helper, cleanup, () -> helper.assertTrue(
                            frozen.hurt(player.damageSources().playerAttack(player), 1), "Frozen humanoid did not shatter"))
                            .stream().filter(IcicleProjectile.class::isInstance).map(IcicleProjectile.class::cast).toList();
                    helper.assertTrue(shards.size() == 8, "Expected eight native icicle shards");
                    for (var shard : shards) {
                        sameSource(helper, SpellCastHooks.entitySnapshot(shard), source, "Frozen shard lost its source");
                        sameSource(helper, SpellCastHooks.forDamage(SpellRegistry.ICICLE_SPELL.get().getDamageSource(shard, player)),
                                source, "ICICLE damage attribution rejected the frostbite source");
                    }
                    assertManaLeech(helper, player, shards.get(0), SpellRegistry.ICICLE_SPELL.get());
                    helper.assertTrue(SpellCastHooks.currentSnapshot() == other, "Shatter leaked its source scope");
                }
                helper.succeed();
            } finally {
                player.removeAllEffects();
                cleanup.forEach(Entity::discard);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void effectMergeCopyHiddenAndNbtKeepCorrectSources(GameTestHelper helper) {
        var player = player(helper, "EffectHistory");
        for (var spell : spells()) {
            var a = source(player, spell, 1, 1.5f, List.of());
            var b = source(player, spell, 4, 2, List.of());
            var c = source(player, spell, 2, 3, List.of());
            var active = instance(helper, effect(spell), 20, 1, a);
            helper.assertTrue(active.update(instance(helper, effect(spell), 5, 3, b)), "Stronger effect was rejected");
            sameSource(helper, active, b, "Stronger effect did not adopt source");
            var saved = active.save(new CompoundTag());
            sameSource(helper, MobEffectInstance.load(saved.getCompound("HiddenEffect")), a, "Stronger short effect lost previous hidden source");
            active.update(instance(helper, effect(spell), 40, 0, c));
            saved = active.save(new CompoundTag());
            sameSource(helper, MobEffectInstance.load(saved.getCompound("HiddenEffect").getCompound("HiddenEffect")), c,
                    "Weak longer effect did not preserve recursive hidden source");
            var restored = MobEffectInstance.load(saved);
            sameSource(helper, restored, b, "NBT lost active source");
            sameSource(helper, new MobEffectInstance(restored), b, "Copy constructor lost source");
            for (int i = 0; i < 5; i++) restored.tick(player, () -> {});
            sameSource(helper, restored, a, "Hidden promotion did not restore previous source");
            for (int i = 0; i < 15; i++) restored.tick(player, () -> {});
            sameSource(helper, restored, c, "Recursive hidden promotion lost source");
            helper.assertTrue(restored.getDuration() == 20, "Hidden durations stopped counting down");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void equalAndInfiniteEffectsAdoptOnlyNativeActiveUpdates(GameTestHelper helper) {
        var player = player(helper, "EffectUpdates");
        for (var spell : spells()) {
            var a = source(player, spell, 1, 1.5f, List.of());
            var b = source(player, spell, 1, 2, List.of());
            var active = instance(helper, effect(spell), 20, 2, a);
            active.update(instance(helper, effect(spell), 10, 2, b));
            sameSource(helper, active, a, "Same amplifier shorter effect replaced source");
            active.update(instance(helper, effect(spell), 20, 2, b));
            sameSource(helper, active, a, "Equal effect replaced source");
            var flagsOnly = new MobEffectInstance(effect(spell), 10, 2, false, false, false);
            active.update(flagsOnly);
            sameSource(helper, active, a, "Cosmetic update cleared source");
            active.update(instance(helper, effect(spell), 21, 2, b));
            sameSource(helper, active, b, "Same amplifier longer effect did not replace source");
            active.update(new MobEffectInstance(effect(spell), 22, 2));
            helper.assertTrue(snapshot(active) == null, "Native active replacement retained stale source");
            var infinite = instance(helper, effect(spell), -1, 1, a);
            infinite.update(instance(helper, effect(spell), 2, 2, b));
            for (int i = 0; i < 2; i++) infinite.tick(player, () -> {});
            sameSource(helper, infinite, a, "Infinite hidden effect lost source");
            helper.assertTrue(infinite.getDuration() == -1, "Infinite hidden duration changed");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nativeAddForceExpiryAndClearFollowInstanceLifetime(GameTestHelper helper) {
        var player = player(helper, "EffectLifetime");
        var spell = SpellRegistry.ECHOING_STRIKES_SPELL.get();
        var effect = effect(spell);
        var a = source(player, spell, 1, 1.5f, List.of());
        var b = source(player, spell, 1, 2, List.of());
        player.addEffect(instance(helper, effect, 2, 3, a));
        helper.assertTrue(!player.addEffect(instance(helper, effect, 10, 1, b)), "Weak hidden add should return false");
        tickEffects(player);
        tickEffects(player);
        sameSource(helper, player.getEffect(effect), b, "Weak hidden source was lost when add returned false");
        player.forceAddEffect(instance(helper, effect, 1, 4, a), player);
        sameSource(helper, player.getEffect(effect), a, "forceAddEffect lost incoming source");
        tickEffects(player);
        helper.assertTrue(player.getEffect(effect) == null, "Expired effect retained active state");
        player.addEffect(instance(helper, effect, 20, 1, b));
        player.removeEffect(effect);
        helper.assertTrue(player.getEffect(effect) == null, "Explicit remove retained effect");
        player.addEffect(instance(helper, effect, 20, 1, a));
        player.removeAllEffects();
        helper.assertTrue(player.getEffect(effect) == null, "Clear retained effect");
        player.addEffect(new MobEffectInstance(effect, 20, 1));
        helper.assertTrue(snapshot(player.getEffect(effect)) == null, "New native effect borrowed a removed source");
        player.removeAllEffects();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void delayedSourceAttributesFreezeWhileNativeAttributesRemainDynamic(GameTestHelper helper) {
        var player = player(helper, "DelayedAttributes");
        var target = target(helper, new Vec3(2, 2, 3));
        var cleanup = new ArrayList<Entity>();
        cleanup.add(target);
        var powerId = ForgeRegistries.ATTRIBUTES.getKey(AttributeRegistry.SPELL_POWER.get()).toString();
        var originalBonuses = List.of(new SpellCastHooks.AttributeBonus(powerId, 0.25, AttributeModifier.Operation.ADDITION));
        var currentBonuses = List.of(new SpellCastHooks.AttributeBonus(powerId, 4, AttributeModifier.Operation.ADDITION));
        helper.runAfterDelay(4, () -> {
            try {
                for (var spell : spells()) {
                    var source = source(player, spell, 4, 1.5f, originalBonuses);
                    var active = cast(helper, player, spell, 4, source);
                    nativePower(player, 1.4f, () -> {
                        float expected;
                        try (var attributes = new BookAttributeHandler.AttributeScope(player, originalBonuses)) {
                            expected = nativePower(player, 1.5f, () -> damage(spell, active.getAmplifier(), player));
                        }
                        var other = source(player, SpellRegistry.GUST_SPELL.get(), 1, 7, currentBonuses);
                        try (var scope = SpellCastHooks.enter(other, player)) {
                            double before = player.getAttributeValue(AttributeRegistry.SPELL_POWER.get());
                            var spawned = capture(helper, cleanup, () -> trigger(player, target, active));
                            near(helper, childDamage(child(helper, spawned, spell)), expected,
                                    "Source book attributes changed or native dynamic power was frozen");
                            near(helper, (float) player.getAttributeValue(AttributeRegistry.SPELL_POWER.get()), (float) before,
                                    "Delayed attributes did not restore surrounding book attributes");
                            spawned.forEach(Entity::discard);
                        }
                        return null;
                    });
                    player.removeEffect(effect(spell));
                }
                helper.succeed();
            } finally {
                player.removeAllEffects();
                cleanup.forEach(Entity::discard);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nativeEffectsDoNotBorrowAnotherSpellSnapshot(GameTestHelper helper) {
        var player = player(helper, "NativeEffect");
        var target = target(helper, new Vec3(2, 2, 3));
        var cleanup = new ArrayList<Entity>();
        cleanup.add(target);
        helper.runAfterDelay(4, () -> {
            try {
                for (var spell : spells()) {
                    var active = new MobEffectInstance(effect(spell), 40, 5);
                    player.addEffect(active);
                    float expected = damage(spell, active.getAmplifier(), player);
                    var other = source(player, spell, 1, 7, List.of());
                    try (var scope = SpellCastHooks.enter(other, player)) {
                        var spawned = capture(helper, cleanup, () -> trigger(player, target, active));
                        var child = child(helper, spawned, spell);
                        near(helper, childDamage(child), expected, "Native effect borrowed surrounding power");
                        helper.assertTrue(SpellCastHooks.entitySnapshot(child) == null, "Native child borrowed surrounding snapshot");
                        spawned.forEach(Entity::discard);
                    }
                    player.removeEffect(effect(spell));
                }
                helper.succeed();
            } finally {
                player.removeAllEffects();
                cleanup.forEach(Entity::discard);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void effectNbtRejectsWrongSpellAndKeepsNativeLegacyData(GameTestHelper helper) {
        var player = player(helper, "EffectNbtBoundary");
        for (var spell : spells()) {
            var nativeTag = new MobEffectInstance(effect(spell), 60, 2).save(new CompoundTag());
            helper.assertTrue(snapshot(MobEffectInstance.load(nativeTag)) == null, "Legacy native effect acquired a source");
            nativeTag.put(SpellCastHooks.SNAPSHOT_KEY, source(player, SpellRegistry.GUST_SPELL.get(), 1, 7, List.of()).write());
            helper.assertTrue(snapshot(MobEffectInstance.load(nativeTag)) == null, "Effect accepted another spell's source");
            nativeTag.put(SpellCastHooks.SNAPSHOT_KEY, new CompoundTag());
            helper.assertTrue(snapshot(MobEffectInstance.load(nativeTag)) == null, "Malformed source became active");
            var reusedTag = instance(helper, effect(spell), 60, 2, source(player, spell, 1, 1.5f, List.of()))
                    .save(new CompoundTag());
            new MobEffectInstance(effect(spell), 60, 2).save(reusedTag);
            helper.assertTrue(snapshot(MobEffectInstance.load(reusedTag)) == null, "Reused NBT resurrected a removed source");
        }
        helper.succeed();
    }

    private static List<AbstractSpell> spells() {
        return List.of(SpellRegistry.FROSTBITE_SPELL.get(), SpellRegistry.ECHOING_STRIKES_SPELL.get(),
                SpellRegistry.THUNDERSTORM_SPELL.get());
    }

    private static MobEffect effect(AbstractSpell spell) {
        return switch (spell.getSpellId()) {
            case "irons_spellbooks:frostbite" -> MobEffectRegistry.FROSTBITTEN_STRIKES.get();
            case "irons_spellbooks:echoing_strikes" -> MobEffectRegistry.ECHOING_STRIKES.get();
            default -> MobEffectRegistry.THUNDERSTORM.get();
        };
    }

    private static ServerPlayer player(GameTestHelper helper, String name) {
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "[" + name + "]"));
        player.setPos(helper.absoluteVec(new Vec3(2, 2, 0)));
        player.getAttribute(dev.shadowsoffire.attributeslib.api.ALObjects.Attributes.CRIT_CHANCE.get()).setBaseValue(0);
        MagicData.getPlayerMagicData(player).getSyncedData();
        return player;
    }

    private static LivingEntity target(GameTestHelper helper, Vec3 position) {
        var target = EntityType.HUSK.create(helper.getLevel());
        target.setNoAi(true);
        target.setNoGravity(true);
        target.setPos(helper.absoluteVec(position));
        helper.getLevel().addFreshEntity(target);
        return target;
    }

    private static SpellCastHooks.Snapshot source(ServerPlayer player, AbstractSpell spell, int level,
                                                   float multiplier, List<SpellCastHooks.AttributeBonus> bonuses) {
        return new SpellCastHooks.Snapshot(player.getUUID(), spell.getSpellId(), level,
                new ReforgeCache.Data(multiplier, 1, 1, 1, 0, 1, 1), SpellEffects.ofManaLeech(0.25f), bonuses);
    }

    private static SpellCastHooks.Snapshot echoSource(ServerPlayer player, float radius) {
        return new SpellCastHooks.Snapshot(player.getUUID(), SpellRegistry.ECHOING_STRIKES_SPELL.get().getSpellId(), 4,
                new ReforgeCache.Data(1.5f, 1, 1, 1, 0, radius, 1.5f), SpellEffects.ofManaLeech(0.25f));
    }

    private static float displayedRadius(AbstractSpell spell, LivingEntity caster) {
        return spell.getUniqueInfo(4, caster).stream()
                .map(component -> component.getContents())
                .filter(net.minecraft.network.chat.contents.TranslatableContents.class::isInstance)
                .map(net.minecraft.network.chat.contents.TranslatableContents.class::cast)
                .filter(contents -> contents.getKey().equals("ui.irons_spellbooks.radius"))
                .map(contents -> ((Number) contents.getArgs()[0]).floatValue()).findFirst().orElseThrow();
    }

    private static int waitTime(EchoingStrikeEntity echo) {
        try {
            var field = EchoingStrikeEntity.class.getDeclaredField("waitTime");
            field.setAccessible(true);
            return field.getInt(echo);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read Echoing Strike's native delay", failure);
        }
    }

    private static MobEffectInstance cast(GameTestHelper helper, ServerPlayer player, AbstractSpell spell, int level,
                                          SpellCastHooks.Snapshot source) {
        try (var scope = SpellCastHooks.enter(source, player)) {
            spell.onCast(helper.getLevel(), level, player, CastSource.COMMAND, MagicData.getPlayerMagicData(player));
        }
        var effect = player.getEffect(effect(spell));
        helper.assertTrue(effect != null, "Spell did not apply its native effect: " + spell.getSpellId());
        return effect;
    }

    private static void trigger(ServerPlayer player, LivingEntity target, MobEffectInstance active) {
        if (active.getEffect() == MobEffectRegistry.FROSTBITTEN_STRIKES.get()) {
            target.setTicksFrozen(target.getTicksRequiredToFreeze());
            FrostbiteEffect.handleFrostbiteDeathEffects(new LivingDeathEvent(target, player.damageSources().playerAttack(player)));
        } else if (active.getEffect() == MobEffectRegistry.ECHOING_STRIKES.get()) {
            EchoingStrikesEffect.createEcho(new LivingHurtEvent(target, player.damageSources().playerAttack(player), 1));
        } else {
            active.applyEffect(player);
        }
    }

    private static float damage(AbstractSpell spell, int amplifier, LivingEntity player) {
        return switch (spell.getSpellId()) {
            case "irons_spellbooks:frostbite" -> FrostbiteEffect.getDamageForAmplifier(amplifier, player);
            case "irons_spellbooks:echoing_strikes" -> EchoingStrikesEffect.getDamageModifier(amplifier, player);
            default -> ThunderstormEffect.getDamageFromAmplifier(amplifier, player);
        };
    }

    private static Entity child(GameTestHelper helper, List<Entity> entities, AbstractSpell spell) {
        Class<? extends Entity> type = switch (spell.getSpellId()) {
            case "irons_spellbooks:frostbite" -> FrozenHumanoid.class;
            case "irons_spellbooks:echoing_strikes" -> EchoingStrikeEntity.class;
            default -> LightningStrike.class;
        };
        var children = entities.stream().filter(type::isInstance).toList();
        helper.assertTrue(!children.isEmpty(), "Native effect did not create " + type.getSimpleName());
        return children.get(0);
    }

    private static float childDamage(Entity child) {
        if (child instanceof EchoingStrikeEntity echo) return echo.getDamage();
        if (child instanceof LightningStrike lightning) return lightning.getDamage();
        try {
            var field = FrozenHumanoid.class.getDeclaredField("shatterProjectileDamage");
            field.setAccessible(true);
            return field.getFloat(child);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read Frozen Humanoid's native damage", failure);
        }
    }

    private static MobEffectInstance instance(GameTestHelper helper, MobEffect effect, int duration, int amplifier,
                                              SpellCastHooks.Snapshot source) {
        var result = new MobEffectInstance(effect, duration, amplifier);
        helper.assertTrue((Object) result instanceof SpellCastHooks.SnapshotCarrier, "MobEffectInstance does not carry source snapshots");
        ((SpellCastHooks.SnapshotCarrier) (Object) result).apoth$setSnapshot(source);
        return result;
    }

    private static SpellCastHooks.Snapshot snapshot(MobEffectInstance effect) {
        return (Object) effect instanceof SpellCastHooks.SnapshotCarrier carrier ? carrier.apoth$getSnapshot() : null;
    }

    private static void sameSource(GameTestHelper helper, MobEffectInstance actual, SpellCastHooks.Snapshot expected, String message) {
        helper.assertTrue(actual != null, message + ": missing effect");
        sameSource(helper, snapshot(actual), expected, message);
    }

    private static void sameSource(GameTestHelper helper, SpellCastHooks.Snapshot actual, SpellCastHooks.Snapshot expected, String message) {
        helper.assertTrue(actual != null && actual.write().equals(expected.write()), message + ": actual=" + actual);
    }

    private static void tickEffects(LivingEntity player) {
        try {
            var method = LivingEntity.class.getDeclaredMethod("tickEffects");
            method.setAccessible(true);
            method.invoke(player);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot tick native living effects", failure);
        }
    }

    private static void assertManaLeech(GameTestHelper helper, ServerPlayer player, Entity child, AbstractSpell spell) {
        var target = EntityType.COW.create(helper.getLevel());
        var magic = MagicData.getPlayerMagicData(player);
        magic.setMana(0);
        var damageSpell = child instanceof FrozenHumanoid ? SpellRegistry.ICICLE_SPELL.get() : spell;
        helper.assertTrue(io.redspace.ironsspellbooks.damage.DamageSources.applyDamage(target, 8,
                damageSpell.getDamageSource(child, player)), "Delayed damage was rejected");
        near(helper, magic.getMana(), 2, "Delayed damage did not execute its source mana-leech module");
        target.discard();
    }

    private static <T> T nativePower(ServerPlayer player, float multiplier, Supplier<T> action) {
        var attribute = player.getAttribute(AttributeRegistry.SPELL_POWER.get());
        var modifier = new AttributeModifier(UUID.randomUUID(), "apoth_test_native_power", multiplier - 1,
                AttributeModifier.Operation.MULTIPLY_TOTAL);
        attribute.addTransientModifier(modifier);
        try {
            return action.get();
        } finally {
            attribute.removeModifier(modifier);
        }
    }

    private static List<Entity> capture(GameTestHelper helper, List<Entity> cleanup, Runnable action) {
        var result = new ArrayList<Entity>();
        Consumer<EntityJoinLevelEvent> listener = event -> {
            if (event.getLevel() == helper.getLevel()) {
                result.add(event.getEntity());
                cleanup.add(event.getEntity());
            }
        };
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, EntityJoinLevelEvent.class, listener);
        try {
            action.run();
            return result;
        } finally {
            MinecraftForge.EVENT_BUS.unregister(listener);
        }
    }

    private static void near(GameTestHelper helper, float actual, float expected, String message) {
        helper.assertTrue(Math.abs(actual - expected) < 0.001f, message + ": actual=" + actual + " expected=" + expected);
    }
}
