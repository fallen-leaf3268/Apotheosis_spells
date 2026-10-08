package com.example.apotheosis_spells.api;

import com.example.apotheosis_spells.affix.spell.CdSkipAffix;
import com.example.apotheosis_spells.affix.spell.ChannelEffectAffix;
import com.example.apotheosis_spells.affix.spell.EchoAffix;
import com.example.apotheosis_spells.affix.spell.ExecuteAffix;
import com.example.apotheosis_spells.affix.spell.ManaStealAffix;
import com.example.apotheosis_spells.affix.spell.PostCastEffectAffix;
import com.example.apotheosis_spells.affix.spell.SchoolSignatureAffix;
import com.example.apotheosis_spells.affix.spell.WardAffix;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import static dev.shadowsoffire.placebo.codec.PlaceboCodecs.nullableField;

public record SpellEffects(List<Module> modules) {

    public record CastContext(net.minecraft.server.level.ServerPlayer player,
                              io.redspace.ironsspellbooks.api.spells.AbstractSpell spell, int level,
                              io.redspace.ironsspellbooks.api.spells.CastSource source,
                              io.redspace.ironsspellbooks.api.magic.MagicData magic,
                              com.example.apotheosis_spells.handler.SpellCastHooks.Snapshot snapshot, float paidMana) {
        public CastContext(net.minecraft.server.level.ServerPlayer player,
                           io.redspace.ironsspellbooks.api.spells.AbstractSpell spell, int level,
                           io.redspace.ironsspellbooks.api.spells.CastSource source,
                           io.redspace.ironsspellbooks.api.magic.MagicData magic,
                           com.example.apotheosis_spells.handler.SpellCastHooks.Snapshot snapshot) {
            this(player, spell, level, source, magic, snapshot, 0);
        }
    }

    public record DamageContext(net.minecraft.server.level.ServerPlayer player,
                                net.minecraft.world.entity.LivingEntity target,
                                io.redspace.ironsspellbooks.damage.SpellDamageSource source, float damage) {}

    public interface Module {
        String type();
        Module merge(Module other);
        default int order() { return 50; }
        default boolean isEmpty() { return false; }
        default float modifyDamage(DamageContext context, float amount) { return amount; }
        default void afterDamage(DamageContext context) {}
        default void beforeCast(io.redspace.ironsspellbooks.api.events.SpellOnCastEvent event) {}
        default void afterCast(CastContext context) {}
        default void duringCast(net.minecraft.server.level.ServerPlayer player) {}
        default void afterCompletion(net.minecraft.server.level.ServerPlayer player) {}
        default boolean skipCooldown(net.minecraft.server.level.ServerPlayer player,
                                     io.redspace.ironsspellbooks.api.spells.AbstractSpell spell) { return false; }
    }

    public static final String DEF_CHANNEL_EFFECT = "minecraft:resistance";
    public static final String DEF_POSTCAST_EFFECT = "minecraft:speed";
    public static final int DEF_EXECUTE_THRESHOLD = 25;
    public static final int DEF_CHANNEL_DURATION = 10;
    public static final String SIG_TARGET_TARGET = "TARGET";
    public static final String SIG_TARGET_ATTACKER = "ATTACKER";

    private static final Map<String, Codec<? extends Module>> MODULE_CODECS = new java.util.concurrent.ConcurrentHashMap<>();
    public static final SpellEffects NONE = new SpellEffects(List.of());

    static {
        register("apotheosis_spells:mana_leech", ManaStealAffix.Effect.CODEC);
        register("apotheosis_spells:execute", ExecuteAffix.Effect.CODEC);
        register("apotheosis_spells:echo", EchoAffix.Effect.CODEC);
        register("apotheosis_spells:cd_skip", CdSkipAffix.Effect.CODEC);
        register("apotheosis_spells:ward", WardAffix.Effect.CODEC);
        register("apotheosis_spells:channel", ChannelEffectAffix.Effect.CODEC);
        register("apotheosis_spells:postcast", PostCastEffectAffix.Effect.CODEC);
        register("apotheosis_spells:school_signature", SchoolSignatureAffix.Effect.CODEC);
    }

    public static <T extends Module> void register(String id, Codec<T> codec) {
        if (ResourceLocation.tryParse(id) == null) throw new IllegalArgumentException("Invalid effect module id: " + id);
        var previous = MODULE_CODECS.putIfAbsent(id, java.util.Objects.requireNonNull(codec));
        if (previous != null && previous != codec) throw new IllegalArgumentException("Duplicate effect module: " + id);
    }

