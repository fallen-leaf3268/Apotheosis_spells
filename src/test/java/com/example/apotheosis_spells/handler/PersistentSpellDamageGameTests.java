package com.example.apotheosis_spells.handler;

import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.api.SpellEffects;
import com.mojang.authlib.GameProfile;
import io.redspace.ironsspellbooks.api.events.SpellDamageEvent;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.entity.spells.AoeEntity;
import io.redspace.ironsspellbooks.entity.spells.EarthquakeAoe;
import io.redspace.ironsspellbooks.entity.spells.FireEruptionAoe;
import io.redspace.ironsspellbooks.entity.spells.dragon_breath.DragonBreathPool;
import io.redspace.ironsspellbooks.entity.spells.magma_ball.FireField;
import io.redspace.ironsspellbooks.entity.spells.poison_cloud.PoisonCloud;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder("apotheosis_spells")
@PrefixGameTestTemplate(false)
public final class PersistentSpellDamageGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void fieldsApplyExecuteAndLeechWithNativeDamageSources(GameTestHelper helper) {
        var player = player(helper);
        for (int kind = 0; kind < 5; kind++) {
            var target = target(helper);
            var field = field(helper, player, kind);
            var sources = new ArrayList<DamageSource>();
            int[] events = {0};
            Consumer<LivingHurtEvent> hurt = event -> {
                if (event.getEntity() == target) sources.add(event.getSource());
            };
            Consumer<SpellDamageEvent> spellDamage = event -> {
                if (event.getEntity() == target) events[0]++;
            };
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, LivingHurtEvent.class, hurt);
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, SpellDamageEvent.class, spellDamage);
            try {
                int initialFire = target.getRemainingFireTicks();
                target.setHealth(20);
                field.applyEffect(target);
                helper.assertTrue(sources.size() == 1 && close(target.getHealth(), 10),
                        "Native field baseline changed: kind=" + kind + " sources=" + sources.size() + " health=" + target.getHealth());
                var nativeSource = sources.get(0);
                int nativeFire = target.getRemainingFireTicks();
                var nativeMovement = target.getDeltaMovement();
                var nativePoison = target.getEffect(MobEffects.POISON);
                var nativeSlow = target.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
                target.invulnerableTime = 0;
                target.setHealth(20);
                target.setRemainingFireTicks(initialFire);
                target.setDeltaMovement(Vec3.ZERO);
                target.removeAllEffects();
                MagicData.getPlayerMagicData(player).setMana(0);
                SpellCastHooks.attach(field, snapshot(player, kind, effects()));
                field.applyEffect(target);
                helper.assertTrue(close(target.getHealth(), 5), "Persistent field missed execute: kind=" + kind + " health=" + target.getHealth());
                helper.assertTrue(close(MagicData.getPlayerMagicData(player).getMana(), 7.5f), "Persistent field leech did not use settled damage once: kind=" + kind);
                helper.assertTrue(target.hasEffect(MobEffects.GLOWING), "Persistent field missed hit signature: kind=" + kind);
                helper.assertTrue(sources.size() == 2 && sources.get(1).typeHolder().equals(nativeSource.typeHolder())
                        && sources.get(1).getDirectEntity() == nativeSource.getDirectEntity() && sources.get(1).getEntity() == player,
                        "Persistent bridge replaced native damage type or attribution");
                helper.assertTrue(events[0] == 0, "Persistent bridge reposted SpellDamageEvent");
                helper.assertTrue(target.getRemainingFireTicks() == nativeFire && target.getDeltaMovement().equals(nativeMovement),
                        "Persistent bridge changed native fire/knockback follow-up: kind=" + kind
                                + " fire=" + nativeFire + "/" + target.getRemainingFireTicks()
                                + " movement=" + nativeMovement + "/" + target.getDeltaMovement());
                helper.assertTrue(sameEffect(nativePoison, target.getEffect(MobEffects.POISON))
                        && sameEffect(nativeSlow, target.getEffect(MobEffects.MOVEMENT_SLOWDOWN)),
                        "Persistent bridge changed native poison/slowness follow-up");
            } finally {
                MinecraftForge.EVENT_BUS.unregister(hurt);
                MinecraftForge.EVENT_BUS.unregister(spellDamage);
                target.discard();
                field.discard();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void savedFieldsRestoreTheirDamageAffixes(GameTestHelper helper) {
        var owner = player(helper);
        for (int kind = 0; kind < 5; kind++) {
            var original = field(helper, owner, kind);
            var target = target(helper);
            AoeEntity loaded = null;
            try {
                SpellCastHooks.attach(original, snapshot(owner, kind, effects()));
                var saved = new CompoundTag();
                helper.assertTrue(original.save(saved), "Field could not be saved");
                loaded = (AoeEntity) EntityType.create(saved, helper.getLevel()).orElseThrow();
                loaded.setOwner(owner);
                helper.assertTrue(SpellCastHooks.entitySnapshot(loaded).equals(SpellCastHooks.entitySnapshot(original)),
                        "Reloaded field lost its complete source");
                target.setHealth(20);
                MagicData.getPlayerMagicData(owner).setMana(0);
                loaded.applyEffect(target);
                helper.assertTrue(close(target.getHealth(), 5) && close(MagicData.getPlayerMagicData(owner).getMana(), 7.5f)
                        && target.hasEffect(MobEffects.GLOWING), "Reloaded field lost its damage affixes");
            } finally {
                original.discard();
                if (loaded != null) loaded.discard();
                target.discard();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void canceledAndAbsorbedFieldDamageCannotTriggerHitAffixes(GameTestHelper helper) {
        var player = player(helper);
        for (int kind = 0; kind < 5; kind++) {
            for (boolean canceled : new boolean[]{false, true}) {
                var target = target(helper);
                var field = field(helper, player, kind);
                Consumer<LivingDamageEvent> listener = event -> {
                    if (event.getEntity() == target && canceled) event.setCanceled(true);
                };
                MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, LivingDamageEvent.class, listener);
                try {
                    target.setHealth(20);
                    if (!canceled) target.setAbsorptionAmount(100);
                    MagicData.getPlayerMagicData(player).setMana(0);
                    SpellCastHooks.attach(field, snapshot(player, kind, effects()));
                    field.applyEffect(target);
                    helper.assertTrue(close(target.getHealth(), 20), "Canceled/absorbed field hurt health");
                    helper.assertTrue(close(MagicData.getPlayerMagicData(player).getMana(), 0), "Canceled/absorbed field refunded mana");
                    helper.assertTrue(!target.hasEffect(MobEffects.GLOWING), "Canceled/absorbed field triggered hit potion");
                } finally {
                    MinecraftForge.EVENT_BUS.unregister(listener);
                    target.discard();
                    field.discard();
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void invulnerableAndPartiallyAbsorbedFieldsUseOnlySettledDamage(GameTestHelper helper) {
        var owner = player(helper);
        for (int kind = 0; kind < 5; kind++) {
            for (boolean invulnerable : new boolean[]{false, true}) {
                var target = target(helper);
                var field = field(helper, owner, kind);
                try {
                    target.setHealth(20);
                    target.setInvulnerable(invulnerable);
                    if (!invulnerable) target.setAbsorptionAmount(10);
                    SpellCastHooks.attach(field, snapshot(owner, kind, effects()));
                    MagicData.getPlayerMagicData(owner).setMana(0);
                    field.applyEffect(target);
                    helper.assertTrue(close(target.getHealth(), invulnerable ? 20 : 15),
                            "Native immunity/partial absorption changed health: kind=" + kind
                                    + " invulnerable=" + invulnerable + " health=" + target.getHealth()
                                    + " cooldown=" + target.invulnerableTime + " absorption=" + target.getAbsorptionAmount());
                    helper.assertTrue(close(MagicData.getPlayerMagicData(owner).getMana(), invulnerable ? 0 : 2.5f),
                            "A field used unaccepted/unabsorbed damage for leech: kind=" + kind);
                    helper.assertTrue(target.hasEffect(MobEffects.GLOWING) != invulnerable,
                            "A field ignored native immunity when applying its hit affix: kind=" + kind);
                } finally {
                    target.discard();
                    field.discard();
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void unrelatedScopeAndWrongOwnerCannotSupplyFieldAffixes(GameTestHelper helper) {
        var owner = player(helper);
        var stranger = player(helper);
        var unrelated = snapshot(stranger, 0, effects());
        for (int kind = 0; kind < 5; kind++) {
            for (boolean wrongOwner : new boolean[]{false, true}) {
                var target = target(helper);
                var field = field(helper, owner, kind);
                try (var ignored = SpellCastHooks.enter(unrelated, stranger)) {
                    if (wrongOwner) SpellCastHooks.attach(field, snapshot(stranger, kind, effects()));
                    target.setHealth(20);
                    MagicData.getPlayerMagicData(owner).setMana(0);
                    field.applyEffect(target);
                    helper.assertTrue(close(target.getHealth(), 10), "Foreign scope/owner changed native field damage: kind=" + kind
                            + " wrongOwner=" + wrongOwner + " health=" + target.getHealth() + " cooldown=" + target.invulnerableTime);
                    helper.assertTrue(!target.hasEffect(MobEffects.GLOWING), "Foreign scope supplied hit potion");
                    helper.assertTrue(close(MagicData.getPlayerMagicData(owner).getMana(), 0), "Foreign scope supplied leech");
                    helper.assertTrue(SpellCastHooks.currentSnapshot() == unrelated, "Persistent bridge leaked its scope");
                } finally {
                    target.discard();
                    field.discard();
                }
            }
        }
        helper.succeed();
    }

    private static SpellEffects effects() {
        return SpellEffects.ofExecute(0.5f, 25).merge(SpellEffects.ofManaLeech(0.5f))
                .merge(SpellEffects.ofSchoolSignature(1, 0, SpellEffects.SIG_TARGET_TARGET, "minecraft:glowing", 80, 0, 1));
    }

    private static SpellCastHooks.Snapshot snapshot(ServerPlayer player, int kind, SpellEffects effects) {
        AbstractSpell spell = switch (kind) {
            case 0 -> SpellRegistry.SCORCH_SPELL.get();
            case 1 -> SpellRegistry.POISON_SPLASH_SPELL.get();
            case 2 -> SpellRegistry.EARTHQUAKE_SPELL.get();
            case 3 -> SpellRegistry.RAISE_HELL_SPELL.get();
            default -> SpellRegistry.DRAGON_BREATH_SPELL.get();
        };
        return new SpellCastHooks.Snapshot(player.getUUID(), spell.getSpellId(), 1, ReforgeCache.Data.DEF, effects);
    }

    private static AoeEntity field(GameTestHelper helper, ServerPlayer owner, int kind) {
        AoeEntity field = switch (kind) {
            case 0 -> new FireField(helper.getLevel());
            case 1 -> new PoisonCloud(helper.getLevel());
            case 2 -> new EarthquakeAoe(helper.getLevel());
            case 3 -> new FireEruptionAoe(helper.getLevel(), 10);
            default -> new DragonBreathPool(helper.getLevel());
        };
        field.setOwner(owner);
        field.setDamage(10);
        field.setPos(helper.absoluteVec(new Vec3(1, 64, 1)));
        if (field instanceof EarthquakeAoe) {
            field.tickCount = 1;
            field.tick();
        }
        return field;
    }

    private static LivingEntity target(GameTestHelper helper) {
        var target = EntityType.IRON_GOLEM.create(helper.getLevel());
        target.setNoAi(true);
        target.setNoGravity(true);
        target.setPos(helper.absoluteVec(new Vec3(1, 2, 1)));
        helper.getLevel().addFreshEntity(target);
        return target;
    }

    private static ServerPlayer player(GameTestHelper helper) {
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "[DotAudit]"));
        player.getAttribute(dev.shadowsoffire.attributeslib.api.ALObjects.Attributes.CRIT_CHANCE.get()).setBaseValue(0);
        MagicData.getPlayerMagicData(player).getSyncedData();
        return player;
    }

    private static boolean close(float actual, float expected) { return Math.abs(actual - expected) < 0.001f; }

    private static boolean sameEffect(net.minecraft.world.effect.MobEffectInstance expected,
                                      net.minecraft.world.effect.MobEffectInstance actual) {
        return expected == null ? actual == null : actual != null && expected.getAmplifier() == actual.getAmplifier()
                && expected.getDuration() == actual.getDuration();
    }
}
