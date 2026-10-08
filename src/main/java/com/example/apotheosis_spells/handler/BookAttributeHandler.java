package com.example.apotheosis_spells.handler;

import com.example.apotheosis_spells.ApotheosisSpells;
import com.example.apotheosis_spells.ApotheosisSpells.Diagnostics;
import com.example.apotheosis_spells.api.ReforgeCache;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixInstance;
import dev.shadowsoffire.apotheosis.adventure.socket.SocketHelper;
import io.redspace.ironsspellbooks.api.magic.SpellSelectionManager;
import io.redspace.ironsspellbooks.item.SpellBook;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 铭刻进法术书的「属性词条 / 属性宝石」生效路径。
 *
 * 手持卷轴时，apotheosis:attribute 词条与宝石属性加成走 Apotheosis 原生的
 * ItemAttributeModifierEvent（scroll 类别注册了 MAINHAND/OFFHAND），无需本类。
 * 但铭刻进法术书后，词条数据只存在于书顶层的 apoth_book_affixes（按物理槽位索引），
 * 书本身不是 scroll 类别，原生钩子不会触发——需求是「仅当玩家当前选中该法术时生效」。
 *
 * 实现：服务端每 tick 与施法前解析当前选中法术，词条和法术信息未变时复用属性缓存：
 *   选中项来自装备中的法术书（排除主/副手卷轴——那是原生路径，避免双重加成）
 *   → 按选择项的实际装备来源解析物理槽位 → 取出存储的 affix_data
 *   → 还原成虚拟卷轴 ItemStack，借 Apotheosis 的 AffixInstance/SocketedGems.addModifiers
 *     收集属性修饰符 → 以本 mod 命名空间的确定性 UUID 作为临时修饰符挂到玩家身上。
 * 选中变化/换书/取消选择时撤销重挂；临时修饰符不落盘，重登由本类自然重建。
 */
@Mod.EventBusSubscriber(modid = ApotheosisSpells.MODID)
public class BookAttributeHandler {

    private static final String MODIFIER_NAME = "apotheosis_spells:book_affix";
    private static final UUID MODIFIER_NAMESPACE = UUID.nameUUIDFromBytes(MODIFIER_NAME.getBytes(StandardCharsets.UTF_8));

    /** 玩家 UUID -> 已挂修饰符（用于撤销）。仅服务端线程访问。 */
    private static final Map<UUID, List<Applied>> APPLIED = new HashMap<>();
    private static final Map<UUID, CachedSelection> CACHED = new HashMap<>();
    private static final Map<UUID, List<SpellCastHooks.AttributeBonus>> SCOPED = new HashMap<>();
    private static final AtomicLong RELOAD_VERSION = new AtomicLong();

    private record Applied(Attribute attribute, UUID id) {}
    private record SelectedSpell(String spellId, int level, CompoundTag affixData) {}
    private record CachedSelection(SelectedSpell spell, long reloadVersion) {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        refresh(player);
    }