    public static SpellEffects of(Module module) {
        return module == null || module.isEmpty() ? NONE : new SpellEffects(List.of(module));
    }

    public <T extends Module> java.util.Optional<T> find(Class<T> type) {
        return modules.stream().filter(type::isInstance).map(type::cast).findFirst();
    }

    private static final Comparator<Signature> SIGNATURE_ORDER = Comparator
            .comparingInt(Signature::school)
            .thenComparing(Signature::target)
            .thenComparing(Signature::effect)
            .thenComparingInt(Signature::duration)
            .thenComparingInt(Signature::amplifier)
            .thenComparingDouble(Signature::chance)
            .thenComparingDouble(Signature::value)
            .thenComparing(Signature::action)
            .thenComparingDouble(Signature::amount)
            .thenComparingInt(Signature::distance);

    public SpellEffects {
        Map<String, Module> byType = new TreeMap<>();
        if (modules != null) for (var module : modules) {
            if (module != null && !module.isEmpty()) byType.merge(module.type(), module, Module::merge);
        }
        modules = byType.values().stream().filter(module -> !module.isEmpty())
                .sorted(Comparator.comparingInt(Module::order).thenComparing(Module::type)).toList();
    }

    public boolean isEmpty() {
        return modules.isEmpty();
    }

    public SpellEffects merge(SpellEffects other) {
        if (other == null || other.isEmpty()) return this;
        if (isEmpty()) return other;
        return new SpellEffects(joined(modules, other.modules));
    }

    public float modifyDamage(DamageContext context, float amount) {
        for (var module : modules) amount = module.modifyDamage(context, amount);
        return amount;
    }

    public void afterDamage(DamageContext context) { modules.forEach(module -> module.afterDamage(context)); }
    public void beforeCast(io.redspace.ironsspellbooks.api.events.SpellOnCastEvent event) {
        modules.forEach(module -> module.beforeCast(event));
    }
    public void afterCast(CastContext context) { modules.forEach(module -> module.afterCast(context)); }
    public void duringCast(net.minecraft.server.level.ServerPlayer player) { modules.forEach(module -> module.duringCast(player)); }
    public void afterCompletion(net.minecraft.server.level.ServerPlayer player) { modules.forEach(module -> module.afterCompletion(player)); }
    public boolean skipCooldown(net.minecraft.server.level.ServerPlayer player,
                                io.redspace.ironsspellbooks.api.spells.AbstractSpell spell) {
        boolean skip = false;
        for (var module : modules) skip |= module.skipCooldown(player, spell);
        return skip;
    }

    public float manaLeech() { return find(ManaStealAffix.Effect.class).map(ManaStealAffix.Effect::value).orElse(0f); }
    public float echo() { return find(EchoAffix.Effect.class).map(EchoAffix.Effect::chance).orElse(0f); }
    public float cdSkip() { return find(CdSkipAffix.Effect.class).map(CdSkipAffix.Effect::chance).orElse(0f); }
    public float shield() { return find(WardAffix.Effect.class).map(WardAffix.Effect::value).orElse(0f); }
    public int haste() { return find(PostCastEffectAffix.Effect.class).map(PostCastEffectAffix.Effect::haste).orElse(0); }
    public List<Execution> executions() { return find(ExecuteAffix.Effect.class).map(ExecuteAffix.Effect::values).orElse(List.of()); }
    public List<Signature> hitSignatures() { return find(SchoolSignatureAffix.Effect.class).map(SchoolSignatureAffix.Effect::signatures).orElse(List.of()); }
    public List<Potion> channelEffects() { return find(ChannelEffectAffix.Effect.class).map(ChannelEffectAffix.Effect::potions).orElse(List.of()); }
    public List<Potion> postcastEffects() { return find(PostCastEffectAffix.Effect.class).map(PostCastEffectAffix.Effect::potions).orElse(List.of()); }

    public float executeBonus(float healthRatio) {
        return find(ExecuteAffix.Effect.class).map(effect -> effect.bonus(healthRatio)).orElse(0f);
    }

    public float execute() {
        float value = 0;
        for (Execution execution : executions()) value += execution.bonus();
        return nonNegativeFinite(value);
    }

    public int executeThreshold() {
        return executions().isEmpty() ? DEF_EXECUTE_THRESHOLD : executions().get(0).threshold();
    }

    public int threshold() {
        return executeThreshold();
    }

    public int signature() {
        return hitSignatures().isEmpty() ? 0 : hitSignatures().get(0).school();
    }

    public float signatureValue() {
        return hitSignatures().isEmpty() ? 0 : hitSignatures().get(0).value();
    }

