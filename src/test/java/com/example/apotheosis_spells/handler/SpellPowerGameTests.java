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
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.capabilities.magic.TargetEntityCastData;
import io.redspace.ironsspellbooks.entity.spells.ice_tomb.IceTombEntity;
import io.redspace.ironsspellbooks.entity.spells.magma_ball.FireBomb;
import io.redspace.ironsspellbooks.entity.spells.void_tentacle.VoidTentacle;
import io.redspace.ironsspellbooks.spells.evocation.GustSpell;
import io.redspace.ironsspellbooks.spells.fire.MagmaBombSpell;
import io.redspace.ironsspellbooks.spells.ice.IceTombSpell;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

@GameTestHolder("apotheosis_spells")
@PrefixGameTestTemplate(false)
public final class SpellPowerGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void baseAndOverriddenCastTimesUseOneMultiplier(GameTestHelper helper) {
        var player = player(helper, "CastTimes");
        var failures = new ArrayList<String>();
        for (var spell : List.of(SpellRegistry.GUST_SPELL.get(), SpellRegistry.POCKET_DIMENSION_SPELL.get(),
                SpellRegistry.RECALL_SPELL.get(), SpellRegistry.THROW_SPELL.get(), SpellRegistry.FLAMING_STRIKE_SPELL.get(),
                SpellRegistry.RAISE_HELL_SPELL.get(), SpellRegistry.DIVINE_SMITE_SPELL.get(), SpellRegistry.STOMP_SPELL.get())) {
            int original = spell.getEffectiveCastTime(1, player);
            helper.assertTrue(original > 0, "Expected a timed spell: " + spell.getSpellId());
            for (float multiplier : new float[]{1, 0.5f}) {
                var data = scaling(1, multiplier);
                try (var scope = SpellCastHooks.enter(preview(player, spell, data))) {
                    helper.assertTrue(spell.getEffectiveCastTime(1, player) == original,
                            "Cast time applied during preview before the display multiplier: " + spell.getSpellId());
                }
                try (var scope = SpellCastHooks.enter(snapshot(player, spell, data), player)) {
                    int actual = spell.getEffectiveCastTime(1, player);
                    int expected = Math.round(original * multiplier);
                    if (actual != expected) failures.add(spell.getSpellId() + " multiplier=" + multiplier
                            + " actual=" + actual + " expected=" + expected);
                }
            }
        }
        helper.assertTrue(failures.isEmpty(), "Effective cast time disagrees with preview: " + failures);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void gustStrengthPreviewMatchesExecution(GameTestHelper helper) {
        var player = player(helper, "GustPower");
        var spell = (GustSpell) SpellRegistry.GUST_SPELL.get();
        float reference = spell.getStrength(1, null);
        for (float multiplier : new float[]{1, 1.5f}) {
            float expected = nativePower(player, multiplier, () -> spell.getStrength(1, player));
            String expectedInfo = nativePower(player, multiplier, () -> info(spell, player, "ui.irons_spellbooks.strength"));
            var data = scaling(multiplier, 1);
            try (var scope = SpellCastHooks.enter(preview(player, spell, data))) {
                near(helper, spell.getStrength(1, player), expected, "Gust preview strength");
                near(helper, spell.getStrength(1, null), reference, "Gust reference strength");
                helper.assertTrue(info(spell, player, "ui.irons_spellbooks.strength").equals(expectedInfo),
                        "Gust preview cancelled its power multiplier: expected=" + expectedInfo);
            }
            try (var scope = SpellCastHooks.enter(snapshot(player, spell, data), player)) {
                near(helper, spell.getStrength(1, player), expected, "Gust execution strength");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void magmaImpactAndAoeUseNativePowerOnce(GameTestHelper helper) {
        var player = player(helper, "MagmaPower");
        var spell = (MagmaBombSpell) SpellRegistry.MAGMA_BOMB_SPELL.get();
        var cleanup = new ArrayList<Entity>();
        try {
            for (float multiplier : new float[]{1, 1.5f}) {
                float impact = nativePower(player, multiplier, () -> spell.getDamage(1, player));
                float aoe = nativePower(player, multiplier, () -> spell.getAoeDamage(1, player));
                var data = scaling(multiplier, 1);
                try (var scope = SpellCastHooks.enter(preview(player, spell, data))) {
                    helper.assertTrue(info(spell, player, "ui.irons_spellbooks.damage").equals(Utils.stringTruncation(impact, 2)),
                            "Magma impact preview ignored power");
                    helper.assertTrue(info(spell, player, "ui.irons_spellbooks.aoe_damage").equals(Utils.stringTruncation(aoe, 1)),
                            "Magma AoE preview applied power twice");
                }
                var bombs = capture(helper, FireBomb.class, cleanup, () -> {
                    try (var scope = SpellCastHooks.enter(snapshot(player, spell, data), player)) {
                        spell.onCast(helper.getLevel(), 1, player, CastSource.COMMAND, MagicData.getPlayerMagicData(player));
                    }
                });
                helper.assertTrue(bombs.size() == 1, "Expected one magma bomb");
                near(helper, bombs.get(0).getDamage(), impact, "Magma impact entity damage");
                near(helper, bombs.get(0).getAoeDamage(), aoe, "Magma AoE entity damage");
                bombs.forEach(Entity::discard);
            }
            helper.succeed();
        } finally {
            cleanup.forEach(Entity::discard);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void sculkDamageUsesNativePowerOnce(GameTestHelper helper) {
        var player = player(helper, "SculkPower");
        var spell = SpellRegistry.SCULK_TENTACLES_SPELL.get();
        var cleanup = new ArrayList<Entity>();
        var magic = MagicData.getPlayerMagicData(player);
        try {
            for (float multiplier : new float[]{1, 1.5f}) {
                String expectedInfo = nativePower(player, multiplier, () -> info(spell, player, "ui.irons_spellbooks.damage"));
                var data = scaling(multiplier, 1);
                try (var scope = SpellCastHooks.enter(preview(player, spell, data))) {
                    helper.assertTrue(info(spell, player, "ui.irons_spellbooks.damage").equals(expectedInfo),
                            "Sculk damage preview ignored power");
                }
                var target = EntityType.IRON_GOLEM.create(helper.getLevel());
                target.setNoAi(true);
                target.setNoGravity(true);
                target.setPos(helper.absoluteVec(new Vec3(1, 2, 1)));
                helper.getLevel().addFreshEntity(target);
                cleanup.add(target);
                magic.setAdditionalCastData(new TargetEntityCastData(target));
                var tentacles = capture(helper, VoidTentacle.class, cleanup, () -> {
                    try (var scope = SpellCastHooks.enter(snapshot(player, spell, data), player)) {
                        spell.onCast(helper.getLevel(), 1, player, CastSource.COMMAND, magic);
                    }
                });
                helper.assertTrue(!tentacles.isEmpty(), "Sculk did not create tentacles");
                float health = target.getHealth();
                helper.assertTrue(tentacles.get(0).dealDamage(target), "Sculk tentacle did not damage its target");
                near(helper, health - target.getHealth(), Float.parseFloat(expectedInfo), "Sculk actual damage");
                tentacles.forEach(Entity::discard);
                target.discard();
                magic.setAdditionalCastData(null);
            }
            helper.succeed();
        } finally {
            cleanup.forEach(Entity::discard);
            magic.setAdditionalCastData(null);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void iceTombHealingAndLifetimeUseNativePower(GameTestHelper helper) {
        var player = player(helper, "TombPower");
        var spell = (IceTombSpell) SpellRegistry.ICE_TOMB_SPELL.get();
        var cleanup = new ArrayList<Entity>();
        try {
            for (float multiplier : new float[]{1, 1.5f}) {
                float healing = nativePower(player, multiplier, () -> spell.getHealing(1, player));
                int lifetime = nativePower(player, multiplier, () -> (int) spell.getDuration(1, player));
                var data = scaling(multiplier, 1);
                var tombs = capture(helper, IceTombEntity.class, cleanup, () -> {
                    try (var scope = SpellCastHooks.enter(snapshot(player, spell, data), player)) {
                        spell.onCast(helper.getLevel(), 1, player, CastSource.COMMAND, MagicData.getPlayerMagicData(player));
                    }
                });
                helper.assertTrue(tombs.size() == 1, "Expected one ice tomb");
                CompoundTag saved = tombs.get(0).saveWithoutId(new CompoundTag());
                near(helper, saved.getFloat("healing"), healing, "Ice tomb entity healing");
                helper.assertTrue(saved.getInt("lifetime") == lifetime, "Ice tomb lifetime ignored native power");
                player.stopRiding();
                tombs.forEach(Entity::discard);
            }
            helper.succeed();
        } finally {
            player.stopRiding();
            cleanup.forEach(Entity::discard);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void helperSpellPreviewsMatchNativePower(GameTestHelper helper) {
        var player = player(helper, "HelperPower");
        String version = ModList.get().getModContainerById("irons_spellbooks").orElseThrow()
                .getModInfo().getVersion().toString();
        helper.assertTrue(version.equals("1.20.1-3.15.6") || version.equals("1.20.1-3.16.1"),
                "Unsupported helper coverage fixture version: " + version);
        boolean newer = version.equals("1.20.1-3.16.1");
        var registry = SpellRegistry.REGISTRY.get();
        var spells = new ArrayList<AbstractSpell>();
        var newerOnly = List.of("arcane_shackle", "fang_swirl", "blizzard");
        for (String id : List.of("blood_step", "sculk_tentacles", "arcane_shackle", "teleport", "fang_swirl",
                "magma_bomb", "wall_of_fire", "blizzard", "frost_step", "ice_tomb", "snowball",
                "summon_polar_bear", "earthquake", "stomp")) {
            var key = new ResourceLocation("irons_spellbooks", id);
            boolean registered = registry.containsKey(key);
            helper.assertTrue(registered || !newer && newerOnly.contains(id), "Missing expected helper spell: " + key);
            if (!registered) continue;
            var spell = registry.getValue(key);
            helper.assertTrue(spell != null && key.toString().equals(spell.getSpellId()), "Invalid registered helper spell: " + key);
            spells.add(spell);
        }
        helper.assertTrue(spells.size() == (newer ? 14 : 11),
                "Unexpected helper spell coverage for " + version + ": " + spells.size());
        for (var spell : spells) {
            float referencePower = spell.getSpellPower(1, null);
            float referenceMultiplier = spell.getEntityPowerMultiplier(null);
            for (float multiplier : new float[]{1, 1.5f}) {
                List<String> expected = nativePower(player, multiplier, () -> info(spell, player));
                try (var scope = SpellCastHooks.enter(preview(player, spell, scaling(multiplier, 1)))) {
                    helper.assertTrue(info(spell, player).equals(expected),
                            "Helper-based preview disagrees with native power: " + spell.getSpellId()
                                    + " actual=" + info(spell, player) + " expected=" + expected);
                    near(helper, spell.getSpellPower(1, null), referencePower, "Null caster reference power");
                    near(helper, spell.getEntityPowerMultiplier(null), referenceMultiplier, "Null caster reference multiplier");
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void powerScopeExcludesAiAndOtherSpells(GameTestHelper helper) {
        var player = player(helper, "ScopedPower");
        var ai = EntityType.ZOMBIE.create(helper.getLevel());
        var spell = SpellRegistry.MAGMA_BOMB_SPELL.get();
        var otherSpell = SpellRegistry.GUST_SPELL.get();
        float aiPower = spell.getSpellPower(1, ai);
        float aiMultiplier = spell.getEntityPowerMultiplier(ai);
        float otherPower = otherSpell.getSpellPower(1, player);
        float otherMultiplier = otherSpell.getEntityPowerMultiplier(player);
        float original = spell.getEntityPowerMultiplier(player);
        try (var scope = SpellCastHooks.enter(snapshot(player, spell, scaling(1.5f, 1)), player)) {
            near(helper, spell.getEntityPowerMultiplier(player), original * 1.5f, "Scoped power multiplier");
            near(helper, spell.getSpellPower(1, ai), aiPower, "Unrelated AI power");
            near(helper, spell.getEntityPowerMultiplier(ai), aiMultiplier, "Unrelated AI multiplier");
            near(helper, otherSpell.getSpellPower(1, player), otherPower, "Unrelated spell power");
            near(helper, otherSpell.getEntityPowerMultiplier(player), otherMultiplier, "Unrelated spell multiplier");
        } finally {
            ai.discard();
        }
        near(helper, spell.getEntityPowerMultiplier(player), original, "Restored multiplier");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void delayedEffectHelpersKeepNativeBaseline(GameTestHelper helper) {
        var player = player(helper, "DelayedPower");
        for (var spell : List.of(SpellRegistry.FROSTBITE_SPELL.get(), SpellRegistry.ECHOING_STRIKES_SPELL.get(),
                SpellRegistry.THUNDERSTORM_SPELL.get())) {
            float original = spell.getEntityPowerMultiplier(player);
            try (var scope = SpellCastHooks.enter(preview(player, spell, scaling(1.5f, 1)))) {
                near(helper, spell.getEntityPowerMultiplier(player), original, "Delayed effect preview multiplier");
            }
            try (var scope = SpellCastHooks.enter(snapshot(player, spell, scaling(1.5f, 1)), player)) {
                near(helper, spell.getEntityPowerMultiplier(player), original, "Delayed effect execution multiplier");
            }
        }
        helper.succeed();
    }

    private static ServerPlayer player(GameTestHelper helper, String name) {
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "[" + name + "]"));
        player.setPos(helper.absoluteVec(new Vec3(1, 2, -5)));
        MagicData.getPlayerMagicData(player).getSyncedData();
        return player;
    }

    private static ReforgeCache.Data scaling(float power, float castTime) {
        return new ReforgeCache.Data(power, 1, 1, castTime, 0, 1, 1);
    }

    private static SpellCastHooks.Context preview(ServerPlayer player, AbstractSpell spell, ReforgeCache.Data data) {
        return new SpellCastHooks.Context(ItemStack.EMPTY, player, -1, 1, data, new SpellData(spell, 1));
    }

    private static SpellCastHooks.Snapshot snapshot(ServerPlayer player, AbstractSpell spell, ReforgeCache.Data data) {
        return new SpellCastHooks.Snapshot(player.getUUID(), spell.getSpellId(), 1, data, SpellEffects.NONE);
    }

    private static <T> T nativePower(ServerPlayer player, float multiplier, Supplier<T> action) {
        var attribute = player.getAttribute(AttributeRegistry.SPELL_POWER.get());
        var modifier = new AttributeModifier(UUID.randomUUID(), "apoth_test_power", multiplier - 1,
                AttributeModifier.Operation.MULTIPLY_TOTAL);
        attribute.addTransientModifier(modifier);
        try {
            return action.get();
        } finally {
            attribute.removeModifier(modifier);
        }
    }

    private static List<String> info(AbstractSpell spell, LivingEntity player) {
        return spell.getUniqueInfo(1, player).stream().map(component -> component.getString()).toList();
    }

    private static String info(AbstractSpell spell, LivingEntity player, String key) {
        for (var component : spell.getUniqueInfo(1, player)) {
            if (component.getContents() instanceof TranslatableContents contents && key.equals(contents.getKey())) {
                return String.valueOf(contents.getArgs()[0]);
            }
        }
        throw new AssertionError("Missing spell info: " + spell.getSpellId() + ' ' + key);
    }

    private static void near(GameTestHelper helper, float actual, float expected, String name) {
        helper.assertTrue(Math.abs(actual - expected) < 0.001f, name + ": actual=" + actual + " expected=" + expected);
    }

    private static <T extends Entity> List<T> capture(GameTestHelper helper, Class<T> type, List<Entity> cleanup, Runnable cast) {
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
            return spawned;
        } finally {
            MinecraftForge.EVENT_BUS.unregister(listener);
        }
    }
}
