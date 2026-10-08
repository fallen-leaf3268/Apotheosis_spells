package com.example.apotheosis_spells.affix.spell;

import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.api.SpellEffects;
import com.example.apotheosis_spells.affix.SpellAffix;
import com.example.apotheosis_spells.ApotheosisSpells.Diagnostics;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixType;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Set;

/** 免冷却：N% 几率本次施法不进入冷却（事件类，MagicManagerMixin 拦 addCooldown）。 */
public class CdSkipAffix extends SpellAffix {
    public record Effect(float chance) implements SpellEffects.Module {
        public static final Codec<Effect> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("chance").forGetter(Effect::chance)
        ).apply(i, Effect::new));

        public Effect { chance = SpellEffects.clamp01(chance); }

        @Override public String type() { return "apotheosis_spells:cd_skip"; }
        @Override public boolean isEmpty() { return chance == 0; }
        @Override public Effect merge(SpellEffects.Module other) { return new Effect(chance + ((Effect) other).chance); }

        @Override
        public boolean skipCooldown(ServerPlayer player, AbstractSpell spell) {
            float roll = player.getRandom().nextFloat();
            boolean cancelled = roll < chance;
            Diagnostics.log("COOLDOWN_ROLL", () -> Diagnostics.player(player) + " spell=" + spell.getSpellId()
                    + " chance=" + chance + " roll=" + roll + " cancelled=" + cancelled);
            return cancelled;
        }
    }

    public static final Codec<CdSkipAffix> C = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("modifier").forGetter(a -> a.mod),
            Codec.unboundedMap(Codec.STRING, Fn.C).fieldOf("values").forGetter(a -> a.vals),
            Codec.STRING.listOf().xmap(Set::copyOf, s -> s.stream().toList()).fieldOf("types").forGetter(a -> a.types)
    ).apply(i, CdSkipAffix::new));

    public CdSkipAffix(String m, Map<String, Fn> v, Set<String> t) { super(m, v, t, AffixType.ABILITY); }

    @Override
    public ReforgeCache.Data contribute(int baseValue) { return ReforgeCache.Data.DEF; }

    @Override
    public SpellEffects contributeEffect(int baseValue) { return SpellEffects.ofCdSkip(baseValue / 100f); }

    @Override
    protected Codec<? extends SpellAffix> getSelfCodec() { return C; }

    @Override
    protected int displayValue(int value) { return Math.max(0, Math.min(100, value)); }
}
