package com.example.apotheosis_spells.affix;

import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.api.SpellEffects;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mojang.datafixers.util.Either;
import dev.shadowsoffire.placebo.util.StepFunction;
import dev.shadowsoffire.apotheosis.adventure.affix.Affix;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixType;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.item.Scroll;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Set;

public abstract class SpellAffix extends Affix {

    private static final Set<String> RADIUS_SPELLS = Set.of(
            "io.redspace.ironsspellbooks.spells.fire.FireArrowSpell",
            "io.redspace.ironsspellbooks.spells.fire.HeatSurgeSpell",
            "io.redspace.ironsspellbooks.spells.fire.MagmaBombSpell",
            "io.redspace.ironsspellbooks.spells.ice.FrostwaveSpell",
            "io.redspace.ironsspellbooks.spells.ice.SnowballSpell",
            "io.redspace.ironsspellbooks.spells.lightning.ShockwaveSpell",
            "io.redspace.ironsspellbooks.spells.nature.AcidOrbSpell",
            "io.redspace.ironsspellbooks.spells.fire.FireballSpell",
            "io.redspace.ironsspellbooks.spells.lightning.ChainLightningSpell",
            "io.redspace.ironsspellbooks.spells.evocation.GustSpell",
            "io.redspace.ironsspellbooks.spells.blood.RayOfSiphoningSpell",
            "io.redspace.ironsspellbooks.spells.ice.RayOfFrostSpell",
            "io.redspace.ironsspellbooks.spells.eldritch.SonicBoomSpell",
            "io.redspace.ironsspellbooks.spells.eldritch.EldritchBlastSpell",
            "io.redspace.ironsspellbooks.spells.ender.BlackHoleSpell",
            "io.redspace.ironsspellbooks.spells.fire.ScorchSpell",
            "io.redspace.ironsspellbooks.spells.holy.HealingCircleSpell",
            "io.redspace.ironsspellbooks.spells.nature.EarthquakeSpell",
            "io.redspace.ironsspellbooks.spells.blood.SacrificeSpell",
            "io.redspace.ironsspellbooks.spells.ender.StarfallSpell",
            "io.redspace.ironsspellbooks.spells.eldritch.TelekinesisSpell",
            "io.redspace.ironsspellbooks.spells.evocation.FirecrackerSpell",
            "io.redspace.ironsspellbooks.spells.nature.StompSpell",
            "io.redspace.ironsspellbooks.spells.evocation.SpectralHammerSpell",
            "io.redspace.ironsspellbooks.spells.fire.RaiseHellSpell",
            "io.redspace.ironsspellbooks.spells.blood.BloodStepSpell",
            "io.redspace.ironsspellbooks.spells.ender.TeleportSpell",
            "io.redspace.ironsspellbooks.spells.ice.FrostStepSpell",
            "io.redspace.ironsspellbooks.spells.lightning.ThunderStepSpell",
            "io.redspace.ironsspellbooks.spells.ender.PortalSpell",
            "io.redspace.ironsspellbooks.spells.ender.ArcaneShackleSpell",
            "io.redspace.ironsspellbooks.spells.evocation.FangSwirlSpell",
            "io.redspace.ironsspellbooks.spells.ender.GravityFissureSpell",
            "io.redspace.ironsspellbooks.spells.ice.BlizzardSpell",
            "io.redspace.ironsspellbooks.spells.fire.SoulfireRaySpell",
            "io.redspace.ironsspellbooks.spells.fire.WallOfFireSpell",
            "io.redspace.ironsspellbooks.spells.ender.EchoingStrikesSpell");

