package com.example.apotheosis_spells.handler;

import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.api.SpellEffects;
import com.mojang.authlib.GameProfile;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.capabilities.magic.TargetEntityCastData;
import io.redspace.ironsspellbooks.entity.spells.AoeEntity;
import io.redspace.ironsspellbooks.entity.mobs.SummonedZombie;
import io.redspace.ironsspellbooks.entity.spells.black_hole.BlackHole;
import io.redspace.ironsspellbooks.entity.spells.gust.GustCollider;
import io.redspace.ironsspellbooks.entity.spells.magma_ball.FireField;
import io.redspace.ironsspellbooks.spells.TargetAreaCastData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
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
public final class ExpandedSpellScalingGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void wallLengthMatchesNativeRecastBudgetDisplayAndNbt(GameTestHelper helper) throws ReflectiveOperationException {
        var caster = player(helper, "WallLength");
        var spell = SpellRegistry.getSpell("irons_spellbooks:wall_of_fire");
        var magic = MagicData.getPlayerMagicData(caster);
        var recasts = magic.getPlayerRecasts();
        var start = caster.position();
        var floorA = BlockPos.containing(start).below();
        var floorB = BlockPos.containing(start.add(4, 0, 0)).below();
        var stateA = helper.getLevel().getBlockState(floorA);
        var stateB = helper.getLevel().getBlockState(floorB);
        var lengthMethod = spell.getClass().getDeclaredMethod("getWallLength", int.class, LivingEntity.class);
        lengthMethod.setAccessible(true);
        float base;
        try (var scope = SpellCastHooks.enter((SpellCastHooks.Context) null)) {
            base = ((Number) lengthMethod.invoke(spell, 1, caster)).floatValue();
        }
        try {
            helper.getLevel().setBlockAndUpdate(floorA, Blocks.STONE.defaultBlockState());
            helper.getLevel().setBlockAndUpdate(floorB, Blocks.STONE.defaultBlockState());
            caster.setXRot(90);
            for (float multiplier : new float[]{1, 1.5f, 2}) {
                if (recasts.hasRecastForSpell(spell)) recasts.removeRecast(recasts.getRecastInstance(spell.getSpellId()),
                        io.redspace.ironsspellbooks.capabilities.magic.RecastResult.COUNTERSPELL);
                caster.setPos(start);
                float expected = base * multiplier;
                try (var scope = SpellCastHooks.enter(snapshot(caster, spell, multiplier, 1), caster)) {
                    var displayed = (net.minecraft.network.chat.contents.TranslatableContents) spell.getUniqueInfo(1, caster).get(1).getContents();
                    helper.assertTrue(displayed.getArgs()[0].equals(io.redspace.ironsspellbooks.api.util.Utils.stringTruncation(expected, 1)),
                            "Wall length display differs from its native budget: " + multiplier);
                    spell.onCast(helper.getLevel(), 1, caster, CastSource.COMMAND, magic);
                }
                var data = (io.redspace.ironsspellbooks.spells.fire.WallOfFireSpell.FireWallData)
                        recasts.getRecastInstance(spell.getSpellId()).getCastData();
                helper.assertTrue(data.maxTotalDistance == expected && data.anchorPoints.size() == 1,
                        "Wall did not capture its scaled native length on the initial cast: " + multiplier);
                caster.setPos(start.add(4, 0, 0));
                try (var scope = SpellCastHooks.enter(snapshot(caster, spell, 7, 1), caster)) {
                    spell.onCast(helper.getLevel(), 1, caster, CastSource.COMMAND, magic);
                }
                helper.assertTrue(data.maxTotalDistance == expected && Math.abs(data.accumulatedDistance - 4) < 0.001f,
                        "A later recast replaced or misused the captured wall budget: " + multiplier);
                var saved = data.serializeNBT();
                var restored = (io.redspace.ironsspellbooks.spells.fire.WallOfFireSpell.FireWallData) spell.getEmptyCastData();
                restored.deserializeNBT(saved);
                helper.assertTrue(restored.anchorPoints.size() == data.anchorPoints.size(), "Wall NBT lost native anchors");
                for (int index = 0; index < data.anchorPoints.size(); index++) {
                    helper.assertTrue(restored.anchorPoints.get(index).distanceTo(data.anchorPoints.get(index)) < 0.001,
                            "Wall NBT lost its native anchor geometry");
                }
                if (multiplier == 1) {
                    helper.assertTrue(!saved.contains("apotheosis_spells:wall_length") && restored.maxTotalDistance == 0,
                            "A neutral wall changed its native saved-data contract");
                } else {
                    helper.assertTrue(restored.maxTotalDistance == expected && restored.accumulatedDistance == data.accumulatedDistance,
                            "Wall NBT lost its captured affix budget: " + multiplier);
                }
            }
            helper.succeed();
        } finally {
            if (recasts.hasRecastForSpell(spell)) recasts.removeRecast(recasts.getRecastInstance(spell.getSpellId()),
                    io.redspace.ironsspellbooks.capabilities.magic.RecastResult.COUNTERSPELL);
            magic.setAdditionalCastData(null);
            helper.getLevel().setBlockAndUpdate(floorA, stateA);
            helper.getLevel().setBlockAndUpdate(floorB, stateB);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void sacrificeUsesItsNativeSummonRadiusInBothVersions(GameTestHelper helper) throws ReflectiveOperationException {
        var caster = player(helper, "SacrificeRadius");
        var spell = SpellRegistry.getSpell("irons_spellbooks:sacrifice");
        var magic = MagicData.getPlayerMagicData(caster);
        var cleanup = new ArrayList<Entity>();
        java.lang.reflect.Method radiusGetter;
        try {
            radiusGetter = spell.getClass().getDeclaredMethod("getRadius", LivingEntity.class);
        } catch (NoSuchMethodException exception) {
            radiusGetter = null;
        }
        try {
            for (float multiplier : new float[]{1, 2}) {
                var summon = new SummonedZombie(helper.getLevel(), caster, false) {
                    @Override
                    public Entity getSummoner() {
                        return caster;
                    }
                };
                summon.setNoAi(true);
                summon.setNoGravity(true);
                summon.setPos(caster.position().add(0, 0, 2));
                helper.getLevel().addFreshEntity(summon);
                cleanup.add(summon);
                float nativeRadius;
                try (var scope = SpellCastHooks.enter((SpellCastHooks.Context) null)) {
                    nativeRadius = radiusGetter != null ? ((Number) radiusGetter.invoke(spell, summon)).floatValue()
                            : 3 * (1 + 0.5f * summon.getHealth() / summon.getMaxHealth());
                }
                var center = summon.getBoundingBox().getCenter();
                float nativeQueryRadius = radiusGetter == null ? nativeRadius : nativeRadius * 0.5f;
                var near = target(helper, cleanup, center.add(0, 0, nativeRadius * 0.25));
                var far = target(helper, cleanup, center.add(0, 0, nativeQueryRadius * 1.5));
                float nearHealth = near.getHealth();
                float farHealth = far.getHealth();
                magic.setAdditionalCastData(new TargetEntityCastData(summon));
                try (var scope = SpellCastHooks.enter(snapshot(caster, spell, multiplier, 1), caster)) {
                    helper.assertTrue(SpellCastHooks.matches(spell, caster) && summon.getSummoner() == caster,
                            "Sacrifice did not retain its real summon owner and source scope");
                    if (radiusGetter != null) {
                        float scaledRadius = ((Number) radiusGetter.invoke(spell, summon)).floatValue();
                        helper.assertTrue(Math.abs(scaledRadius - nativeRadius * multiplier) < 0.001f,
                                "Sacrifice's summon getter lost its multiplier: base=" + nativeRadius + ", actual=" + scaledRadius
                                        + ", multiplier=" + multiplier + ", health=" + summon.getHealth());
                    }
                    spell.onCast(helper.getLevel(), 1, caster, CastSource.COMMAND, magic);
                }
                helper.assertTrue(near.getHealth() < nearHealth, "The native Sacrifice fixture missed its nearby target");
                helper.assertTrue(multiplier == 1 ? far.getHealth() == farHealth : far.getHealth() < farHealth,
                        "Sacrifice did not scale its native summon-based blast query: multiplier=" + multiplier
                                + ", radius=" + nativeRadius + ", queryRadius=" + nativeQueryRadius);
                near.discard();
                far.discard();
            }
            try (var scope = SpellCastHooks.enter(snapshot(caster, spell, 1.5f, 1), caster)) {
                var radius = (net.minecraft.network.chat.contents.TranslatableContents) spell.getUniqueInfo(1, caster).get(1).getContents();
                helper.assertTrue(((Number) radius.getArgs()[0]).floatValue() == 4.5f,
                        "Sacrifice rounded its fractional native base-radius display");
            }
            helper.succeed();
        } finally {
            cleanup.forEach(Entity::discard);
            magic.setAdditionalCastData(null);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void optionalNativeFieldsCaptureRadiusAndDurationOnce(GameTestHelper helper) throws ReflectiveOperationException {
        var caster = player(helper, "OptionalFields");
        var magic = MagicData.getPlayerMagicData(caster);
        var cleanup = new ArrayList<Entity>();
        try {
            for (var id : List.of("gravity_fissure", "blizzard", "fang_swirl")) {
                var spell = SpellRegistry.getSpell("irons_spellbooks:" + id);
                if (spell == SpellRegistry.none()) continue;
                String radiusName = id.equals("fang_swirl") ? "getSwirlRadius" : "getRadius";
                String durationName = id.equals("fang_swirl") ? "getSwirlDurationTicks" : "getDurationTicks";
                var radiusMethod = spell.getClass().getDeclaredMethod(radiusName, int.class, LivingEntity.class);
                var durationMethod = spell.getClass().getDeclaredMethod(durationName, int.class, LivingEntity.class);
                radiusMethod.setAccessible(true);
                durationMethod.setAccessible(true);
                float baseRadius;
                int baseDuration;
                try (var scope = SpellCastHooks.enter((SpellCastHooks.Context) null)) {
                    baseRadius = ((Number) radiusMethod.invoke(spell, 1, caster)).floatValue();
                    baseDuration = ((Number) durationMethod.invoke(spell, 1, caster)).intValue();
                }
                var marker = target(helper, cleanup, caster.position().add(0, 0, 3));
                magic.setAdditionalCastData(new TargetEntityCastData(marker));
                Entity field = capture(helper, Entity.class, cleanup, () -> {
                    try (var scope = SpellCastHooks.enter(snapshot(caster, spell, 2, 1.5f), caster)) {
                        spell.onCast(helper.getLevel(), 1, caster, CastSource.COMMAND, magic);
                    }
                });
                float radius = field instanceof AoeEntity area ? area.getRadius() : ((BlackHole) field).getRadius();
                int duration = field instanceof AoeEntity area ? area.getDuration() : ((BlackHole) field).getDuration();
                var saved = new CompoundTag();
                field.saveWithoutId(saved);
                helper.assertTrue(Math.abs(radius - baseRadius * 2) < 0.001f && duration == Math.round(baseDuration * 1.5f),
                        "A native field did not capture its getter parameters once: " + id);
                helper.assertTrue(saved.getInt("Duration") == duration && Math.abs(saved.getFloat("Radius") - radius) < 0.001f,
                        "A native field lost its scaled parameters when saved: " + id);
                field.discard();
                marker.discard();
                magic.setAdditionalCastData(null);
            }
            var raiseHell = SpellRegistry.getSpell("irons_spellbooks:raise_hell");
            var radiusMethod = raiseHell.getClass().getDeclaredMethod("getRadius", int.class, LivingEntity.class);
            radiusMethod.setAccessible(true);
            try (var scope = SpellCastHooks.enter(snapshot(caster, raiseHell, 8, 1), caster)) {
                float displayed = ((Number) radiusMethod.invoke(raiseHell, 1, caster)).floatValue();
                var field = capture(helper, AoeEntity.class, cleanup,
                        () -> raiseHell.onCast(helper.getLevel(), 1, caster, CastSource.COMMAND, magic));
                helper.assertTrue(displayed == 32 && field.getRadius() == displayed,
                        "Raise Hell's radius display exceeded the native field limit");
                field.discard();
            }
            helper.succeed();
        } finally {
            cleanup.forEach(Entity::discard);
            magic.setAdditionalCastData(null);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void directNativeBuffDurationsScaleWithoutChangingAmplifiers(GameTestHelper helper) {
        var caster = player(helper, "DirectBuffDuration");
        var recipient = EntityType.COW.create(helper.getLevel());
        recipient.setNoAi(true);
        recipient.setNoGravity(true);
        recipient.setPos(caster.position().add(0, 0, 1));
        helper.getLevel().addFreshEntity(recipient);
        helper.runAfterDelay(4, () -> {
        try {
            for (var id : List.of("heartstop", "abyssal_shroud", "planar_sight", "echoing_strikes", "evasion",
                    "fortify", "frostbite", "charge", "gluttony", "oakskin", "spider_aspect")) {
                var spell = SpellRegistry.getSpell("irons_spellbooks:" + id);
                LivingEntity affected = id.equals("fortify") ? recipient : caster;
                caster.removeAllEffects();
                recipient.removeAllEffects();
                try (var scope = SpellCastHooks.enter(snapshot(caster, spell, 1, 1), caster)) {
                    spell.onCast(helper.getLevel(), 1, caster, CastSource.COMMAND, MagicData.getPlayerMagicData(caster));
                }
                var original = affected.getActiveEffects().stream()
                        .collect(java.util.stream.Collectors.toMap(effect -> effect.getEffect(), effect -> effect));
                helper.assertTrue(original.size() == 1, "Native buff fixture did not create one effect: " + id);
                caster.removeAllEffects();
                recipient.removeAllEffects();
                try (var scope = SpellCastHooks.enter(snapshot(caster, spell, 1, 1.5f), caster)) {
                    spell.onCast(helper.getLevel(), 1, caster, CastSource.COMMAND, MagicData.getPlayerMagicData(caster));
                }
                for (var entry : original.entrySet()) {
                    var effect = affected.getEffect(entry.getKey());
                    helper.assertTrue(effect != null && effect.getDuration() == Math.round(entry.getValue().getDuration() * 1.5f),
                            "Direct native buff duration was not captured once: " + id);
                    helper.assertTrue(effect.getAmplifier() == entry.getValue().getAmplifier(),
                            "The duration affix changed the native buff amplifier: " + id);
                }
            }
            helper.succeed();
        } finally {
            caster.removeAllEffects();
            recipient.discard();
        }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nativeParameterFamiliesScaleOnlyTheirOwnCast(GameTestHelper helper) throws ReflectiveOperationException {
        var caster = player(helper, "ParameterFamilies");
        var other = player(helper, "OtherParameters");
        var parameters = List.of(
                "sacrifice:getRadius:r:1", "starfall:getRadius:r:1",
                "telekinesis:getRange:r:2", "firecracker:getRange:r:2", "stomp:getRange:r:2",
                "spectral_hammer:getRadius:r:2", "raise_hell:getRadius:r:2",
                "blood_step:getDistance:r:2", "teleport:getDistance:r:2",
                "frost_step:getDistance:r:2", "thunder_step:getDistance:r:2",
                "portal:getCastDistance:r:2", "portal:getPortalDuration:d:2",
                "arcane_shackle:getLashRadius:r:2", "arcane_shackle:getChainDuration:d:2",
                "fang_swirl:getRange:r:2", "fang_swirl:getSwirlRadius:r:2", "fang_swirl:getSwirlDurationTicks:d:2",
                "gravity_fissure:getRadius:r:2", "gravity_fissure:getDurationTicks:d:2",
                "blizzard:getRadius:r:2", "blizzard:getDurationTicks:d:2",
                "soulfire_ray:getRange:r:2", "angel_wings:getEffectDuration:d:2",
                "thunderstorm:getDurationTicks:d:2", "acid_orb:getRendDuration:d:2",
                "snowball:getDuration:d:2", "ice_tomb:getDuration:d:2",
                "invisibility:getDuration:d:2", "slow:getDuration:d:2", "haste:getDuration:d:2",
                "heat_surge:getDuration:d:2", "frostwave:getDuration:d:2", "blight:getDuration:d:2");
        int verified = 0;
        for (var entry : parameters) {
            var parts = entry.split(":");
            var spell = SpellRegistry.getSpell("irons_spellbooks:" + parts[0]);
            if (spell == SpellRegistry.none()) continue;
            java.lang.reflect.Method method;
            try {
                method = parts[3].equals("1") ? spell.getClass().getDeclaredMethod(parts[1], LivingEntity.class)
                        : spell.getClass().getDeclaredMethod(parts[1], int.class, LivingEntity.class);
            } catch (NoSuchMethodException exception) {
                if (parts[0].equals("sacrifice")) continue;
                throw exception;
            }
            method.setAccessible(true);
            Object[] arguments = parts[3].equals("1") ? new Object[]{caster} : new Object[]{1, caster};
            Object[] otherArguments = parts[3].equals("1") ? new Object[]{other} : new Object[]{1, other};
            float base;
            float otherBase;
            try (var scope = SpellCastHooks.enter((SpellCastHooks.Context) null)) {
                base = ((Number) method.invoke(spell, arguments)).floatValue();
                otherBase = ((Number) method.invoke(spell, otherArguments)).floatValue();
            }
            try (var scope = SpellCastHooks.enter(snapshot(caster, spell, 2, 1.5f), caster)) {
                float expected = base * (parts[2].equals("r") ? 2 : 1.5f);
                if (method.getReturnType() == int.class) expected = Math.round(expected);
                float actual = ((Number) method.invoke(spell, arguments)).floatValue();
                helper.assertTrue(Math.abs(actual - expected) < 0.001f,
                        "Native parameter was not scaled once: " + entry + " " + base + " -> " + actual + ", expected " + expected);
                helper.assertTrue(((Number) method.invoke(spell, otherArguments)).floatValue() == otherBase,
                        "Native parameter inherited another caster's scope: " + entry);
            }
            helper.assertTrue(((Number) method.invoke(spell, arguments)).floatValue() == base,
                    "Native parameter leaked outside the cast: " + entry);
            verified++;
        }
        helper.assertTrue(verified >= 20, "The native parameter inventory was not exercised");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void blackHoleCapturesScaledLifetimeAndPersistsIt(GameTestHelper helper) {
        var caster = player(helper, "BlackHoleLifetime");
        var spell = SpellRegistry.getSpell("irons_spellbooks:black_hole");
        var cleanup = new ArrayList<Entity>();
        try {
            BlackHole hole = capture(helper, BlackHole.class, cleanup, () -> {
                try (var scope = SpellCastHooks.enter(snapshot(caster, spell, 1, 1.5f), caster)) {
                    spell.onCast(helper.getLevel(), 1, caster, CastSource.COMMAND, MagicData.getPlayerMagicData(caster));
                }
            });
            var saved = new CompoundTag();
            hole.saveWithoutId(saved);
            helper.assertTrue(hole.getDuration() == 900 && saved.getInt("Duration") == 900,
                    "Black Hole did not capture its scaled native lifetime");
            try (var scope = SpellCastHooks.enter(snapshot(caster, spell, 1, 3), caster)) {
                helper.assertTrue(hole.getDuration() == 900, "Black Hole recomputed its lifetime from a later cast");
            }
            hole.tickCount = 901;
            hole.tick();
            helper.assertTrue(hole.isRemoved(), "Black Hole survived its captured expiration boundary");
            helper.succeed();
        } finally {
            cleanup.forEach(Entity::discard);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void gustRangeExtendsItsActualCollisionCone(GameTestHelper helper) throws ReflectiveOperationException {
        var caster = player(helper, "GustGeometry");
        caster.setPos(caster.getX(), helper.absolutePos(new BlockPos(0, 192, 0)).getY(), caster.getZ());
        var spell = SpellRegistry.getSpell("irons_spellbooks:gust");
        var cleanup = new ArrayList<Entity>();
        try {
            for (float multiplier : new float[]{1, 2}) {
                var near = target(helper, cleanup, caster.position().add(0, 0.9, 5));
                var far = target(helper, cleanup, caster.position().add(0, 0.9, 12));
                near.setBoundingBox(near.getDimensions(near.getPose()).makeBoundingBox(near.position()));
                far.setBoundingBox(far.getDimensions(far.getPose()).makeBoundingBox(far.position()));
                GustCollider cone = capture(helper, GustCollider.class, cleanup, () -> {
                    try (var scope = SpellCastHooks.enter(snapshot(caster, spell, multiplier, 1), caster)) {
                        spell.onCast(helper.getLevel(), 1, caster, CastSource.COMMAND, MagicData.getPlayerMagicData(caster));
                    }
                });
                helper.assertTrue(near.getDeltaMovement().lengthSqr() > 0,
                        "The native Gust fixture did not push its nearby target: " + multiplier);
                helper.assertTrue(multiplier == 1 ? far.getDeltaMovement().lengthSqr() == 0 : far.getDeltaMovement().lengthSqr() > 0,
                        "Gust increased its displayed range without extending the collision cone: " + multiplier);
                var positions = java.util.Arrays.stream(cone.getParts()).map(Entity::position).toList();
                var boxes = java.util.Arrays.stream(cone.getParts()).map(Entity::getBoundingBox).toList();
                var collisions = io.redspace.ironsspellbooks.entity.spells.AbstractConeProjectile.class
                        .getDeclaredMethod("getSubEntityCollisions");
                collisions.setAccessible(true);
                collisions.invoke(cone);
                collisions.invoke(cone);
                helper.assertTrue(positions.equals(java.util.Arrays.stream(cone.getParts()).map(Entity::position).toList())
                                && boxes.equals(java.util.Arrays.stream(cone.getParts()).map(Entity::getBoundingBox).toList()),
                        "Repeated Gust collision queries changed its geometry: " + multiplier);
                near.setDeltaMovement(Vec3.ZERO);
                far.setDeltaMovement(Vec3.ZERO);
                cone.tick();
                helper.assertTrue(positions.equals(java.util.Arrays.stream(cone.getParts()).map(Entity::position).toList())
                                && boxes.equals(java.util.Arrays.stream(cone.getParts()).map(Entity::getBoundingBox).toList()),
                        "A later Gust tick accumulated its geometry multiplier: " + multiplier);
                cone.discard();
                near.discard();
                far.discard();
            }
            helper.succeed();
        } finally {
            cleanup.forEach(Entity::discard);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void scorchAndEarthquakeUseScaledNativeExpiration(GameTestHelper helper) {
        var caster = player(helper, "FieldLifetime");
        var cleanup = new ArrayList<Entity>();
        var floor = BlockPos.containing(caster.position()).below();
        var previous = helper.getLevel().getBlockState(floor);
        var magic = MagicData.getPlayerMagicData(caster);
        try {
            helper.getLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            for (var id : List.of("irons_spellbooks:scorch", "irons_spellbooks:earthquake")) {
                var spell = SpellRegistry.getSpell(id);
                boolean scorch = id.endsWith(":scorch");
                caster.setXRot(90);
                try (var scope = SpellCastHooks.enter(snapshot(caster, spell, 1, 1.5f), caster)) {
                    if (scorch) {
                        helper.assertTrue(spell.checkPreCastConditions(helper.getLevel(), 1, caster, magic), "Scorch targeting failed");
                        cleanup.add(((TargetAreaCastData) magic.getAdditionalCastData()).getCastingEntity());
                    } else {
                        var marker = target(helper, cleanup, caster.position().add(0, 0, 3));
                        magic.setAdditionalCastData(new TargetEntityCastData(marker));
                    }
                    Class<? extends AoeEntity> fieldType = scorch ? FireField.class : AoeEntity.class;
                    AoeEntity field = capture(helper, fieldType, cleanup,
                            () -> spell.onCast(helper.getLevel(), 1, caster, CastSource.COMMAND, magic));
                    field.tickCount = 1;
                    field.tick();
                    int expected = scorch ? 300 : 360;
                    var saved = new CompoundTag();
                    field.saveWithoutId(saved);
                    helper.assertTrue(field.getDuration() == expected && saved.getInt("Duration") == expected,
                            "A field lost or duplicated its duration multiplier: " + id);
                    field.tickCount = field.getDelay() + expected;
                    field.tick();
                    helper.assertTrue(!field.isRemoved(), "A field expired before its scaled native boundary: " + id);
                    field.tickCount = field.getDelay() + expected + 1;
                    field.tick();
                    helper.assertTrue(field.isRemoved(), "A field survived its scaled native boundary: " + id);
                    magic.setAdditionalCastData(null);
                }
            }
            helper.succeed();
        } finally {
            cleanup.forEach(Entity::discard);
            magic.setAdditionalCastData(null);
            helper.getLevel().setBlockAndUpdate(floor, previous);
        }
    }

    private static ServerPlayer player(GameTestHelper helper, String name) {
        var caster = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "[" + name + "]"));
        var origin = helper.absolutePos(new BlockPos(0, 128, 0));
        caster.setPos((origin.getX() & ~15) + 8.5, origin.getY(), (origin.getZ() & ~15) + 2.5);
        caster.setYRot(0);
        caster.setXRot(0);
        MagicData.getPlayerMagicData(caster).getSyncedData();
        return caster;
    }

    private static LivingEntity target(GameTestHelper helper, List<Entity> cleanup, Vec3 position) {
        var target = EntityType.IRON_GOLEM.create(helper.getLevel());
        target.setNoAi(true);
        target.setNoGravity(true);
        target.setPos(position);
        target.setBoundingBox(new AABB(position.subtract(0.05, 0.05, 0.05), position.add(0.05, 0.05, 0.05)));
        helper.getLevel().addFreshEntity(target);
        cleanup.add(target);
        return target;
    }

    private static SpellCastHooks.Snapshot snapshot(ServerPlayer caster, AbstractSpell spell, float radius, float duration) {
        return new SpellCastHooks.Snapshot(caster.getUUID(), spell.getSpellId(), 1,
                new ReforgeCache.Data(1, 1, 1, 1, 0, radius, duration), SpellEffects.NONE);
    }

    private static <T extends Entity> T capture(GameTestHelper helper, Class<T> type, List<Entity> cleanup, Runnable cast) {
        var spawned = new ArrayList<T>();
        Consumer<EntityJoinLevelEvent> listener = event -> {
            if (event.getLevel() == helper.getLevel() && type.isInstance(event.getEntity())) {
                spawned.add(type.cast(event.getEntity()));
                cleanup.add(event.getEntity());
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