    public String sigTarget() {
        return hitSignatures().isEmpty() ? null : hitSignatures().get(0).target();
    }

    public String sigEffect() {
        if (hitSignatures().isEmpty() || hitSignatures().get(0).effect().isEmpty()) return null;
        return hitSignatures().get(0).effect();
    }

    public int sigDuration() {
        return hitSignatures().isEmpty() ? 0 : hitSignatures().get(0).duration();
    }

    public int sigAmplifier() {
        return hitSignatures().isEmpty() ? 0 : hitSignatures().get(0).amplifier();
    }

    public float sigChance() {
        return hitSignatures().isEmpty() ? 0 : hitSignatures().get(0).chance();
    }

    public int channel() {
        return channelEffects().isEmpty() ? 0 : channelEffects().get(0).amplifier() + 1;
    }

    public String channelEffect() {
        return channelEffects().isEmpty() ? DEF_CHANNEL_EFFECT : channelEffects().get(0).effect();
    }

    public int postcast() {
        return postcastEffects().isEmpty() ? 0 : postcastEffects().get(0).amplifier() + 1;
    }

    public int postcastDur() {
        return postcastEffects().isEmpty() ? 0 : postcastEffects().get(0).duration();
    }

    public String postcastEffect() {
        return postcastEffects().isEmpty() ? DEF_POSTCAST_EFFECT : postcastEffects().get(0).effect();
    }

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", 2);
        var encoded = new ListTag();
        for (var module : modules) {
            @SuppressWarnings("unchecked")
            var codec = (Codec<Module>) MODULE_CODECS.get(module.type());
            if (codec == null) throw new IllegalStateException("Unregistered effect module: " + module.type());
            var entry = new CompoundTag();
            entry.putString("type", module.type());
            entry.put("data", codec.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, module)
                    .getOrThrow(false, error -> {}));
            encoded.add(entry);
        }
        tag.put("modules", encoded);
        return tag;
    }

    public static SpellEffects read(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return NONE;
        if (tag.getInt("version") >= 2) {
            var decoded = new ArrayList<Module>();
            for (var element : tag.getList("modules", Tag.TAG_COMPOUND)) {
                var entry = (CompoundTag) element;
                var codec = MODULE_CODECS.get(entry.getString("type"));
                if (codec != null && entry.contains("data")) {
                    try {
                        codec.parse(net.minecraft.nbt.NbtOps.INSTANCE, entry.get("data")).result().ifPresent(decoded::add);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            return new SpellEffects(decoded);
        }
        return new SpellEffects(List.of(
                new ManaStealAffix.Effect(tag.getFloat("mana_leech")),
                new EchoAffix.Effect(tag.getFloat("echo")),
                new CdSkipAffix.Effect(tag.getFloat("cd_skip")),
                new WardAffix.Effect(tag.getFloat("shield")),
                new ExecuteAffix.Effect(readExecutions(tag.getList("executions", Tag.TAG_COMPOUND))),
                new SchoolSignatureAffix.Effect(readSignatures(tag.getList("signatures", Tag.TAG_COMPOUND))),
                new ChannelEffectAffix.Effect(readPotions(tag.getList("channel", Tag.TAG_COMPOUND))),
                new PostCastEffectAffix.Effect(readPotions(tag.getList("postcast", Tag.TAG_COMPOUND)), tag.getInt("haste"))));
    }

    public static SpellEffects ofManaLeech(float value) {
        return of(new ManaStealAffix.Effect(value));
    }

    public static SpellEffects ofExecute(float value) {
        return ofExecute(value, DEF_EXECUTE_THRESHOLD);
    }

    public static SpellEffects ofExecute(float value, int threshold) {
        if (!Float.isFinite(value) || value <= 0) return NONE;
        return of(new ExecuteAffix.Effect(List.of(new Execution(value, threshold))));
    }

    public static SpellEffects ofEcho(float value) {
        return of(new EchoAffix.Effect(value));
    }

    public static SpellEffects ofCdSkip(float value) {
        return of(new CdSkipAffix.Effect(value));
    }

    public static SpellEffects ofShield(float value) {
        return of(new WardAffix.Effect(value));
    }

    public static SpellEffects ofSignature(int school, float value) {
        return ofSchoolSignature(school, value, SIG_TARGET_TARGET, "", 0, 0, 1);
    }

    public static SpellEffects ofSchoolSignature(
            int school, float value, String target, String effect, int duration, int amplifier, float chance) {
        if (school < 0) return NONE;
        return of(new SchoolSignatureAffix.Effect(List.of(
                new Signature(school, value, target, effect, duration, amplifier, chance))));
    }

    public static SpellEffects ofChannel(int level, String effect) {
        return ofChannel(level, DEF_CHANNEL_DURATION, effect);
    }

    public static SpellEffects ofChannel(int level, int duration, String effect) {
        if (level <= 0) return NONE;
        return of(new ChannelEffectAffix.Effect(List.of(new Potion(effect, duration, level - 1))));
    }

    public static SpellEffects ofPostcast(int level, int duration, String effect) {
        if (level <= 0) return NONE;
        return of(new PostCastEffectAffix.Effect(List.of(new Potion(effect, duration, level - 1)), 0));
    }

    public record Execution(float bonus, int threshold) {
        public static final Codec<Execution> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("bonus").forGetter(Execution::bonus),
                Codec.intRange(0, 100).fieldOf("threshold").forGetter(Execution::threshold)
        ).apply(i, Execution::new));

        public Execution {
            bonus = nonNegativeFinite(bonus);
            threshold = Math.max(0, Math.min(100, threshold));
        }
    }

    public record Potion(String effect, int duration, int amplifier, int instantDuration, float chance) {
        public static final Codec<Potion> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceLocation.CODEC.xmap(ResourceLocation::toString, ResourceLocation::parse).fieldOf("effect").forGetter(Potion::effect),
                Codec.intRange(0, Integer.MAX_VALUE).fieldOf("duration").forGetter(Potion::duration),
                Codec.intRange(0, Integer.MAX_VALUE).fieldOf("amplifier").forGetter(Potion::amplifier),
                nullableField(Codec.intRange(0, Integer.MAX_VALUE), "instant_duration", 60).forGetter(Potion::instantDuration),
                nullableField(Codec.floatRange(0, 1), "chance", 1f).forGetter(Potion::chance)
        ).apply(i, Potion::new));

        public Potion(String effect, int duration, int amplifier) { this(effect, duration, amplifier, 60, 1); }

        public Potion(String effect, int duration, int amplifier, int instantDuration) {
            this(effect, duration, amplifier, instantDuration, 1);
        }

        public Potion {
            if (!validResourceId(effect)) throw new IllegalArgumentException("Invalid effect id: " + effect);
            duration = Math.max(0, duration);
            amplifier = Math.max(0, amplifier);
            instantDuration = Math.max(0, instantDuration);
            chance = clamp01(chance);
        }
    }

    public record Signature(
            int school, float value, String target, String effect, int duration, int amplifier, float chance,
            String action, float amount, int distance) {
        public static final Codec<Signature> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.intRange(0, Integer.MAX_VALUE).fieldOf("school").forGetter(Signature::school),
                Codec.FLOAT.fieldOf("value").forGetter(Signature::value),
                Codec.STRING.fieldOf("target").forGetter(Signature::target),
                Codec.STRING.fieldOf("effect").forGetter(Signature::effect),
                Codec.intRange(0, Integer.MAX_VALUE).fieldOf("duration").forGetter(Signature::duration),
                Codec.intRange(0, Integer.MAX_VALUE).fieldOf("amplifier").forGetter(Signature::amplifier),
                Codec.floatRange(0, 1).fieldOf("chance").forGetter(Signature::chance),
                nullableField(Codec.STRING, "action", "").forGetter(Signature::action),
                nullableField(Codec.FLOAT, "amount").forGetter(s -> java.util.Optional.of(s.amount())),
                nullableField(Codec.intRange(1, 128), "distance", 5).forGetter(Signature::distance)
        ).apply(i, (school, value, target, effect, duration, amplifier, chance, action, amount, distance) ->
                new Signature(school, value, target, effect, duration, amplifier, chance, action,
                        amount.orElse("low_health_damage".equals(action.isEmpty() ? legacyAction(school, effect) : action) ? 0.15f : 0.5f), distance)));

        public Signature(int school, float value, String target, String effect, int duration, int amplifier, float chance) {
            this(school, value, target, effect, duration, amplifier, chance, legacyAction(school, effect),
                    "low_health_damage".equals(legacyAction(school, effect)) ? 0.15f : 0.5f, 5);
        }

        public Signature {
            if (school < 0) throw new IllegalArgumentException("Invalid school id: " + school);
            value = finiteOrZero(value);
            target = normalizeTarget(target);
            effect = effect == null ? "" : effect;
            if (!effect.isEmpty() && !validResourceId(effect)) {
                throw new IllegalArgumentException("Invalid effect id: " + effect);
            }
            duration = Math.max(0, duration);
            amplifier = Math.max(0, amplifier);
            chance = clamp01(chance);
            action = action == null || action.isEmpty() ? legacyAction(school, effect) : action;
            if (school == 5 && "blink".equals(action)) {
                action = "potion";
                target = SIG_TARGET_TARGET;
                effect = "minecraft:slowness";
                amplifier = 0;
                duration = 60;
                chance = 1;
            }
            if (!java.util.Set.of("potion", "undead_damage", "blink", "mana_refund", "low_health_damage", "none").contains(action)) {
                throw new IllegalArgumentException("Unknown signature action: " + action);
            }
            amount = nonNegativeFinite(amount);
            distance = Math.max(1, Math.min(128, distance));
        }
    }

    public static String legacyAction(int school, String effect) {
        if (effect != null && !effect.isEmpty()) return "potion";
        return switch (school) {
            case 4 -> "undead_damage";
            case 5 -> "blink";
            case 6 -> "low_health_damage";
            case 7 -> "mana_refund";
            default -> "none";
        };
    }

    public static List<Execution> normalizeExecutions(List<Execution> values) {
        if (values == null || values.isEmpty()) return List.of();
        Map<Integer, Float> byThreshold = new TreeMap<>();
        for (Execution execution : values) {
            if (execution != null && execution.bonus() > 0) {
                byThreshold.merge(execution.threshold(), execution.bonus(), Float::sum);
            }
        }
        List<Execution> result = new ArrayList<>(byThreshold.size());
        byThreshold.forEach((threshold, bonus) -> result.add(new Execution(bonus, threshold)));
        return List.copyOf(result);
    }

    public static List<Signature> normalizeSignatures(List<Signature> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().filter(value -> value != null).sorted(SIGNATURE_ORDER).toList();
    }

    public static List<Potion> normalizePotions(List<Potion> values) {
        if (values == null || values.isEmpty()) return List.of();
        Map<String, Potion> byEffect = new TreeMap<>();
        for (Potion potion : values) {
            if (potion != null) byEffect.merge(potion.effect(), potion, SpellEffects::strongerPotion);
        }
        return List.copyOf(byEffect.values());
    }

    private static Potion strongerPotion(Potion first, Potion second) {
        if (second.amplifier() != first.amplifier()) {
            return second.amplifier() > first.amplifier() ? second : first;
        }
        if (second.duration() != first.duration()) {
            return second.duration() > first.duration() ? second : first;
        }
        if (second.instantDuration() != first.instantDuration()) {
            return second.instantDuration() > first.instantDuration() ? second : first;
        }
        return second.chance() > first.chance() ? second : first;
    }

    public static <T> List<T> joined(List<T> first, List<T> second) {
        ArrayList<T> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return result;
    }

    private static List<Execution> readExecutions(ListTag list) {
        List<Execution> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag value = list.getCompound(i);
            float bonus = value.getFloat("bonus");
            if (Float.isFinite(bonus) && bonus > 0) {
                result.add(new Execution(bonus, value.getInt("threshold")));
            }
        }
        return result;
    }

    private static List<Potion> readPotions(ListTag list) {
        List<Potion> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag value = list.getCompound(i);
            String effect = value.getString("effect");
            if (validResourceId(effect)) {
                result.add(new Potion(effect, value.getInt("duration"), value.getInt("amplifier")));
            }
        }
        return result;
    }

    private static List<Signature> readSignatures(ListTag list) {
        List<Signature> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag value = list.getCompound(i);
            int school = value.getInt("school");
            String target = value.getString("target");
            String effect = value.getString("effect");
            if (school > 0 && validTarget(target) && (effect.isEmpty() || validResourceId(effect))) {
                result.add(new Signature(
                        school, value.getFloat("value"), target, effect,
                        value.getInt("duration"), value.getInt("amplifier"), value.getFloat("chance")));
            }
        }
        return result;
    }

    private static String normalizeTarget(String target) {
        String normalized = target == null ? "" : target.toUpperCase(Locale.ROOT);
        if (!validTarget(normalized)) throw new IllegalArgumentException("Invalid signature target: " + target);
        return normalized;
    }

    private static boolean validTarget(String target) {
        return SIG_TARGET_TARGET.equals(target) || SIG_TARGET_ATTACKER.equals(target);
    }

    private static boolean validResourceId(String value) {
        return value != null && ResourceLocation.tryParse(value) != null;
    }

    private static float finiteOrZero(float value) {
        return Float.isFinite(value) ? value : 0;
    }

    public static float nonNegativeFinite(float value) {
        return Float.isFinite(value) && value > 0 ? value : 0;
    }

    public static float clamp01(float value) {
        if (!Float.isFinite(value) || value <= 0) return 0;
        return Math.min(1, value);
    }
}