    private static final Set<String> DURATION_SPELLS = Set.of(
            "io.redspace.ironsspellbooks.spells.fire.ScorchSpell",
            "io.redspace.ironsspellbooks.spells.holy.HealingCircleSpell",
            "io.redspace.ironsspellbooks.spells.nature.EarthquakeSpell",
            "io.redspace.ironsspellbooks.spells.nature.PoisonSplashSpell",
            "io.redspace.ironsspellbooks.spells.nature.RootSpell",
            "io.redspace.ironsspellbooks.spells.ender.BlackHoleSpell",
            "io.redspace.ironsspellbooks.spells.fire.MagmaBombSpell",
            "io.redspace.ironsspellbooks.spells.ender.ArcaneShackleSpell",
            "io.redspace.ironsspellbooks.spells.ender.GravityFissureSpell",
            "io.redspace.ironsspellbooks.spells.ender.PortalSpell",
            "io.redspace.ironsspellbooks.spells.evocation.FangSwirlSpell",
            "io.redspace.ironsspellbooks.spells.evocation.InvisibilitySpell",
            "io.redspace.ironsspellbooks.spells.evocation.SlowSpell",
            "io.redspace.ironsspellbooks.spells.fire.HeatSurgeSpell",
            "io.redspace.ironsspellbooks.spells.holy.AngelWingsSpell",
            "io.redspace.ironsspellbooks.spells.holy.HasteSpell",
            "io.redspace.ironsspellbooks.spells.ice.BlizzardSpell",
            "io.redspace.ironsspellbooks.spells.ice.FrostwaveSpell",
            "io.redspace.ironsspellbooks.spells.ice.IceTombSpell",
            "io.redspace.ironsspellbooks.spells.ice.SnowballSpell",
            "io.redspace.ironsspellbooks.spells.lightning.ThunderstormSpell",
            "io.redspace.ironsspellbooks.spells.nature.AcidOrbSpell",
            "io.redspace.ironsspellbooks.spells.nature.BlightSpell",
            "io.redspace.ironsspellbooks.spells.blood.HeartstopSpell",
            "io.redspace.ironsspellbooks.spells.eldritch.AbyssalShroudSpell",
            "io.redspace.ironsspellbooks.spells.eldritch.PlanarSightSpell",
            "io.redspace.ironsspellbooks.spells.ender.EchoingStrikesSpell",
            "io.redspace.ironsspellbooks.spells.ender.EvasionSpell",
            "io.redspace.ironsspellbooks.spells.holy.FortifySpell",
            "io.redspace.ironsspellbooks.spells.ice.FrostbiteSpell",
            "io.redspace.ironsspellbooks.spells.lightning.ChargeSpell",
            "io.redspace.ironsspellbooks.spells.nature.GluttonySpell",
            "io.redspace.ironsspellbooks.spells.nature.OakskinSpell",
            "io.redspace.ironsspellbooks.spells.nature.SpiderAspectSpell");

