package com.example.apotheosis_spells.api;

import com.example.apotheosis_spells.ApotheosisSpells;
import com.example.apotheosis_spells.handler.SpellCastHooks;
import dev.shadowsoffire.apotheosis.Apotheosis;
import dev.shadowsoffire.apotheosis.adventure.affix.Affix;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixRegistry;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry;
import dev.shadowsoffire.placebo.reload.DynamicHolder;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.item.Scroll;
import io.redspace.ironsspellbooks.item.SpellBook;
import io.redspace.ironsspellbooks.registries.ItemRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * 重铸数据缓存层。
 *
 * 重铸单位 = SpellContainer 的每个 SpellSlot。
 * 但 Scroll 整体被 Apotheosis 重铸时，affix 写到 Scroll 的 affix_data 顶层；
 * 铭刻到 SpellBook 后才下沉到 SpellSlot 子标签。
 *
 * 因此 Data 读取必须支持两个来源：
 *   1. SpellSlot 子标签 `affix_data` —— 铭刻后 SpellBook / 铭刻后 Scroll
 *   2. 物品顶层 `affix_data` —— 重铸后的 Scroll
 *
 * 物品顶层为权威来源，SpellSlot 子标签只用于旧数据迁移。
 */
public class ReforgeCache {

    public static final String KEY = "iss_reforge";
    public static final String SLOT_AFFIX_DATA = "affix_data";
    /**
     * 法术书顶层的并行词缀存储：CompoundTag，键 = 法术槽的稳定 index 字段(字符串)，值 = 该法术的 affix_data。
     * 存在物品顶层 NBT(不在 ISB_Spells 容器内)，因此 Iron's 用 CODEC 重序列化法术容器时不会抹掉它，
     * 从根本上解决"增删法术后其他法术词缀丢失/错乱"。
     */
    public static final String BOOK_AFFIXES = "apoth_book_affixes";
    private static final String BOOK_ENTRY_SPELL_ID = "spell_id";
    private static final String BOOK_ENTRY_AFFIX_DATA = "affix_data";

    /** 读取书顶层并行存储里某 index 的 affix_data；兼容旧的未绑定映射与槽内数据。 */
    public static CompoundTag getBookAffix(ItemStack book, int index) {
        if (book.isEmpty() || !(book.getItem() instanceof SpellBook) || index < 0) return null;
        String spellId = getSlotSpellId(book, index);
        if (spellId == null) return null;
        CompoundTag tag = book.getTag();
        CompoundTag map = tag == null ? new CompoundTag() : tag.getCompound(BOOK_AFFIXES);
        String key = String.valueOf(index);
        return preferredBookAffixData(map.contains(key) ? map.getCompound(key) : null,
                getSlotTag(book, index), spellId);
    }

    static CompoundTag preferredBookAffixData(CompoundTag entry, CompoundTag slot, String spellId) {
        if (entry != null) return readBookEntry(entry, spellId);
        if (slot == null || !slot.contains(SLOT_AFFIX_DATA, Tag.TAG_COMPOUND)) return null;
        CompoundTag legacy = slot.getCompound(SLOT_AFFIX_DATA);
        return legacy.isEmpty() ? null : legacy.copy();
    }

    /** 写入/清除书顶层并行存储里某 index 的 affix_data（affixData 为空则清除该键，用于索引复用/移除）。 */
    public static void setBookAffix(ItemStack book, int index, CompoundTag affixData) {
        if (book.isEmpty() || !(book.getItem() instanceof SpellBook) || index < 0) return;
        CompoundTag tag = book.getOrCreateTag();
        CompoundTag map = tag.getCompound(BOOK_AFFIXES);
        String k = String.valueOf(index);
        String spellId = getSlotSpellId(book, index);
        if (affixData == null || affixData.isEmpty() || spellId == null) {
            map.remove(k);
        } else {
            map.put(k, createBookEntry(spellId, affixData));
        }
        if (map.isEmpty()) {
            tag.remove(BOOK_AFFIXES);
        } else {
            tag.put(BOOK_AFFIXES, map);
        }
        CompoundTag slot = getSlotTag(book, index);
        if (slot != null) {
            slot.remove(SLOT_AFFIX_DATA);
            slot.remove(KEY);
            putSlotTag(book, index, slot);
        }
    }

