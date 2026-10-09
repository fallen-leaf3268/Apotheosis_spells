package com.example.apotheosis_spells.handler;

import net.minecraft.world.entity.LivingEntity;

public final class SiphoningBeamRange {
    public static final String SPELL_ID = "irons_spellbooks:ray_of_siphoning";

    private SiphoningBeamRange() {}

    public interface Data {
        float apoth$getSiphoningRange();
        void apoth$setSiphoningRange(float range);
    }

    public static float renderedRange(LivingEntity caster, float original) {
        if (caster instanceof Data data) {
            float range = data.apoth$getSiphoningRange();
            if (range > 0 && Float.isFinite(range)) return range;
        }
        return original;
    }
}
