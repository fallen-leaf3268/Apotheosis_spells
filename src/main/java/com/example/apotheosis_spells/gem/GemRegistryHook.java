package com.example.apotheosis_spells.gem;

import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.GemInstance;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * SpellGemBonus 注册表。
 *
 * 因为 Apotheosis 的 GemBonusCodec 不允许直接加载外部注册的 codec，
 * 我们用 gem tag "gem" 字段 + id 反查映射到 SpellGemBonus 子类。
 */
public class GemRegistryHook {

    private static final Map<String, Supplier<? extends SpellGemBonus>> FACTORIES = new HashMap<>();

    public static void register(String gemId, Supplier<? extends SpellGemBonus> factory) {
        FACTORIES.put(gemId, factory);
    }

    public static SpellGemBonus getForGemStack(ItemStack gemStack, LootRarity rarity) {
        ResolvedBonus resolved = resolve(gemStack);
        return resolved == null ? null : resolved.bonus();
    }

    public static ResolvedBonus resolve(ItemStack gemStack) {
        if (gemStack == null || gemStack.isEmpty()) return null;
        GemInstance instance = GemInstance.unsocketed(gemStack);
        if (!instance.isValidUnsocketed()) return null;
        Supplier<? extends SpellGemBonus> factory = FACTORIES.get(instance.gem().getId().toString());
        if (factory == null) return null;
        SpellGemBonus bonus = factory.get();
        LootRarity rarity = instance.rarity().get();
        return bonus != null && bonus.supports(rarity) ? new ResolvedBonus(bonus, rarity) : null;
    }

    /**
     * 默认注册两个内置 Gem
     */
    public static void registerDefaults() {
        register("apotheosis_spells:mage_slayer", MageSlayerGemBonus::new);
        register("apotheosis_spells:spell_specialist", SpellSpecialistGemBonus::new);
    }

    public static Set<String> registeredIds() {
        return Set.copyOf(FACTORIES.keySet());
    }

    public record ResolvedBonus(SpellGemBonus bonus, LootRarity rarity) {
        public com.example.apotheosis_spells.api.ReforgeCache.Data contribute() {
            return bonus.contribute(rarity);
        }
    }
}