    public static void removeBookAffix(ItemStack book, int index) {
        setBookAffix(book, index, null);
    }

    public static void migrateBook(ItemStack book) {
        if (book.isEmpty() || !(book.getItem() instanceof SpellBook)) return;
        preserveLegacyAffixes(book);
        if (!ISpellContainer.isSpellContainer(book)) {
            CompoundTag tag = book.getTag();
            if (tag != null) tag.remove(BOOK_AFFIXES);
            return;
        }
        CompoundTag tag = book.getOrCreateTag();
        CompoundTag oldMap = tag.getCompound(BOOK_AFFIXES);
        CompoundTag migrated = new CompoundTag();
        var activeSlots = ISpellContainer.get(book).getActiveSpells();
        for (var activeSlot : activeSlots) {
            int index = activeSlot.index();
            String spellId = activeSlot.getSpell().getSpellId();
            if (index < 0 || spellId == null || spellId.isEmpty()) continue;
            CompoundTag slot = getSlotTag(book, index);

            String key = String.valueOf(index);
            CompoundTag affixData = preferredBookAffixData(
                    oldMap.contains(key) ? oldMap.getCompound(key) : null, slot, spellId);
            if (affixData != null && !affixData.isEmpty()) {
                migrated.put(String.valueOf(index), createBookEntry(spellId, affixData));
            }
            if (slot != null) {
                slot.remove(SLOT_AFFIX_DATA);
                slot.remove(KEY);
                putSlotTag(book, index, slot);
            }
        }
        if (migrated.isEmpty()) {
            tag.remove(BOOK_AFFIXES);
        } else {
            tag.put(BOOK_AFFIXES, migrated);
        }
    }