    public static void refresh(ServerPlayer player) {
        try {
            List<SpellCastHooks.AttributeBonus> scoped = SCOPED.get(player.getUUID());
            if (scoped != null) applyBonuses(player, scoped, false);
            else refreshSelected(player);
        } catch (Exception e) {
            removeApplied(player);
            CACHED.remove(player.getUUID());
            ApotheosisSpells.LOGGER.warn("BookAttributeHandler refresh failed", e);
        }
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener((ResourceManagerReloadListener) resourceManager -> RELOAD_VERSION.incrementAndGet());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) removeApplied(player);
        forget(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        // 死亡/末地返回会换新实体，属性表是新的：丢掉缓存，下个周期在新实体上重挂。
        forget(event.getOriginal().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        APPLIED.clear();
        CACHED.clear();
        SCOPED.clear();
    }

    private static void forget(UUID id) {
        APPLIED.remove(id);
        CACHED.remove(id);
        SCOPED.remove(id);
    }

    private static void refreshSelected(ServerPlayer player) {
        SelectedSpell selected = dev.shadowsoffire.apotheosis.Apotheosis.enableAdventure
                ? resolveSelectedBookAffixes(player) : null;
        long reloadVersion = RELOAD_VERSION.get();
        CachedSelection cached = CACHED.get(player.getUUID());
        if (cached != null && cached.reloadVersion() == reloadVersion
            && Objects.equals(cached.spell(), selected)) {
            if (Diagnostics.enabled()) {
                for (Applied modifier : APPLIED.getOrDefault(player.getUUID(), List.of())) {
                    var attribute = player.getAttribute(modifier.attribute());
                    if (attribute == null || attribute.getModifier(modifier.id()) == null) {
                        Diagnostics.calculation("ATTR_CACHE_MISSING", player.getUUID() + ":" + modifier.id(),
                                "attribute=" + modifier.attribute().getDescriptionId() + " cachedSelection=" + selected);
                    }
                }
            }
            return;
        }

        List<SpellCastHooks.AttributeBonus> bonuses = List.of();
        if (selected != null) {
            List<Map.Entry<Attribute, AttributeModifier>> collected = collectModifiers(selected);
            Diagnostics.log("ATTR_COLLECT", () -> Diagnostics.player(player) + " spell=" + selected.spellId()
                    + " level=" + selected.level() + " collected=" + collected + " rawAffixes=" + selected.affixData());
            bonuses = toBonuses(collected);
        }
        applyBonuses(player, bonuses, true);
        CACHED.put(player.getUUID(), new CachedSelection(selected, reloadVersion));
    }

    public static List<SpellCastHooks.AttributeBonus> capture(SpellCastHooks.Context context) {
        if (!dev.shadowsoffire.apotheosis.Apotheosis.enableAdventure
                || context == null || context.stack() == null || !(context.stack().getItem() instanceof SpellBook)
                || context.spellData() == null || context.spellData().getSpell() == null) return List.of();
        CompoundTag data = ReforgeCache.getBookAffix(context.stack(), context.spellSlotIndex());
        if (data == null || data.isEmpty()) return List.of();
        return toBonuses(collectModifiers(new SelectedSpell(context.spellData().getSpell().getSpellId(),
                context.spellData().getLevel(), data)));
    }

    public interface PreviewAttributeSource {
        AttributeInstance apoth$copyAttribute(Attribute attribute);
    }

    static AttributeInstance previewAttribute(Player player, Attribute attribute,
                                               List<SpellCastHooks.AttributeBonus> bonuses) {
        AttributeInstance preview = ((PreviewAttributeSource) player.getAttributes()).apoth$copyAttribute(attribute);
        if (preview == null) return null;
        for (var modifier : preview.getModifiers()) {
            if (modifier.getId().getMostSignificantBits() == MODIFIER_NAMESPACE.getMostSignificantBits()) {
                preview.removeModifier(modifier.getId());
            }
        }
        var key = net.minecraftforge.registries.ForgeRegistries.ATTRIBUTES.getKey(attribute);
        if (key == null) return preview;
        int index = 0;
        for (var bonus : bonuses) {
            if (key.toString().equals(bonus.attribute()) && Double.isFinite(bonus.amount())) {
                preview.addTransientModifier(new AttributeModifier(modifierId(index++), MODIFIER_NAME,
                        bonus.amount(), bonus.operation()));
            }
        }
        return preview;
    }

    private static UUID modifierId(int index) {
        return new UUID(MODIFIER_NAMESPACE.getMostSignificantBits(), MODIFIER_NAMESPACE.getLeastSignificantBits() + index);
    }

    private static List<SpellCastHooks.AttributeBonus> toBonuses(List<Map.Entry<Attribute, AttributeModifier>> modifiers) {
        return modifiers.stream().map(entry -> new SpellCastHooks.AttributeBonus(
                net.minecraftforge.registries.ForgeRegistries.ATTRIBUTES.getKey(entry.getKey()).toString(),
                entry.getValue().getAmount(), entry.getValue().getOperation())).toList();
    }

    private static List<SpellCastHooks.AttributeBonus> appliedBonuses(ServerPlayer player) {
        List<Map.Entry<Attribute, AttributeModifier>> modifiers = new ArrayList<>();
        for (var applied : APPLIED.getOrDefault(player.getUUID(), List.of())) {
            AttributeInstance instance = player.getAttribute(applied.attribute());
            AttributeModifier modifier = instance == null ? null : instance.getModifier(applied.id());
            if (modifier != null) modifiers.add(Map.entry(applied.attribute(), modifier));
        }
        return toBonuses(modifiers);
    }

    public static final class AttributeScope implements AutoCloseable {
        private final ServerPlayer player;
        private final List<SpellCastHooks.AttributeBonus> previous;
        private final List<SpellCastHooks.AttributeBonus> previousOverride;
        private boolean closed;

        public AttributeScope(ServerPlayer player, List<SpellCastHooks.AttributeBonus> bonuses) {
            this.player = player;
            previous = appliedBonuses(player);
            previousOverride = SCOPED.put(player.getUUID(), bonuses);
            applyBonuses(player, bonuses, false);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previousOverride == null) SCOPED.remove(player.getUUID());
            else SCOPED.put(player.getUUID(), previousOverride);
            applyBonuses(player, previous, false);
            if (previousOverride == null) refresh(player);
        }
    }

    private static void applyBonuses(ServerPlayer player, List<SpellCastHooks.AttributeBonus> bonuses, boolean trace) {
        if (!dev.shadowsoffire.apotheosis.Apotheosis.enableAdventure) bonuses = List.of();
        if (appliedBonuses(player).equals(bonuses)) return;
        removeApplied(player, trace);
        List<Applied> applied = new ArrayList<>();
        APPLIED.put(player.getUUID(), applied);
        int i = 0;
        for (var bonus : bonuses) {
            var attribute = net.minecraftforge.registries.ForgeRegistries.ATTRIBUTES.getValue(
                    net.minecraft.resources.ResourceLocation.tryParse(bonus.attribute()));
            if (attribute == null || !Double.isFinite(bonus.amount())) continue;
            AttributeInstance inst = player.getAttribute(attribute);
            if (inst == null) {
                if (trace) Diagnostics.log("ATTR_MISSING", () -> Diagnostics.player(player) + " attribute=" + bonus.attribute());
                continue;
            }
            double before = inst.getValue();
            UUID id = modifierId(i++);
            if (inst.getModifier(id) != null) inst.removeModifier(id);
            inst.addTransientModifier(new AttributeModifier(id, MODIFIER_NAME, bonus.amount(), bonus.operation()));
            applied.add(new Applied(attribute, id));
            if (trace) Diagnostics.log("ATTR_APPLY", () -> Diagnostics.player(player) + " attribute=" + bonus.attribute()
                    + " amount=" + bonus.amount() + " operation=" + bonus.operation() + " uuid=" + id
                    + " before=" + before + " after=" + inst.getValue() + " allModifiers=" + inst.getModifiers());
        }
        if (applied.isEmpty()) APPLIED.remove(player.getUUID());
    }

