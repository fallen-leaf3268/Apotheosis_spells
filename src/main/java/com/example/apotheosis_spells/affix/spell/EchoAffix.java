package com.example.apotheosis_spells.affix.spell;

import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.api.SpellEffects;
import com.example.apotheosis_spells.affix.SpellAffix;
import com.example.apotheosis_spells.handler.SpellEffectHandler;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixType;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Set;

public class EchoAffix extends SpellAffix {
    public record Effect(float chance) implements SpellEffects.Module {
        public static final Codec<Effect> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("chance").forGetter(Effect::chance)
        ).apply(i, Effect::new));

        public Effect { chance = SpellEffects.clamp01(chance); }

        @Override public String type() { return "apotheosis_spells:echo"; }
        @Override public int order() { return 20; }
        @Override public boolean isEmpty() { return chance == 0; }
        @Override public Effect merge(SpellEffects.Module other) { return new Effect(chance + ((Effect) other).chance); }

        @Override
        public void afterCast(SpellEffects.CastContext context) {
            SpellEffectHandler.queueEcho(context, chance);
        }
    }

    public static final Codec<EchoAffix> C = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("modifier").forGetter(a -> a.mod),
            Codec.unboundedMap(Codec.STRING, Fn.C).fieldOf("values").forGetter(a -> a.vals),
            Codec.STRING.listOf().xmap(Set::copyOf, s -> s.stream().toList()).fieldOf("types").forGetter(a -> a.types)
    ).apply(i, EchoAffix::new));

    public EchoAffix(String m, Map<String, Fn> v, Set<String> t) { super(m, v, t, AffixType.ABILITY); }

    @Override
    public boolean canApplyTo(ItemStack stack, LootCategory cat, LootRarity rarity) {
        return super.canApplyTo(stack, cat, rarity) && (stack.isEmpty() || supportsEcho(spellFrom(stack)));
    }

    @Override
    protected boolean requiresSupportedSpell() { return true; }

    @Override
    public ReforgeCache.Data contribute(int baseValue) { return ReforgeCache.Data.DEF; }

    @Override
    public SpellEffects contributeEffect(int baseValue) { return SpellEffects.ofEcho(baseValue / 100f); }

    @Override
    protected Codec<? extends SpellAffix> getSelfCodec() { return C; }

    @Override
    protected int displayValue(int value) { return Math.max(0, Math.min(100, value)); }
}