    static void preserveLegacyAffixes(CompoundTag root, boolean book) {
        if (root == null || root.contains(ISpellContainer.NBT)
                || !root.contains(ISpellContainer.LEGACY_NBT, Tag.TAG_COMPOUND)) return;
        CompoundTag container = root.getCompound(ISpellContainer.LEGACY_NBT);
        ListTag slots = container.getList("data", Tag.TAG_COMPOUND);
        CompoundTag map = root.getCompound(BOOK_AFFIXES);
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = slots.size() - 1; i >= 0; i--) {
            CompoundTag slot = slots.getCompound(i);
            if (!slot.contains("index", Tag.TAG_INT)) continue;
            int index = slot.getInt("index");
            if (index < 0 || index >= container.getInt("maxSpells") || !seen.add(index)) continue;
            String spellId = slot.getString("id");
            if (spellId.isEmpty() || ResourceLocation.tryParse(spellId) == null) continue;
            CompoundTag affixData = slot.getCompound(SLOT_AFFIX_DATA);
            if (affixData.isEmpty()) continue;
            if (book) {
                String key = String.valueOf(index);
                if (!map.contains(key)) map.put(key, createBookEntry(spellId, affixData));
            } else if (index == 0 && !root.contains(AffixHelper.AFFIX_DATA)) {
                root.put(AffixHelper.AFFIX_DATA, affixData.copy());
            }
        }
        if (book && !map.isEmpty()) root.put(BOOK_AFFIXES, map);
    }

    public static void preserveLegacyAffixes(ItemStack stack) {
        if (stack.getItem() instanceof SpellBook) preserveLegacyAffixes(stack.getTag(), true);
        else if (stack.getItem() instanceof Scroll) preserveLegacyAffixes(stack.getTag(), false);
    }

    static CompoundTag createBookEntry(String spellId, CompoundTag affixData) {
        CompoundTag entry = new CompoundTag();
        entry.putString(BOOK_ENTRY_SPELL_ID, spellId);
        entry.put(BOOK_ENTRY_AFFIX_DATA, affixData.copy());
        return entry;
    }

    static CompoundTag readBookEntry(CompoundTag entry, String spellId) {
        if (entry == null || entry.isEmpty()) return null;
        if (!entry.contains(BOOK_ENTRY_AFFIX_DATA, Tag.TAG_COMPOUND)) {
            return entry.contains(BOOK_ENTRY_SPELL_ID) ? null : entry.copy();
        }
        if (!spellId.equals(entry.getString(BOOK_ENTRY_SPELL_ID))) return null;
        CompoundTag affixData = entry.getCompound(BOOK_ENTRY_AFFIX_DATA);
        return affixData.isEmpty() ? null : affixData.copy();
    }

    public record Data(float dmg, float mana, float cd, float cast, int lvl,
                       float radius, float duration) {
        public static final Data DEF = new Data(1, 1, 1, 1, 0, 1, 1);

        public CompoundTag write() {
            CompoundTag t = new CompoundTag();
            t.putFloat("d", dmg);
            t.putFloat("m", mana);
            t.putFloat("c", cd);
            t.putFloat("t", cast);
            t.putInt("l", lvl);
            t.putFloat("r", radius);
            t.putFloat("du", duration);
            return t;
        }

        public static Data read(CompoundTag t) {
            if (t == null || t.isEmpty()) return DEF;
            return new Data(
                    multiplier(t, "d"),
                    multiplier(t, "m"),
                    multiplier(t, "c"),
                    multiplier(t, "t"),
                    t.contains("l", Tag.TAG_INT) ? Math.max(0, t.getInt("l")) : 0,
                    multiplier(t, "r"),
                    multiplier(t, "du")
            );
        }

        private static float multiplier(CompoundTag tag, String key) {
            if (!tag.contains(key, Tag.TAG_ANY_NUMERIC)) return 1;
            float value = tag.getFloat(key);
            return Float.isFinite(value) && value >= 0 ? value : 1;
        }

        public boolean isDefault() {
            return dmg == 1 && mana == 1 && cd == 1 && cast == 1 && lvl == 0
                    && radius == 1 && duration == 1;
        }
    }

    // ============================ 复合读取（核心修复） ============================

    /**
     * 从旧 SpellSlot 子标签的 affix_data 实时计算 Data。
     */
    public static Data fromSlotAffixData(CompoundTag slotTag) {
        if (slotTag == null) return null;
        CompoundTag affixData = slotTag.getCompound(SLOT_AFFIX_DATA);
        if (affixData.isEmpty()) return null;
        Data d = computeData(affixData);
        return d.isDefault() ? null : d;
    }

    /**
     * 从物品顶层 affix_data 读 Data（重铸场景）。
     */
    public static Data fromItemAffixData(ItemStack stack) {
        if (stack.isEmpty()) return null;
        CompoundTag affixData = stack.getTagElement(AffixHelper.AFFIX_DATA);
        if (affixData == null || affixData.isEmpty()) return null;
        Data d = computeData(affixData);
        return d.isDefault() ? null : d;
    }

    /**
     * 复合读取：物品顶层 affix_data 为权威来源，槽内数据仅作旧数据回退。
     */
    public static Data read(CompoundTag slotTag, ItemStack stack) {
        Data d = fromItemAffixData(stack);
        if (d != null) return d;
        d = fromSlotAffixData(slotTag);
        if (d != null) return d;
        return Data.DEF;
    }

    // ============================ 兼容旧 API ============================

    public static Data get(ItemStack stack) {
        if (stack.isEmpty()) return Data.DEF;
        if (stack.getItem() instanceof Scroll) {
            return getFromScroll(stack);
        }
        return Data.DEF;
    }

    public static Data getFromScroll(ItemStack scroll) {
        if (scroll.isEmpty() || !(scroll.getItem() instanceof Scroll)) return Data.DEF;
        CompoundTag affixData = scrollAffixData(scroll);
        return affixData == null ? Data.DEF : computeData(affixData);
    }

    public static Data getFromSpellBook(ItemStack book, int spellIndex) {
        if (book.isEmpty() || !(book.getItem() instanceof SpellBook)) return Data.DEF;
        if (spellIndex < 0) return Data.DEF;
        CompoundTag affixData = getBookAffix(book, spellIndex);
        return affixData == null ? Data.DEF : computeData(affixData);
    }

    /**
     * 综合查找：从玩家当前施法物品解析 Data。
     */
    public static Data resolveDataFromStack(ItemStack item, Player player) {
        if (item.isEmpty()) return Data.DEF;
        if (item.getItem() instanceof Scroll) {
            return getFromScroll(item);
        }
        if (item.getItem() instanceof SpellBook) {
            int idx = resolveSelectedSpellIndex(item, player);
            return getFromSpellBook(item, idx);
        }
        return Data.DEF;
    }

    // ============================ SpellSlot 子标签操作 ============================

    public static CompoundTag getSlotTag(ItemStack stack, int index) {
        if (stack.isEmpty()) return null;
        if (!ISpellContainer.isSpellContainer(stack)) return null;
        CompoundTag root = getContainerTag(stack);
        if (root == null) return null;
        ListTag data = root.getList("data", 10);
        // 按 SpellSlot 的 index 字段匹配，而非列表下标。移除法术后 "data" 列表会紧凑
        // （getActiveSpells 只含非空槽），列表下标 != index 字段，按下标读会读错/读不到对应法术的词缀。
        for (int i = 0; i < data.size(); i++) {
            CompoundTag slot = data.getCompound(i);
            if (slot.contains("index", Tag.TAG_INT) && slot.getInt("index") == index) return slot;
        }
        return null;
    }

    public static void putSlotTag(ItemStack stack, int index, CompoundTag slotTag) {
        if (stack.isEmpty() || slotTag == null) return;
        if (!ISpellContainer.isSpellContainer(stack)) return;
        CompoundTag root = getContainerTag(stack);
        if (root == null) return;
        ListTag data = root.getList("data", 10);
        // 同 getSlotTag：按 index 字段定位列表项，而非列表下标。
        for (int i = 0; i < data.size(); i++) {
            if (data.getCompound(i).contains("index", Tag.TAG_INT) && data.getCompound(i).getInt("index") == index) {
                data.set(i, slotTag);
                root.put("data", data);
                return;
            }
        }
    }

    private static CompoundTag getContainerTag(ItemStack stack) {
        CompoundTag root = stack.getTagElement(ISpellContainer.NBT);
        return root != null ? root : stack.getTagElement(ISpellContainer.LEGACY_NBT);
    }

    private static String getSlotSpellId(ItemStack stack, int index) {
        CompoundTag slot = getSlotTag(stack, index);
        if (slot == null || !slot.contains("id", Tag.TAG_STRING)) return null;
        String spellId = slot.getString("id");
        return spellId.isEmpty() ? null : spellId;
    }

    public static Data getFromSlot(CompoundTag slotTag) {
        if (slotTag == null) return Data.DEF;
        Data live = fromSlotAffixData(slotTag);
        return live != null ? live : Data.DEF;
    }

    // ============================ selectionIndex 解析 ============================

    public static int resolveSelectedSpellIndex(ItemStack spellBook, Player player) {
        if (player == null || spellBook.isEmpty()) return -1;
        try {
            var manager = player.level().isClientSide()
                    ? io.redspace.ironsspellbooks.player.ClientMagicData.getSpellSelectionManager()
                    : new io.redspace.ironsspellbooks.api.magic.SpellSelectionManager(player);
            SpellCastHooks.Context context = SpellCastHooks.resolveSelection(player, manager.getSelection());
            return context != null && context.stack() == spellBook ? context.spellSlotIndex() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ============================ 同步入口 ============================

    public static void sync(ItemStack stack) {
        if (stack.isEmpty()) return;
        if (stack.getItem() instanceof Scroll) {
            syncScroll(stack, 0);
        } else if (stack.getItem() instanceof SpellBook) {
            migrateBook(stack);
        }
    }

    public static void syncScroll(ItemStack scroll, int slotIndex) {
        if (scroll.isEmpty() || !(scroll.getItem() instanceof Scroll)) return;
        scroll.removeTagKey(KEY);
        CompoundTag slot = ISpellContainer.isSpellContainer(scroll) ? getSlotTag(scroll, slotIndex) : null;
        CompoundTag itemAffix = scroll.getTagElement(AffixHelper.AFFIX_DATA);
        if (slot != null) {
            if (itemAffix == null && slot.contains(SLOT_AFFIX_DATA, Tag.TAG_COMPOUND)) {
                CompoundTag legacy = slot.getCompound(SLOT_AFFIX_DATA);
                if (!legacy.isEmpty()) scroll.addTagElement(AffixHelper.AFFIX_DATA, legacy.copy());
            }
            slot.remove(SLOT_AFFIX_DATA);
            slot.remove(KEY);
            putSlotTag(scroll, slotIndex, slot);
        }
    }

    public static void syncScrollSlot(ItemStack stack, int slotIndex) {
        if (stack.isEmpty() || !(stack.getItem() instanceof Scroll)) return;
        syncScroll(stack, slotIndex);
    }

    public static void syncSpellBookSlot(ItemStack book, int spellIndex) {
        migrateBook(book);
    }

    public static void syncSlotTag(CompoundTag slotTag) {
        if (slotTag == null) return;
        slotTag.remove(KEY);
    }

    // ============================ computeData（保持不变） ============================

    public static Data computeData(CompoundTag affixData) {
        if (affixData == null || affixData.isEmpty()) return Data.DEF;

        float d = 1, m = 1, c = 1, ct = 1;
        int lv = 0;
        float radius = 1, duration = 1;

        LootRarity rarity = resolveRarity(affixData);
        if (rarity == null) return Data.DEF;

        CompoundTag affixesTag = affixData.getCompound(AffixHelper.AFFIXES);
        for (String key : affixesTag.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id == null) continue;
            DynamicHolder<Affix> holder = AffixRegistry.INSTANCE.holder(id);
            if (!holder.isBound()) continue;
            Affix affix = holder.get();
            if (affix == null) continue;
            float lvl = affixesTag.getFloat(key);

            if (affix instanceof com.example.apotheosis_spells.affix.SpellAffix sa) {
                int v = sa.getBaseValue(rarity, lvl);
                if (v == 0) continue;
                Data d2 = sa.contribute(v);
                d = d * d2.dmg();
                m = m * d2.mana();
                c = c * d2.cd();
                ct = ct * d2.cast();
                lv = Math.max(lv, d2.lvl());
                radius = radius * d2.radius();
                duration = duration * d2.duration();
            }
        }

        try {
            ListTag gemList = affixData.getList("gems", Tag.TAG_COMPOUND);
            for (Tag tag : gemList) {
                ItemStack gemStack = ItemStack.of((CompoundTag) tag);
                com.example.apotheosis_spells.gem.GemRegistryHook.ResolvedBonus resolved =
                        com.example.apotheosis_spells.gem.GemRegistryHook.resolve(gemStack);
                if (resolved == null) continue;
                Data d2 = resolved.contribute();
                d = d * d2.dmg();
                m = m * d2.mana();
                c = c * d2.cd();
                ct = ct * d2.cast();
                lv = Math.max(lv, d2.lvl());
                radius = radius * d2.radius();
                duration = duration * d2.duration();
            }
        } catch (Throwable t) {
            ApotheosisSpells.LOGGER.debug("[ReforgeCache] failed to read gem data", t);
        }

        return new Data(d, m, c, ct, lv, radius, duration);
    }

    // ============================ computeEffects（事件类特效层，不缓存，实时算）============================

    /**
     * 从 affixData 聚合「事件类特效」（吸血/暴击/斩杀/…）。与 {@link #computeData} 并行、互不影响：
     * 倍率类走 Data + 各倍率钩子，特效类走 SpellEffects + SpellEffectHandler 的事件。
     */
    public static SpellEffects computeEffects(CompoundTag affixData) {
        if (affixData == null || affixData.isEmpty()) return SpellEffects.NONE;
        SpellEffects acc = SpellEffects.NONE;
        LootRarity rarity = resolveRarity(affixData);
        if (rarity == null) return SpellEffects.NONE;
        CompoundTag affixesTag = affixData.getCompound(AffixHelper.AFFIXES);
        for (String key : affixesTag.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id == null) continue;
            DynamicHolder<Affix> holder = AffixRegistry.INSTANCE.holder(id);
            if (!holder.isBound()) continue;
            Affix affix = holder.get();
            if (affix instanceof com.example.apotheosis_spells.affix.SpellAffix sa) {
                acc = acc.merge(sa.resolveEffects(rarity, affixesTag.getFloat(key)));
            }
        }
        return acc;
    }

    /** 卷轴当前生效的 affixData：物品顶层为权威来源，SpellSlot 0 仅兼容旧数据。 */
    public static CompoundTag scrollAffixData(ItemStack scroll) {
        if (scroll.isEmpty()) return null;
        CompoundTag topLevel = scroll.getTagElement(AffixHelper.AFFIX_DATA);
        CompoundTag slot = ISpellContainer.isSpellContainer(scroll) ? getSlotTag(scroll, 0) : null;
        return preferredScrollAffixData(topLevel, slot);
    }

    static CompoundTag preferredScrollAffixData(CompoundTag topLevel, CompoundTag slot) {
        if (topLevel != null) return topLevel;
        if (slot != null) {
            CompoundTag legacy = slot.getCompound(SLOT_AFFIX_DATA);
            if (!legacy.isEmpty()) return legacy;
        }
        return null;
    }

    /** 卷轴的事件类特效（实时算）。 */
    public static SpellEffects getEffectsFromScroll(ItemStack scroll) {
        if (scroll.isEmpty() || !(scroll.getItem() instanceof Scroll)) return SpellEffects.NONE;
        return computeEffects(scrollAffixData(scroll));
    }

    /** 法术书某物理槽的事件类特效（实时算，权威来源 = 书顶层并行存储）。 */
    public static SpellEffects getEffectsFromSpellBook(ItemStack book, int spellIndex) {
        if (book.isEmpty() || !(book.getItem() instanceof SpellBook) || spellIndex < 0) return SpellEffects.NONE;
        return computeEffects(getBookAffix(book, spellIndex));
    }

    @Nullable
    public static LootRarity resolveRarity(CompoundTag affixData) {
        if (!Apotheosis.enableAdventure) return null;
        var rarities = RarityRegistry.INSTANCE.getOrderedRarities();
        if (rarities.isEmpty()) return null;
        String rarityId = affixData == null ? "" : affixData.getString(AffixHelper.RARITY);
        ResourceLocation id = ResourceLocation.tryParse(rarityId.contains(":") ? rarityId : Apotheosis.MODID + ":" + rarityId);
        if (id != null) {
            DynamicHolder<LootRarity> holder = RarityRegistry.INSTANCE.holder(id);
            if (holder.isBound()) return holder.get();
        }
        DynamicHolder<LootRarity> minimum = rarities.get(0);
        return minimum.isBound() ? minimum.get() : null;
    }

    // ============================ 工具方法 ============================

    public static void clearSlot(CompoundTag slotTag) {
        if (slotTag == null) return;
        slotTag.remove(SLOT_AFFIX_DATA);
        slotTag.remove(KEY);
    }

    public static void rebuildAffixesToScroll(ItemStack scroll, CompoundTag affixData) {
        if (scroll.isEmpty() || affixData == null || affixData.isEmpty()) return;
        scroll.addTagElement(AffixHelper.AFFIX_DATA, affixData.copy());
        syncScroll(scroll, 0);
    }

    public static ItemStack restoreBookSpell(ItemStack book, int index) {
        CompoundTag affixData = getBookAffix(book, index);
        if (affixData == null || affixData.isEmpty()) return ItemStack.EMPTY;
        SpellData spell = ISpellContainer.get(book).getSpellAtIndex(index);
        if (spell == null || spell == SpellData.EMPTY || spell.getSpell() == null) return ItemStack.EMPTY;
        return createAffixedScroll(spell.getSpell(), spell.getLevel(), affixData);
    }

    public static ItemStack createAffixedScroll(AbstractSpell spell, int level, CompoundTag affixData) {
        ItemStack scroll = new ItemStack(ItemRegistry.SCROLL.get());
        ISpellContainer.createScrollContainer(spell, level, scroll);
        rebuildAffixesToScroll(scroll, affixData);
        return scroll;
    }
}
