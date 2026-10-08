package com.example.apotheosis_spells.affix.spell;

import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.api.Schools;
import com.example.apotheosis_spells.api.SpellEffects;
import com.example.apotheosis_spells.handler.SpellEffectHandler;
import com.example.apotheosis_spells.affix.SpellAffix;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixType;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.item.Scroll;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.MobType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import static dev.shadowsoffire.placebo.codec.PlaceboCodecs.nullableField;

public class SchoolSignatureAffix extends SpellAffix {
    private static final Set<String> ACTIONS = Set.of("potion", "undead_damage", "blink", "mana_refund", "low_health_damage", "none");
    private static final Codec<String> ACTION_CODEC = Codec.STRING.comapFlatMap(
            value -> ACTIONS.contains(value) ? DataResult.success(value)
                    : DataResult.error(() -> "Unknown signature action: " + value), value -> value);
    private static final Codec<Float> AMOUNT_CODEC = Codec.FLOAT.flatXmap(
            SchoolSignatureAffix::validAmount, SchoolSignatureAffix::validAmount);
    private static final Codec<String> SCHOOL_CODEC = Codec.STRING.comapFlatMap(
            value -> !value.isEmpty() && Schools.idFromName(value) == 0
                    ? DataResult.error(() -> "Unknown spell school: " + value)
                    : DataResult.success(value.toLowerCase(Locale.ROOT)),
            value -> value);
    private static final Codec<String> TARGET_CODEC = Codec.STRING.comapFlatMap(
            value -> {
                String normalized = value.toUpperCase(Locale.ROOT);
                return SpellEffects.SIG_TARGET_TARGET.equals(normalized)
                        || SpellEffects.SIG_TARGET_ATTACKER.equals(normalized)
                        ? DataResult.success(normalized)
                        : DataResult.error(() -> "Invalid signature target: " + value);
            },
            value -> value);
    private static final Codec<String> EFFECT_CODEC = Codec.STRING.comapFlatMap(
            value -> value.isEmpty() || ResourceLocation.tryParse(value) != null
                    ? DataResult.success(value)
                    : DataResult.error(() -> "Invalid effect id: " + value),
            value -> value);
    private static final Codec<Float> CHANCE_CODEC = Codec.FLOAT.flatXmap(
            SchoolSignatureAffix::validChance, SchoolSignatureAffix::validChance);

