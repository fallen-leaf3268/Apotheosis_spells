package com.example.apotheosis_spells.affix.spell;

import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.api.SpellEffects;
import com.example.apotheosis_spells.affix.SpellAffix;
import com.example.apotheosis_spells.ApotheosisSpells.Diagnostics;
import com.example.apotheosis_spells.handler.SpellCastHooks;
import com.example.apotheosis_spells.handler.SpellEffectHandler;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixType;
import io.redspace.ironsspellbooks.api.magic.MagicData;

import java.util.Map;
import java.util.Set;

/** 法力虹吸：法术伤害的 N% 回复法力（事件类，SpellDamageEvent 结算）。 */
public class ManaStealAffix extends SpellAffix {
    public record Effect(float value) implements SpellEffects.Module {
        public static final Codec<Effect> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("value").forGetter(Effect::value)
        ).apply(i, Effect::new));

        public Effect { value = SpellEffects.nonNegativeFinite(value); }

        @Override public String type() { return "apotheosis_spells:mana_leech"; }
        @Override public int order() { return 0; }
        @Override public boolean isEmpty() { return value == 0; }
        @Override public Effect merge(SpellEffects.Module other) { return new Effect(value + ((Effect) other).value); }

        @Override
        public void afterDamage(SpellEffects.DamageContext context) {
            if (value <= 0) return;
            var player = context.player();
            var magic = MagicData.getPlayerMagicData(player);
            float before = magic.getMana();
            SpellEffectHandler.restoreMana(player, context.damage() * value);
            var snapshot = SpellCastHooks.forDamage(context.source());
            String origin = snapshot != null ? snapshot.spellId()
                    : context.source().spell() == null ? "NONE" : context.source().spell().getSpellId();
            Diagnostics.log("MANA_LEECH", () -> Diagnostics.player(player)
                    + " originSpell=" + origin + " settledDamage=" + context.damage()
                    + " ratio=" + value + " mana=" + before + "->" + magic.getMana());
        }
    }

    public static final Codec<ManaStealAffix> C = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("modifier").forGetter(a -> a.mod),
            Codec.unboundedMap(Codec.STRING, Fn.C).fieldOf("values").forGetter(a -> a.vals),
            Codec.STRING.listOf().xmap(Set::copyOf, s -> s.stream().toList()).fieldOf("types").forGetter(a -> a.types)
    ).apply(i, ManaStealAffix::new));

    public ManaStealAffix(String m, Map<String, Fn> v, Set<String> t) { super(m, v, t, AffixType.ABILITY); }

    @Override
    public ReforgeCache.Data contribute(int baseValue) { return ReforgeCache.Data.DEF; }

    @Override
    public SpellEffects contributeEffect(int baseValue) { return SpellEffects.ofManaLeech(baseValue / 100f); }

    @Override
    protected Codec<? extends SpellAffix> getSelfCodec() { return C; }

    @Override
    protected int displayValue(int value) { return Math.max(0, value); }
}