    private static void removeApplied(ServerPlayer player) {
        removeApplied(player, true);
    }

    private static void removeApplied(ServerPlayer player, boolean trace) {
        List<Applied> old = APPLIED.remove(player.getUUID());
        if (old != null) {
            for (Applied a : old) {
                AttributeInstance inst = player.getAttribute(a.attribute());
                if (inst != null) {
                    double before = inst.getValue();
                    inst.removeModifier(a.id());
                    if (trace) Diagnostics.log("ATTR_REMOVE", () -> Diagnostics.player(player) + " attribute=" + a.attribute().getDescriptionId()
                            + " uuid=" + a.id() + " before=" + before + " after=" + inst.getValue());
                }
            }
        }
    }

    /**
     * 当前选中法术若来自装备的法术书且该槽位铭刻时带了词条数据，返回其 affix_data；否则 null。
     */
    private static SelectedSpell resolveSelectedBookAffixes(ServerPlayer player) {
        SpellSelectionManager manager = new SpellSelectionManager(player);
        SpellSelectionManager.SelectionOption selection = manager.getSelection();
        if (selection == null || selection.spellData == null || selection.spellData.getSpell() == null) {
            Diagnostics.selection(player, null, ItemStack.EMPTY, -1, null, "NO_SELECTION");
            return null;
        }
        ItemStack book = SpellCastHooks.equippedStack(player, selection.slot);
        if (!(book.getItem() instanceof SpellBook)) {
            Diagnostics.selection(player, selection, book, -1, null, "SOURCE_NOT_BOOK");
            return null;
        }
        var slot = SpellCastHooks.resolveSelectionSlot(book, selection);
        if (slot == null) {
            Diagnostics.selection(player, selection, book, -1, null, "SLOT_MISMATCH");
            return null;
        }
        CompoundTag affixData = ReforgeCache.getBookAffix(book, slot.index());
        Diagnostics.selection(player, selection, book, slot.index(), affixData,
                affixData == null || affixData.isEmpty() ? "NO_AFFIX_DATA" : "RESOLVED");
        if (affixData == null || affixData.isEmpty() || ReforgeCache.resolveRarity(affixData) == null) return null;
        return new SelectedSpell(slot.getSpell().getSpellId(), slot.spellData().getLevel(), affixData);
    }

    /**
     * 把存储的 affix_data 还原到一个虚拟卷轴上，用 Apotheosis 自己的逻辑取属性修饰符
     * （词条 + 镶嵌宝石都覆盖，数值与手持卷轴时完全一致）。
     */
    private static List<Map.Entry<Attribute, AttributeModifier>> collectModifiers(SelectedSpell selected) {
        if (ReforgeCache.resolveRarity(selected.affixData()) == null) return List.of();
        List<Map.Entry<Attribute, AttributeModifier>> collected = new ArrayList<>();
        ItemStack virtual = ReforgeCache.createAffixedScroll(SpellRegistry.getSpell(selected.spellId()),
                selected.level(), selected.affixData());
        Diagnostics.log("ATTR_VIRTUAL_SCROLL", () -> Diagnostics.stack(virtual, true)
                + " nativeAffixes=" + AffixHelper.getAffixes(virtual).keySet());

        for (AffixInstance inst : AffixHelper.getAffixes(virtual).values()) {
            try {
                inst.addModifiers(EquipmentSlot.MAINHAND, (attr, m) -> collected.add(Map.entry(attr, m)));
            } catch (Exception e) {
                ApotheosisSpells.LOGGER.debug("[BookAttributeHandler] addModifiers affix failed", e);
                Diagnostics.log("ATTR_AFFIX_ERROR", () -> "spell=" + selected.spellId() + " affix=" + inst + " error=" + e);
            }
        }
        try {
            SocketHelper.getGems(virtual).addModifiers(ScrollLootCategory.SCROLL, EquipmentSlot.MAINHAND,
                (attr, m) -> collected.add(Map.entry(attr, m)));
        } catch (Exception e) {
            ApotheosisSpells.LOGGER.debug("[BookAttributeHandler] addModifiers gems failed", e);
            Diagnostics.log("ATTR_GEM_ERROR", () -> "spell=" + selected.spellId() + " error=" + e);
        }
        return collected;
    }
}