    private static final ClassValue<Boolean> ECHO_SAFE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                Class<?> living = net.minecraft.world.entity.LivingEntity.class;
                return inheritsSpellMethod(type, "getRecastCount", int.class, int.class, living)
                        && inheritsSpellMethod(type, "getEmptyCastData", io.redspace.ironsspellbooks.api.spells.ICastDataSerializable.class)
                        && inheritsSpellMethod(type, "onServerPreCast", void.class, net.minecraft.world.level.Level.class, int.class,
                                living, io.redspace.ironsspellbooks.api.magic.MagicData.class);
            } catch (ReflectiveOperationException | LinkageError exception) {
                return false;
            }
        }
    };

    private static boolean inheritsSpellMethod(Class<?> type, String name, Class<?> result, Class<?>... parameters)
            throws ReflectiveOperationException {
        var lookup = java.lang.invoke.MethodHandles.publicLookup();
        var method = lookup.findVirtual(type, name, java.lang.invoke.MethodType.methodType(result, parameters));
        return lookup.revealDirect(method).getDeclaringClass() == AbstractSpell.class;
    }

    public record Fn(float min, int steps, float step) {
        private static final Codec<Float> FINITE_FLOAT = Codec.FLOAT.flatXmap(Fn::finite, Fn::finite);

        public Fn {
            if (!Float.isFinite(min) || !Float.isFinite(step) || steps < 0) {
                throw new IllegalArgumentException("Fn requires finite values and non-negative steps");
            }
            if (steps == 0) {
                steps = 1;
                step = 0;
            }
            if (!Float.isFinite(min + steps * step)) throw new IllegalArgumentException("Step range must be finite");
        }

        private static final Codec<Fn> LEGACY = RecordCodecBuilder.create(i -> i.group(
                FINITE_FLOAT.fieldOf("min").forGetter(Fn::min),
                strictIntRange(0, 0).fieldOf("steps").forGetter(Fn::steps),
                FINITE_FLOAT.fieldOf("step").forGetter(Fn::step)
        ).apply(i, Fn::new));

        private static final Codec<Fn> NATIVE = StepFunction.CODEC.flatXmap(
                value -> Float.isFinite(value.min()) && Float.isFinite(value.step()) && Float.isFinite(value.max())
                        ? DataResult.success(new Fn(value.min(), value.steps(), value.step()))
                        : DataResult.error(() -> "Step range must be finite"),
                value -> DataResult.success(new StepFunction(value.min(), value.steps(), value.step())));
        private static final Codec<Fn> COMPATIBLE = Codec.either(LEGACY, NATIVE).xmap(e -> e.map(v -> v, v -> v), Either::right);
        public static final Codec<Fn> C = new Codec<>() {
            @Override
            public <T> DataResult<com.mojang.datafixers.util.Pair<Fn, T>> decode(com.mojang.serialization.DynamicOps<T> ops, T input) {
                var map = ops.getMap(input).result();
                if (map.isPresent() && map.get().entries().anyMatch(entry -> ops.getStringValue(entry.getFirst()).result()
                        .map(key -> !Set.of("min", "steps", "step").contains(key)).orElse(true))) {
                    return DataResult.error(() -> "Step function only accepts min, steps, step");
                }
                try {
                    return COMPATIBLE.decode(ops, input);
                } catch (IllegalArgumentException exception) {
                    return DataResult.error(() -> "Invalid step function: " + exception.getMessage());
                }
            }

            @Override
            public <T> DataResult<T> encode(Fn input, com.mojang.serialization.DynamicOps<T> ops, T prefix) {
                return NATIVE.encode(input, ops, prefix);
            }
        };

        public float getFloat(float lvl) {
            float normalizedLevel = Float.isFinite(lvl) ? Mth.clamp(lvl, 0, 1) : 0;
            return new StepFunction(min, steps, step).get(normalizedLevel);
        }

        public int get(float lvl) {
            return (int) getFloat(lvl);
        }

        private static DataResult<Float> finite(float value) {
            return Float.isFinite(value)
                    ? DataResult.success(value)
                    : DataResult.error(() -> "Value must be finite");
        }
    }

    public record Values(Map<String, Map<String, Fn>> entries) {
        public Values {
            var copy = new java.util.HashMap<String, Map<String, Fn>>();
            entries.forEach((rarity, fields) -> copy.put(rarity, Map.copyOf(fields)));
            entries = Map.copyOf(copy);
        }

        public static Codec<Values> codec(String... parameters) {
            Set<String> allowed = Set.of(parameters);
            Codec<Map<String, Fn>> fields = Codec.unboundedMap(Codec.STRING, Fn.C).flatXmap(
                    value -> validate(value, allowed), value -> validate(value, allowed));
            Codec<Map<String, Fn>> entry = Codec.either(Fn.C, fields).xmap(
                    either -> either.map(value -> Map.of("value", value), value -> value), Either::right);
            return Codec.unboundedMap(Codec.STRING, entry).xmap(Values::new, Values::entries);
        }

        private static DataResult<Map<String, Fn>> validate(Map<String, Fn> fields, Set<String> allowed) {
            if (fields.isEmpty()) return DataResult.error(() -> "At least one effect parameter is required");
            for (var entry : fields.entrySet()) {
                String key = entry.getKey();
                if (!allowed.contains(key)) return DataResult.error(() -> "Unsupported effect parameter: " + key);
                Fn function = entry.getValue();
                double min = Math.min(function.min(), function.min() + function.steps() * function.step());
                double max = Math.max(function.min(), function.min() + function.steps() * function.step());
                double lower = "distance".equals(key) ? 1 : "value".equals(key) ? -Float.MAX_VALUE : 0;
                double upper = switch (key) {
                    case "chance" -> 1;
                    case "distance" -> 128;
                    case "duration", "amplifier", "instant_duration" -> Integer.MAX_VALUE;
                    default -> Float.MAX_VALUE;
                };
                if (min < lower || max > upper) return DataResult.error(() -> "Effect parameter range is invalid: " + key);
            }
            return DataResult.success(fields);
        }

        public static Values legacy(Map<String, Fn> values) {
            var result = new java.util.HashMap<String, Map<String, Fn>>();
            values.forEach((rarity, function) -> result.put(rarity, Map.of("value", function)));
            return new Values(result);
        }

        public Map<String, Fn> scalars() {
            var result = new java.util.HashMap<String, Fn>();
            entries.forEach((rarity, fields) -> result.put(rarity, fields.getOrDefault("value", new Fn(0, 1, 0))));
            return Map.copyOf(result);
        }

        public boolean has(LootRarity rarity, String parameter) {
            return entries.getOrDefault(rarityKey(rarity), Map.of()).containsKey(parameter);
        }

        public float get(LootRarity rarity, float level, String parameter, float fallback) {
            Fn function = entries.getOrDefault(rarityKey(rarity), Map.of()).get(parameter);
            return function == null ? fallback : function.getFloat(level);
        }

        public int getInt(LootRarity rarity, float level, String parameter, int fallback) {
            return (int) get(rarity, level, parameter, fallback);
        }
    }

    protected static Codec<Integer> strictIntRange(int min, int max) {
        return Codec.INT.flatXmap(value -> integerInRange(value, min, max),
                value -> integerInRange(value, min, max));
    }

    private static DataResult<Integer> integerInRange(int value, int min, int max) {
        return value >= min && value <= max ? DataResult.success(value)
                : DataResult.error(() -> "Value " + value + " must be in [" + min + "," + max + "]");
    }

    protected final String mod;
    protected final Map<String, Fn> vals;
    protected final Set<String> types;

    protected SpellAffix(String mod, Map<String, Fn> vals, Set<String> types, AffixType type) {
        super(type);
        this.mod = mod;
        this.vals = Map.copyOf(vals);
        this.types = Set.copyOf(types);
    }

    public int getBaseValue(LootRarity r, float lvl) {
        Fn f = vals.get(rarityKey(r));
        return f != null ? f.get(lvl) : Mth.floor(lvl);
    }

    protected static String rarityKey(LootRarity r) {
        var key = dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry.INSTANCE.getKey(r);
        return key != null ? key.getPath() : "";
    }

    public abstract ReforgeCache.Data contribute(int baseValue);

    public SpellEffects contributeEffect(int baseValue) {
        return SpellEffects.NONE;
    }

    public SpellEffects contributeEffect(LootRarity rarity, int baseValue) {
        return contributeEffect(baseValue);
    }

    public SpellEffects resolveEffects(LootRarity rarity, float level) {
        return contributeEffect(rarity, getBaseValue(rarity, level));
    }

    @Override
    public boolean canApplyTo(ItemStack stack, LootCategory cat, LootRarity rarity) {
        return types.contains(cat.getName()) && vals.containsKey(rarityKey(rarity));
    }

    public static boolean supportsRadius(AbstractSpell spell) {
        return spell != null && RADIUS_SPELLS.contains(spell.getClass().getName());
    }

    public static boolean supportsDuration(AbstractSpell spell) {
        return spell != null && DURATION_SPELLS.contains(spell.getClass().getName());
    }

    public static boolean supportsEcho(AbstractSpell spell) {
        if (spell == null || spell.getCastType() == CastType.CONTINUOUS
                || "irons_spellbooks:sacrifice".equals(spell.getSpellId())) return false;
        return ECHO_SAFE.get(spell.getClass());
    }

    protected static AbstractSpell spellFrom(ItemStack stack) {
        try {
            if (!(stack.getItem() instanceof Scroll) || !ISpellContainer.isSpellContainer(stack)) return null;
            SpellData data = ISpellContainer.get(stack).getSpellAtIndex(0);
            return data == null ? null : data.getSpell();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    @Override
    public MutableComponent getDescription(ItemStack stack, LootRarity rarity, float level) {
        int v = displayValue(getBaseValue(rarity, level));
        return Component.translatable(getModifierKey(), v);
    }

    public String getModifierKey() {
        return "modifier.apotheosis_spells." + mod;
    }

    @Override
    public Component getAugmentingText(ItemStack stack, LootRarity rarity, float level) {
        var text = getDescription(stack, rarity, level);
        appendBounds(text, Integer.toString(displayValue(getBaseValue(rarity, 0))), Integer.toString(displayValue(getBaseValue(rarity, 1))));
        if (stack.isEmpty() && requiresSupportedSpell()) {
            text.append("\n").append(Component.translatable("modifier.apotheosis_spells.catalog.supported_spells"));
        }
        return text;
    }

    protected int displayValue(int value) { return value; }

    protected boolean requiresSupportedSpell() { return false; }

    protected static void appendBounds(MutableComponent text, String min, String max) {
        if (!min.equals(max)) text.append(valueBounds(Component.literal(min), Component.literal(max)));
    }

    @Override
    public Component getName(boolean prefix) {
        return Component.translatable("affix.apotheosis_spells." + mod + (prefix ? "" : ".suffix"));
    }

    @Override
    public Codec<? extends Affix> getCodec() {
        return getSelfCodec();
    }

    protected abstract Codec<? extends SpellAffix> getSelfCodec();
}
