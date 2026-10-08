package com.example.apotheosis_spells.affix.spell;

import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.api.SpellEffects;
import com.example.apotheosis_spells.affix.SpellAffix;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixType;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.List;
import java.util.Set;

public class ExecuteAffix extends SpellAffix {
    public record Effect(List<SpellEffects.Execution> values) implements SpellEffects.Module {
        public static final Codec<Effect> CODEC = RecordCodecBuilder.create(i -> i.group(
                SpellEffects.Execution.CODEC.listOf().fieldOf("values").forGetter(Effect::values)
        ).apply(i, Effect::new));

        public Effect { values = SpellEffects.normalizeExecutions(values); }

        @Override public String type() { return "apotheosis_spells:execute"; }
        @Override public int order() { return 0; }
        @Override public boolean isEmpty() { return values.isEmpty(); }
        @Override public Effect merge(SpellEffects.Module other) {
            return new Effect(SpellEffects.joined(values, ((Effect) other).values));
        }

        public float bonus(float healthRatio) {
            if (!Float.isFinite(healthRatio)) return 0;
            float result = 0;
            for (var execution : values) {
                if (healthRatio < execution.threshold() / 100f) result += execution.bonus();
            }
            return SpellEffects.nonNegativeFinite(result);
        }

        @Override
        public float modifyDamage(SpellEffects.DamageContext context, float amount) {
            var target = context.target();
            return target.getMaxHealth() > 0 ? amount * (1f + bonus(target.getHealth() / target.getMaxHealth())) : amount;
        }
    }

    public static final Codec<ExecuteAffix> C = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("modifier").forGetter(a -> a.mod),
            Codec.unboundedMap(Codec.STRING, Fn.C).fieldOf("values").forGetter(a -> a.vals),
            Codec.STRING.listOf().xmap(Set::copyOf, s -> s.stream().toList()).fieldOf("types").forGetter(a -> a.types),
            Codec.unboundedMap(Codec.STRING, Codec.intRange(0, 100)).fieldOf("thresholds").forGetter(a -> a.thresholds)
    ).apply(i, ExecuteAffix::new));

    private final Map<String, Integer> thresholds;

    public ExecuteAffix(String m, Map<String, Fn> v, Set<String> t, Map<String, Integer> thresholds) {
        super(m, v, t, AffixType.ABILITY);
        this.thresholds = Map.copyOf(thresholds);
    }

    @Override
    public ReforgeCache.Data contribute(int baseValue) { return ReforgeCache.Data.DEF; }

    @Override
    public SpellEffects contributeEffect(int baseValue) {
        return SpellEffects.ofExecute(baseValue / 100f, SpellEffects.DEF_EXECUTE_THRESHOLD);
    }

    @Override
    public SpellEffects contributeEffect(LootRarity rarity, int baseValue) {
        return SpellEffects.ofExecute(baseValue / 100f, getThresholdByRarity(rarity));
    }

    public int getThresholdByRarity(LootRarity rarity) {
        Integer t = thresholds.get(rarityKey(rarity));
        if (t != null) return Math.max(0, Math.min(100, t));
        return SpellEffects.DEF_EXECUTE_THRESHOLD;
    }

    @Override
    public MutableComponent getDescription(ItemStack stack, LootRarity rarity, float level) {
        int v = displayValue(getBaseValue(rarity, level));
        int th = getThresholdByRarity(rarity);
        return Component.translatable(getModifierKey(), th, String.valueOf(v));
    }

    @Override
    protected Codec<? extends SpellAffix> getSelfCodec() { return C; }

    @Override
    protected int displayValue(int value) { return Math.max(0, value); }
}
