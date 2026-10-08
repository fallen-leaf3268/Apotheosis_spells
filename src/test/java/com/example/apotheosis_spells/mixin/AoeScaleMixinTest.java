package com.example.apotheosis_spells.mixin;

import org.junit.jupiter.api.Test;

import net.minecraft.world.entity.LivingEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AoeScaleMixinTest {
    @Test
    void spellGetterSignaturesMatchTheRuntimeHooks() throws NoSuchMethodException {
        assertEquals(float.class, io.redspace.ironsspellbooks.spells.fire.ScorchSpell.class
                .getDeclaredMethod("getRadius", LivingEntity.class).getReturnType());
        for (var type : java.util.List.of(io.redspace.ironsspellbooks.spells.ender.BlackHoleSpell.class,
                io.redspace.ironsspellbooks.spells.holy.HealingCircleSpell.class,
                io.redspace.ironsspellbooks.spells.nature.EarthquakeSpell.class)) {
            assertEquals(float.class, type.getDeclaredMethod("getRadius", int.class, LivingEntity.class).getReturnType());
        }
        for (var type : java.util.List.of(io.redspace.ironsspellbooks.spells.nature.RootSpell.class,
                io.redspace.ironsspellbooks.spells.nature.PoisonSplashSpell.class,
                io.redspace.ironsspellbooks.spells.holy.HealingCircleSpell.class)) {
            assertEquals(int.class, type.getDeclaredMethod("getDuration", int.class, LivingEntity.class).getReturnType());
        }
    }
}
