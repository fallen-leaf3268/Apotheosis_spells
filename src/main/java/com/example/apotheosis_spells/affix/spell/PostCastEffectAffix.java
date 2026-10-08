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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.List;
import java.util.Set;
import static dev.shadowsoffire.placebo.codec.PlaceboCodecs.nullableField;

public class PostCastEffectAffix extends SpellAffix {
    public record Effect(List<SpellEffects.Potion> potions, int haste) implements SpellEffects.Module {
        public static final Codec<Effect> CODEC = RecordCodecBuilder.create(i -> i.group(
                SpellEffects.Potion.CODEC.listOf().fieldOf("potions").forGetter(Effect::potions),
                Codec.INT.optionalFieldOf("haste", 0).forGetter(Effect::haste)
        ).apply(i, Effect::new));

        public Effect {
            potions = SpellEffects.normalizePotions(potions == null ? List.of()
                    : potions.stream().filter(potion -> potion != null && potion.chance() > 0).toList());
            haste = Math.max(0, haste);
        }

        @Override public String type() { return "apotheosis_spells:postcast"; }
        @Override public int order() { return 8; }
        @Override public boolean isEmpty() { return potions.isEmpty() && haste == 0; }
        @Override public Effect merge(SpellEffects.Module other) {
            var effect = (Effect) other;
            return new Effect(SpellEffects.joined(potions, effect.potions), Math.max(haste, effect.haste));
        }

        @Override
        public void afterCast(SpellEffects.CastContext context) {
            if (haste > 0) SpellEffectHandler.applyEffect(context.player(), "minecraft:speed", 100, haste - 1);
        }

        @Override
        public void afterCompletion(ServerPlayer player) {
            for (var potion : potions) {
                if (potion.chance() > 0 && (potion.chance() >= 1 || player.getRandom().nextFloat() < potion.chance())) {
                    SpellEffectHandler.applyEffect(player, potion.effect(), potion.duration(), potion.amplifier());
                }
            }
        }
    }

    public static final int DEFAULT_DURATION = 100;

    private static final Codec<String> EFFECT_CODEC = Codec.STRING.comapFlatMap(
            value -> ResourceLocation.tryParse(value) != null
                    ? DataResult.success(value)
                    : DataResult.error(() -> "Invalid effect id: " + value),
            value -> value);

    public static final Codec<PostCastEffectAffix> C = RecordCodecBuilder.create(i -> i.group(
            Values.codec("value", "amplifier", "duration", "chance").fieldOf("values").forGetter(a -> a.parameters),
            Codec.STRING.listOf().xmap(Set::copyOf, s -> s.stream().toList()).fieldOf("types").forGetter(a -> a.types),
            nullableField(EFFECT_CODEC, "effect", SpellEffects.DEF_POSTCAST_EFFECT).forGetter(a -> a.effect),
            nullableField(strictIntRange(1, Integer.MAX_VALUE), "duration", DEFAULT_DURATION).forGetter(a -> a.duration)
    ).apply(i, PostCastEffectAffix::new));

    private final String effect;
    private final Values parameters;
    private final int duration;

    public PostCastEffectAffix(Map<String, Fn> v, Set<String> t, String effect, int duration) {
        this(Values.legacy(v), t, effect, duration);
    }

    private PostCastEffectAffix(Values v, Set<String> t, String effect, int duration) {
        super("postcast_effect", v.scalars(), t, AffixType.ABILITY);
        if (ResourceLocation.tryParse(effect) == null) throw new IllegalArgumentException("Invalid effect id: " + effect);
        if (duration <= 0) throw new IllegalArgumentException("Duration must be positive");
        this.effect = effect;
        this.parameters = v;
        this.duration = duration;
    }

    @Override
    public ReforgeCache.Data contribute(int baseValue) { return ReforgeCache.Data.DEF; }

    @Override
    public SpellEffects contributeEffect(int baseValue) {
        return SpellEffects.ofPostcast(baseValue, duration, effect);
    }

    @Override
    public SpellEffects resolveEffects(LootRarity rarity, float level) {
        var potion = resolvePotion(rarity, level);
        return potion == null ? SpellEffects.NONE : SpellEffects.of(new Effect(List.of(potion), 0));
    }

    private SpellEffects.Potion resolvePotion(LootRarity rarity, float level) {
        int value = parameters.has(rarity, "value") ? getBaseValue(rarity, level) : 1;
        if (!parameters.has(rarity, "amplifier") && value <= 0) return null;
        return new SpellEffects.Potion(effect, parameters.getInt(rarity, level, "duration", duration),
                parameters.getInt(rarity, level, "amplifier", value - 1), 60,
                parameters.get(rarity, level, "chance", 1));
    }

    @Override
    public MutableComponent getDescription(ItemStack stack, LootRarity rarity, float level) {
        var potion = resolvePotion(rarity, level);
        int amplifier = potion == null ? 0 : potion.amplifier();
        return describePotion(potion, ChannelEffectAffix.effectComponent(effect, amplifier));
    }

    private MutableComponent describePotion(SpellEffects.Potion potion, MutableComponent name) {
        int ticks = potion == null ? 0 : potion.duration();
        float chance = potion == null ? 0 : potion.chance();
        return chance >= 1 ? Component.translatable(getModifierKey(), name, ChannelEffectAffix.seconds(ticks))
                : Component.translatable(getModifierKey() + ".chance", name, ChannelEffectAffix.seconds(ticks),
                        new java.math.BigDecimal(Float.toString(chance)).movePointRight(2).stripTrailingZeros().toPlainString());
    }

    @Override
    public Component getAugmentingText(ItemStack stack, LootRarity rarity, float level) {
        var potion = resolvePotion(rarity, level);
        var name = ChannelEffectAffix.effectComponent(effect, potion == null ? 0 : potion.amplifier());
        var min = resolvePotion(rarity, 0);
        var max = resolvePotion(rarity, 1);
        ChannelEffectAffix.appendPotionLevelBounds(name, min == null ? -1 : min.amplifier(), max == null ? -1 : max.amplifier());
        var text = describePotion(potion, name);
        appendBounds(text, ChannelEffectAffix.seconds(min == null ? 0 : min.duration()) + "s",
                ChannelEffectAffix.seconds(max == null ? 0 : max.duration()) + "s");
        appendBounds(text, SchoolSignatureAffix.percent(min == null ? 0 : min.chance()) + "%",
                SchoolSignatureAffix.percent(max == null ? 0 : max.chance()) + "%");
        return text;
    }

    @Override
    protected Codec<? extends SpellAffix> getSelfCodec() { return C; }
}
