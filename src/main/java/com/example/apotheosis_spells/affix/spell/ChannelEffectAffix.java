package com.example.apotheosis_spells.affix.spell;

import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.api.SpellEffects;
import com.example.apotheosis_spells.affix.SpellAffix;
import com.example.apotheosis_spells.handler.SpellEffectHandler;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixType;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import io.redspace.ironsspellbooks.api.spells.CastType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.List;
import java.util.Set;
import static dev.shadowsoffire.placebo.codec.PlaceboCodecs.nullableField;

public class ChannelEffectAffix extends SpellAffix {
    public record Effect(List<SpellEffects.Potion> potions) implements SpellEffects.Module {
        public static final Codec<Effect> CODEC = RecordCodecBuilder.create(i -> i.group(
                SpellEffects.Potion.CODEC.listOf().fieldOf("potions").forGetter(Effect::potions)
        ).apply(i, Effect::new));

        public Effect {
            potions = SpellEffects.normalizePotions(potions).stream().map(potion ->
                    new SpellEffects.Potion(potion.effect(), SpellEffects.DEF_CHANNEL_DURATION, potion.amplifier(), 0)).toList();
        }

        @Override public String type() { return "apotheosis_spells:channel"; }
        @Override public int order() { return 5; }
        @Override public boolean isEmpty() { return potions.isEmpty(); }
        @Override public Effect merge(SpellEffects.Module other) {
            return new Effect(SpellEffects.joined(potions, ((Effect) other).potions));
        }

        @Override
        public void duringCast(ServerPlayer player) {
            for (var potion : potions) SpellEffectHandler.applyEffect(player, potion.effect(), potion.duration(), potion.amplifier());
        }

    }

    private static final Codec<String> EFFECT_CODEC = Codec.STRING.comapFlatMap(
            value -> ResourceLocation.tryParse(value) != null
                    ? DataResult.success(value)
                    : DataResult.error(() -> "Invalid effect id: " + value),
            value -> value);

    public static final Codec<ChannelEffectAffix> C = RecordCodecBuilder.create(i -> i.group(
            Values.codec("value", "amplifier", "duration", "instant_duration").fieldOf("values").forGetter(a -> a.parameters),
            Codec.STRING.listOf().xmap(Set::copyOf, s -> s.stream().toList()).fieldOf("types").forGetter(a -> a.types),
            nullableField(EFFECT_CODEC, "effect", SpellEffects.DEF_CHANNEL_EFFECT).forGetter(a -> a.effect)
    ).apply(i, ChannelEffectAffix::new));

    private final String effect;
    private final Values parameters;

    public ChannelEffectAffix(Map<String, Fn> v, Set<String> t, String effect) {
        this(Values.legacy(v), t, effect);
    }

    public ChannelEffectAffix(Map<String, Fn> v, Set<String> t, String effect, int duration, int instantDuration) {
        this(v, t, effect);
    }

    private ChannelEffectAffix(Values v, Set<String> t, String effect) {
        super("channel_effect", v.scalars(), t, AffixType.ABILITY);
        if (ResourceLocation.tryParse(effect) == null) throw new IllegalArgumentException("Invalid effect id: " + effect);
        this.effect = effect;
        var entries = new java.util.HashMap<String, Map<String, Fn>>();
        v.entries().forEach((rarity, fields) -> {
            var retained = new java.util.HashMap<String, Fn>();
            for (String key : List.of("value", "amplifier")) {
                if (fields.containsKey(key)) retained.put(key, fields.get(key));
            }
            if (retained.isEmpty()) retained.put("amplifier", new Fn(0, 1, 0));
            entries.put(rarity, retained);
        });
        this.parameters = new Values(entries);
    }

    @Override
    public boolean canApplyTo(ItemStack stack, LootCategory cat, LootRarity rarity) {
        if (!super.canApplyTo(stack, cat, rarity)) return false;
        if (stack.isEmpty()) return true;
        var spell = spellFrom(stack);
        return spell != null && (spell.getCastType() == CastType.LONG || spell.getCastType() == CastType.CONTINUOUS);
    }

    @Override
    public ReforgeCache.Data contribute(int baseValue) { return ReforgeCache.Data.DEF; }

    @Override
    public SpellEffects contributeEffect(int baseValue) {
        return baseValue <= 0 ? SpellEffects.NONE : SpellEffects.of(new Effect(List.of(
                new SpellEffects.Potion(effect, SpellEffects.DEF_CHANNEL_DURATION, baseValue - 1, 0))));
    }

    @Override
    public SpellEffects resolveEffects(LootRarity rarity, float level) {
        var potion = resolvePotion(rarity, level);
        return potion == null ? SpellEffects.NONE : SpellEffects.of(new Effect(List.of(potion)));
    }

    private SpellEffects.Potion resolvePotion(LootRarity rarity, float level) {
        int value = parameters.has(rarity, "value") ? getBaseValue(rarity, level) : 1;
        if (!parameters.has(rarity, "amplifier") && value <= 0) return null;
        return new SpellEffects.Potion(effect, SpellEffects.DEF_CHANNEL_DURATION,
                parameters.getInt(rarity, level, "amplifier", value - 1), 0);
    }

    @Override
    public MutableComponent getDescription(ItemStack stack, LootRarity rarity, float level) {
        var potion = resolvePotion(rarity, level);
        int amplifier = potion == null ? 0 : potion.amplifier();
        return Component.translatable(getModifierKey(), effectComponent(effect, amplifier));
    }

    @Override
    public Component getAugmentingText(ItemStack stack, LootRarity rarity, float level) {
        var potion = resolvePotion(rarity, level);
        var name = effectComponent(effect, potion == null ? 0 : potion.amplifier());
        var min = resolvePotion(rarity, 0);
        var max = resolvePotion(rarity, 1);
        appendPotionLevelBounds(name, min == null ? -1 : min.amplifier(), max == null ? -1 : max.amplifier());
        return Component.translatable(getModifierKey(), name);
    }

    static void appendPotionLevelBounds(MutableComponent text, int min, int max) {
        if (min != max) text.append(valueBounds(potencyComponent(min), potencyComponent(max)));
    }

    private static MutableComponent potencyComponent(int amplifier) {
        if (amplifier < 0) return Component.literal("0");
        if (amplifier == 0) return Component.literal("I");
        return amplifier <= 9 ? Component.translatable("potion.potency." + amplifier)
                : Component.literal(Long.toString((long) amplifier + 1));
    }

    static MutableComponent effectComponent(String effectId, int amplifier) {
        ResourceLocation id = ResourceLocation.tryParse(effectId);
        MobEffect eff = id == null ? null : BuiltInRegistries.MOB_EFFECT.get(id);
        MutableComponent name = eff != null
                ? Component.translatable(eff.getDescriptionId())
                : Component.literal(effectId);
        if (amplifier > 0) {
            name = Component.translatable("potion.withAmplifier", name,
                    potencyComponent(amplifier));
        }
        if (eff != null) name = name.withStyle(eff.getCategory().getTooltipFormatting());
        return name;
    }

    static String seconds(int ticks) {
        return java.math.BigDecimal.valueOf(ticks).divide(java.math.BigDecimal.valueOf(20)).stripTrailingZeros().toPlainString();
    }

    @Override
    protected Codec<? extends SpellAffix> getSelfCodec() { return C; }
}
