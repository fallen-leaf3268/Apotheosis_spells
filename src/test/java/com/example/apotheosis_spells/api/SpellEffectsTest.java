package com.example.apotheosis_spells.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpellEffectsTest {
    private record TestModule(float value) implements SpellEffects.Module {
        private static final com.mojang.serialization.Codec<TestModule> CODEC =
                com.mojang.serialization.Codec.FLOAT.xmap(TestModule::new, TestModule::value);
        @Override public String type() { return "apotheosis_spells_test:custom"; }
        @Override public TestModule merge(SpellEffects.Module other) { return new TestModule(value + ((TestModule) other).value); }
        @Override public float modifyDamage(SpellEffects.DamageContext context, float amount) { return amount + value; }
    }

    @Test
    void customModuleRunsAndPersistsWithoutCentralEffectFields() {
        SpellEffects.register("apotheosis_spells_test:custom", TestModule.CODEC);
        var effects = SpellEffects.of(new TestModule(3)).merge(SpellEffects.of(new TestModule(4)));
        assertEquals(17, effects.modifyDamage(null, 10));
        assertEquals(effects, SpellEffects.read(effects.write()));
        assertEquals(17, SpellEffects.read(effects.write()).modifyDamage(null, 10));
        assertThrows(UnsupportedOperationException.class, () -> effects.modules().clear());
    }

    @Test
    void invalidOrMissingModuleDoesNotRemoveOtherFrozenEffects() {
        var tag = SpellEffects.ofEcho(0.3f).write();
        var list = tag.getList("modules", net.minecraft.nbt.Tag.TAG_COMPOUND);
        var unknown = new net.minecraft.nbt.CompoundTag();
        unknown.putString("type", "missing_mod:effect");
        list.add(unknown);
        var invalid = new net.minecraft.nbt.CompoundTag();
        invalid.putString("type", "apotheosis_spells:channel");
        invalid.putString("data", "invalid_module_data");
        list.add(invalid);
        assertEquals(SpellEffects.ofEcho(0.3f), SpellEffects.read(tag));
    }

    @Test
    void effectSnapshotStoresIndependentlyTypedModules() {
        SpellEffects effects = SpellEffects.ofManaLeech(0.2f).merge(SpellEffects.ofEcho(0.3f));
        var tag = effects.write();
        assertEquals(2, tag.getInt("version"));
        assertEquals(2, tag.getList("modules", net.minecraft.nbt.Tag.TAG_COMPOUND).size());
        assertEquals(effects, SpellEffects.read(tag));
    }

    @Test
    void legacySnapshotRetainsFrozenValuesAfterMigration() {
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putInt("version", 1);
        tag.putFloat("mana_leech", 0.17f);
        tag.putFloat("echo", 0.25f);
        tag.putFloat("shield", 8);
        var effects = SpellEffects.read(tag);
        assertEquals(0.17f, effects.manaLeech());
        assertEquals(0.25f, effects.echo());
        assertEquals(8, effects.shield());
        assertEquals(2, effects.write().getInt("version"));
        assertEquals(effects, SpellEffects.read(effects.write()));
    }

    @Test
    void unrelatedEffectDoesNotChangeExecuteThreshold() {
        SpellEffects effects = SpellEffects.ofExecute(0.2f, 50).merge(SpellEffects.ofEcho(0.1f));

        assertEquals(50, effects.executeThreshold());
        assertEquals(0.2f, effects.executeBonus(0.49f));
    }

    @Test
    void multipleExecuteThresholdsApplyOnlyTheirOwnBonuses() {
        SpellEffects effects = SpellEffects.ofExecute(0.1f, 50)
                .merge(SpellEffects.ofExecute(0.2f, 25));

        assertEquals(0f, effects.executeBonus(0.6f));
        assertEquals(0.1f, effects.executeBonus(0.4f));
        assertEquals(0.3f, effects.executeBonus(0.2f));
    }

    @Test
    void distinctPotionEffectsAreRetainedInImmutableLists() {
        SpellEffects effects = SpellEffects.ofPostcast(1, 100, "minecraft:regeneration")
                .merge(SpellEffects.ofPostcast(2, 80, "minecraft:speed"));

        assertEquals(List.of(
                new SpellEffects.Potion("minecraft:regeneration", 100, 0),
                new SpellEffects.Potion("minecraft:speed", 80, 1)), effects.postcastEffects());
        assertThrows(UnsupportedOperationException.class,
                () -> effects.postcastEffects().add(new SpellEffects.Potion("minecraft:haste", 20, 0)));
    }

    @Test
    void instantPotionDurationMergeDoesNotDependOnOrder() {
        var shortEffect = new SpellEffects.Potion("minecraft:resistance", 10, 0, 60);
        var longEffect = new SpellEffects.Potion("minecraft:resistance", 10, 0, 100);
        assertEquals(List.of(longEffect), SpellEffects.normalizePotions(List.of(shortEffect, longEffect)));
        assertEquals(List.of(longEffect), SpellEffects.normalizePotions(List.of(longEffect, shortEffect)));
    }

    @Test
    void samePotionIdKeepsOneWholeExistingConfiguration() {
        SpellEffects effects = SpellEffects.ofPostcast(1, 200, "minecraft:speed")
                .merge(SpellEffects.ofPostcast(2, 40, "minecraft:speed"));

        assertEquals(List.of(new SpellEffects.Potion("minecraft:speed", 40, 1)), effects.postcastEffects());
    }

    @Test
    void signaturesKeepAllFieldsTogetherIncludingZeroChance() {
        SpellEffects.Signature blood = new SpellEffects.Signature(
                1, 0, "ATTACKER", "minecraft:regeneration", 60, 0, 0.3f);
        SpellEffects.Signature lightning = new SpellEffects.Signature(
                2, 0, "TARGET", "minecraft:mining_fatigue", 80, 1, 0f);
        SpellEffects effects = SpellEffects.ofSchoolSignature(
                        blood.school(), blood.value(), blood.target(), blood.effect(), blood.duration(), blood.amplifier(), blood.chance())
                .merge(SpellEffects.ofSchoolSignature(
                        lightning.school(), lightning.value(), lightning.target(), lightning.effect(), lightning.duration(), lightning.amplifier(), lightning.chance()));

        assertEquals(List.of(blood, lightning), effects.hitSignatures());
    }

    @Test
    void nbtRoundTripPreservesCompleteValue() {
        SpellEffects effects = SpellEffects.ofManaLeech(0.2f)
                .merge(SpellEffects.ofExecute(0.15f, 50))
                .merge(SpellEffects.ofChannel(2, "minecraft:resistance"))
                .merge(SpellEffects.ofPostcast(1, 100, "minecraft:speed"))
                .merge(SpellEffects.ofSchoolSignature(
                        1, 0, "ATTACKER", "minecraft:regeneration", 60, 0, 0.3f));

        assertEquals(effects, SpellEffects.read(effects.write()));
    }
}
