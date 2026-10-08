package com.example.apotheosis_spells.handler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpellEffectHandlerTest {
    @Test
    void nestedSettlementRestoresOuterValueAfterExceptionAndClearsOnClose() {
        var outer = new SpellEffectHandler.DamageSettlement(null, null);
        try (outer) {
            SpellEffectHandler.recordSettledDamage(null, null, 3);
            assertThrows(IllegalStateException.class, () -> {
                try (var inner = new SpellEffectHandler.DamageSettlement(null, null)) {
                    SpellEffectHandler.recordSettledDamage(null, null, 7);
                    assertEquals(7, inner.amount(true));
                    throw new IllegalStateException();
                }
            });
            assertEquals(3, outer.amount(true));
            SpellEffectHandler.recordSettledDamage(null, null, 5);
            assertEquals(5, outer.amount(true));
        }
        SpellEffectHandler.recordSettledDamage(null, null, 99);
        assertEquals(5, outer.amount(true));
    }

    @Test
    void rejectedDamageNeverGrantsOnHitEffects() {
        assertEquals(0, SpellEffectHandler.settledDamage(false, 20));
    }

    @Test
    void damageUsesFinitePositiveSettlement() {
        assertEquals(20, SpellEffectHandler.settledDamage(true, 20));
        assertEquals(0, SpellEffectHandler.settledDamage(true, 0));
        assertEquals(0, SpellEffectHandler.settledDamage(true, -1));
        assertEquals(0, SpellEffectHandler.settledDamage(true, Float.NaN));
        assertEquals(0, SpellEffectHandler.settledDamage(true, Float.POSITIVE_INFINITY));
    }

    @Test
    void refundChangesCostWithoutDependingOnCurrentMana() {
        assertEquals(20, SpellEffectHandler.discountedCost(40, 0.5f));
        assertEquals(0, SpellEffectHandler.discountedCost(0, 0.5f));
        assertEquals(1, SpellEffectHandler.discountedCost(1, 0.5f));
    }
}