    public static final Codec<SchoolSignatureAffix> C = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("modifier").forGetter(a -> a.mod),
            Values.codec("value", "duration", "amplifier", "chance", "amount", "distance").fieldOf("values").forGetter(a -> a.parameters),
            Codec.STRING.listOf().xmap(list -> Set.copyOf(list), set -> set.stream().toList()).fieldOf("types").forGetter(a -> a.types),
            nullableField(SCHOOL_CODEC, "school", "").forGetter(a -> a.school),
            nullableField(TARGET_CODEC, "target", SpellEffects.SIG_TARGET_TARGET).forGetter(a -> a.target),
            nullableField(EFFECT_CODEC, "effect", "").forGetter(a -> a.effect),
            nullableField(strictIntRange(0, Integer.MAX_VALUE), "duration", 0).forGetter(a -> a.duration),
            nullableField(strictIntRange(0, Integer.MAX_VALUE), "amplifier", 0).forGetter(a -> a.amplifier),
            nullableField(CHANCE_CODEC, "chance", 1f).forGetter(a -> a.chance),
            nullableField(ACTION_CODEC, "action").forGetter(a -> Optional.of(a.action)),
            nullableField(AMOUNT_CODEC, "amount").forGetter(a -> Optional.of(a.amount)),
            nullableField(strictIntRange(1, 128), "distance", 5).forGetter(a -> a.distance)
    ).apply(i, SchoolSignatureAffix::new));

    private final String school;
    private final Values parameters;
    private final int schoolId;
    private final String target;
    private final String effect;
    private final int duration;
    private final int amplifier;
    private final float chance;
    private final String action;
    private final float amount;
    private final int distance;

    private static DataResult<Float> validAmount(float value) {
        return Float.isFinite(value) && value >= 0 ? DataResult.success(value)
                : DataResult.error(() -> "Amount must be finite and nonnegative");
    }

    private static DataResult<Float> validChance(float value) {
        return Float.isFinite(value) && value >= 0 && value <= 1
                ? DataResult.success(value)
                : DataResult.error(() -> "Chance must be finite and in [0,1]");
    }

    public SchoolSignatureAffix(
            String m, Map<String, Fn> v, Set<String> t, String school,
            String target, String effect, int duration, int amplifier, float chance) {
        this(m, v, t, school, target, effect, duration, amplifier, chance, Optional.empty(), Optional.empty(), 5);
    }

    public SchoolSignatureAffix(
            String m, Map<String, Fn> v, Set<String> t, String school,
            String target, String effect, int duration, int amplifier, float chance,
            Optional<String> action, Optional<Float> amount, int distance) {
        this(m, Values.legacy(v), t, school, target, effect, duration, amplifier, chance, action, amount, distance);
    }

    private SchoolSignatureAffix(
            String m, Values v, Set<String> t, String school,
            String target, String effect, int duration, int amplifier, float chance,
            Optional<String> action, Optional<Float> amount, int distance) {
        super(m, v.scalars(), t, AffixType.ABILITY);
        if (!school.isEmpty() && Schools.idFromName(school) == 0) throw new IllegalArgumentException("Unknown spell school: " + school);
        String normalizedTarget = target.toUpperCase(Locale.ROOT);
        if (!SpellEffects.SIG_TARGET_TARGET.equals(normalizedTarget)
                && !SpellEffects.SIG_TARGET_ATTACKER.equals(normalizedTarget)) {
            throw new IllegalArgumentException("Invalid signature target: " + target);
        }
        if (!effect.isEmpty() && ResourceLocation.tryParse(effect) == null) {
            throw new IllegalArgumentException("Invalid effect id: " + effect);
        }
        if (duration < 0 || amplifier < 0 || !Float.isFinite(chance) || chance < 0 || chance > 1) {
            throw new IllegalArgumentException("Invalid signature effect parameters");
        }
        this.school = school.toLowerCase(Locale.ROOT);
        this.schoolId = Schools.idFromName(school);
        String resolvedAction = action.orElseGet(() -> SpellEffects.legacyAction(schoolId, effect));
        boolean legacyBlink = schoolId == 5 && "blink".equals(resolvedAction);
        if (legacyBlink) {
            var migrated = new java.util.HashMap<String, Map<String, Fn>>();
            v.entries().keySet().forEach(rarity -> migrated.put(rarity, Map.of(
                    "amplifier", new Fn(0, 1, 0), "duration", new Fn(60, 1, 0), "chance", new Fn(1, 1, 0))));
            this.parameters = new Values(migrated);
        } else this.parameters = v;
        this.target = legacyBlink ? SpellEffects.SIG_TARGET_TARGET : normalizedTarget;
        this.effect = legacyBlink ? "minecraft:slowness" : effect;
        this.duration = legacyBlink ? 60 : duration;
        this.amplifier = legacyBlink ? 0 : amplifier;
        this.chance = legacyBlink ? 1 : chance;
        this.action = legacyBlink ? "potion" : resolvedAction;
        this.amount = amount.orElse("low_health_damage".equals(this.action) ? 0.15f : 0.5f);
        this.distance = distance;
        if (!ACTIONS.contains(this.action) || !Float.isFinite(this.amount) || this.amount < 0 || distance < 1 || distance > 128) {
            throw new IllegalArgumentException("Invalid signature action parameters");
        }
    }

    @Override
    public boolean canApplyTo(ItemStack stack, LootCategory cat, LootRarity rarity) {
        if (!super.canApplyTo(stack, cat, rarity)) return false;
        if (stack.isEmpty()) return true;
        try {
            if (!(stack.getItem() instanceof Scroll) || !ISpellContainer.isSpellContainer(stack)) return false;
            SpellData sd = ISpellContainer.get(stack).getSpellAtIndex(0);
            if (sd == null || sd.getSpell() == null) return false;
            if (schoolId == 0) return true;
            ResourceLocation sc = sd.getSpell().getSchoolType().getId();
            return sc != null && sc.equals(Schools.resource(schoolId));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ReforgeCache.Data contribute(int baseValue) { return ReforgeCache.Data.DEF; }

    @Override
    public SpellEffects contributeEffect(int baseValue) {
        return SpellEffects.of(new Effect(List.of(new SpellEffects.Signature(
                schoolId, baseValue, target, effect, duration, amplifier, chance, action, amount, distance))));
    }

    @Override
    public SpellEffects resolveEffects(LootRarity rarity, float level) {
        return SpellEffects.of(new Effect(List.of(resolveSignature(rarity, level))));
    }

    private SpellEffects.Signature resolveSignature(LootRarity rarity, float level) {
        float value = parameters.get(rarity, level, "value", getBaseValue(rarity, level));
        if (("blink".equals(action) || "mana_refund".equals(action)) && parameters.has(rarity, "chance")) {
            value = 100 * parameters.get(rarity, level, "chance", 0);
        }
        return new SpellEffects.Signature(schoolId, value, target, effect,
                parameters.getInt(rarity, level, "duration", duration),
                parameters.getInt(rarity, level, "amplifier", amplifier),
                parameters.get(rarity, level, "chance", chance), action,
                parameters.get(rarity, level, "amount", amount),
                parameters.getInt(rarity, level, "distance", distance));
    }

    @Override
    public MutableComponent getDescription(ItemStack stack, LootRarity rarity, float level) {
        String key = "modifier.apotheosis_spells.signature.";
        var signature = resolveSignature(rarity, level);
        String value = number(signature.value());
        if (!"potion".equals(action)) return switch (action) {
            case "undead_damage" -> Component.translatable(key + action, value);
            case "blink" -> Component.translatable(key + action, percent(SpellEffects.clamp01(signature.value() / 100)), signature.distance());
            case "mana_refund" -> Component.translatable(key + action, percent(SpellEffects.clamp01(signature.value() / 100)), percent(Math.min(1, signature.amount())));
            case "low_health_damage" -> Component.translatable(key + action, percent(signature.amount()));
            default -> Component.translatable(key + "none");
        };
        MutableComponent effectName = ChannelEffectAffix.effectComponent(effect, signature.amplifier());
        return describePotion(signature, effectName);
    }

    private MutableComponent describePotion(SpellEffects.Signature signature, MutableComponent effectName) {
        String key = "modifier.apotheosis_spells.signature.";
        String receiver = SpellEffects.SIG_TARGET_ATTACKER.equals(target) ? "attacker" : "target";
        return signature.chance() >= 1
                ? Component.translatable(key + "potion." + receiver + ".guaranteed", effectName, ChannelEffectAffix.seconds(signature.duration()))
                : Component.translatable(key + "potion." + receiver, effectName, percent(signature.chance()), ChannelEffectAffix.seconds(signature.duration()));
    }

    @Override
    public Component getAugmentingText(ItemStack stack, LootRarity rarity, float level) {
        var text = getDescription(stack, rarity, level);
        var min = resolveSignature(rarity, 0);
        var max = resolveSignature(rarity, 1);
        switch (action) {
            case "potion" -> {
                var signature = resolveSignature(rarity, level);
                var name = ChannelEffectAffix.effectComponent(effect, signature.amplifier());
                ChannelEffectAffix.appendPotionLevelBounds(name, min.amplifier(), max.amplifier());
                text = describePotion(signature, name);
                appendBounds(text, ChannelEffectAffix.seconds(min.duration()) + "s", ChannelEffectAffix.seconds(max.duration()) + "s");
                appendBounds(text, percent(min.chance()) + "%", percent(max.chance()) + "%");
            }
            case "undead_damage" -> appendBounds(text, number(min.value()) + "%", number(max.value()) + "%");
            case "blink", "mana_refund" -> {
                appendBounds(text, percent(SpellEffects.clamp01(min.value() / 100)) + "%",
                        percent(SpellEffects.clamp01(max.value() / 100)) + "%");
                if ("blink".equals(action)) appendBounds(text, Integer.toString(min.distance()), Integer.toString(max.distance()));
                else appendBounds(text, percent(Math.min(1, min.amount())) + "%", percent(Math.min(1, max.amount())) + "%");
            }
            case "low_health_damage" -> appendBounds(text, percent(min.amount()) + "%", percent(max.amount()) + "%");
        }
        if (stack.isEmpty() && schoolId != 0) {
            var type = io.redspace.ironsspellbooks.api.registry.SchoolRegistry.getSchool(Schools.resource(schoolId));
            text.append("\n").append(Component.translatable("modifier.apotheosis_spells.catalog.school",
                    type == null ? Component.literal(school) : type.getDisplayName()));
        }
        return text;
    }

    private static String number(float value) {
        return new java.math.BigDecimal(Float.toString(value)).stripTrailingZeros().toPlainString();
    }

    static String percent(float value) {
        return new java.math.BigDecimal(Float.toString(value)).movePointRight(2).stripTrailingZeros().toPlainString();
    }

    public record Effect(List<SpellEffects.Signature> signatures) implements SpellEffects.Module {
        public static final Codec<Effect> CODEC = SpellEffects.Signature.CODEC.listOf().xmap(Effect::new, Effect::signatures);

        public Effect {
            signatures = SpellEffects.normalizeSignatures(signatures);
        }

        @Override
        public String type() { return "apotheosis_spells:school_signature"; }

        @Override
        public int order() { return 10; }

        @Override
        public boolean isEmpty() { return signatures.isEmpty(); }

        @Override
        public Effect merge(SpellEffects.Module other) {
            return new Effect(SpellEffects.normalizeSignatures(SpellEffects.joined(signatures, ((Effect) other).signatures)));
        }

        @Override
        public float modifyDamage(SpellEffects.DamageContext context, float amount) {
            for (var signature : signatures) {
                if ("undead_damage".equals(signature.action()) && context.target().getMobType() == MobType.UNDEAD) {
                    amount *= 1f + signature.value() / 100f;
                } else if ("low_health_damage".equals(signature.action()) && context.player().getMaxHealth() > 0
                        && context.player().getHealth() / context.player().getMaxHealth() < 0.5f) {
                    amount *= 1f + signature.amount();
                }
            }
            return amount;
        }

        @Override
        public void afterDamage(SpellEffects.DamageContext context) {
            for (var signature : signatures) {
                if (!"potion".equals(signature.action()) || signature.chance() <= 0
                        || context.player().getRandom().nextFloat() >= signature.chance()) continue;
                var receiver = SpellEffects.SIG_TARGET_ATTACKER.equals(signature.target()) ? context.player() : context.target();
                SpellEffectHandler.applyEffect(receiver, signature.effect(), signature.duration(), signature.amplifier());
            }
        }

        @Override
        public void afterCast(SpellEffects.CastContext context) {
            for (var signature : signatures) {
                if ("blink".equals(signature.action()) && context.player().getRandom().nextFloat() < signature.value() / 100f) {
                    SpellEffectHandler.blink(context.player(), signature.distance());
                }
                if ("mana_refund".equals(signature.action()) && context.source().consumesMana()
                        && Float.isFinite(context.paidMana()) && context.paidMana() > 0
                        && context.player().getRandom().nextFloat() < SpellEffects.clamp01(signature.value() / 100f)) {
                    float refund = context.paidMana() * Math.min(1, signature.amount());
                    SpellEffectHandler.restoreMana(context.player(), refund);
                    com.example.apotheosis_spells.ApotheosisSpells.Diagnostics.log("MANA_REFUND", () ->
                            com.example.apotheosis_spells.ApotheosisSpells.Diagnostics.player(context.player())
                                    + " spell=" + context.spell().getSpellId() + " paid=" + context.paidMana() + " refund=" + refund);
                    return;
                }
            }
        }
    }

    @Override
    protected Codec<? extends SpellAffix> getSelfCodec() { return C; }
}
