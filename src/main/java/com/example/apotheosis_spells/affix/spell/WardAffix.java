package com.example.apotheosis_spells.affix.spell;

import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.api.SpellEffects;
import com.example.apotheosis_spells.affix.SpellAffix;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixType;

import java.util.Map;
import java.util.Set;

/** 施法护盾：每次施法刷新 N 点吸收护盾（事件类，SpellOnCastEvent 结算）。 */
public class WardAffix extends SpellAffix {
    public record Effect(float value) implements SpellEffects.Module {
        public static final Codec<Effect> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("value").forGetter(Effect::value)
        ).apply(i, Effect::new));

        public Effect { value = SpellEffects.nonNegativeFinite(value); }

        @Override public String type() { return "apotheosis_spells:ward"; }
        @Override public int order() { return 0; }
        @Override public boolean isEmpty() { return value == 0; }
        @Override public Effect merge(SpellEffects.Module other) { return new Effect(value + ((Effect) other).value); }

        @Override
        public void afterCast(SpellEffects.CastContext context) {
            if (value > context.player().getAbsorptionAmount()) context.player().setAbsorptionAmount(value);
        }
    }

    public static final Codec<WardAffix> C = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("modifier").forGetter(a -> a.mod),
            Codec.unboundedMap(Codec.STRING, Fn.C).fieldOf("values").forGetter(a -> a.vals),
            Codec.STRING.listOf().xmap(Set::copyOf, s -> s.stream().toList()).fieldOf("types").forGetter(a -> a.types)
    ).apply(i, WardAffix::new));

    public WardAffix(String m, Map<String, Fn> v, Set<String> t) { super(m, v, t, AffixType.ABILITY); }

    @Override
    public ReforgeCache.Data contribute(int baseValue) { return ReforgeCache.Data.DEF; }

    @Override
    public SpellEffects contributeEffect(int baseValue) { return SpellEffects.ofShield(baseValue); }

    @Override
    protected Codec<? extends SpellAffix> getSelfCodec() { return C; }

    @Override
    protected int displayValue(int value) { return Math.max(0, value); }
}
