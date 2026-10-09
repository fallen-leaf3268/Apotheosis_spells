package com.example.apotheosis_spells.handler;

import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.api.SpellEffects;
import io.redspace.ironsspellbooks.api.magic.SpellSelectionManager;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.api.spells.SpellSlot;
import io.redspace.ironsspellbooks.damage.SpellDamageSource;
import io.redspace.ironsspellbooks.item.Scroll;
import io.redspace.ironsspellbooks.item.SpellBook;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

public final class SpellCastHooks {
    public static final String SNAPSHOT_KEY = "apotheosis_spells:cast";
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Snapshot> SNAPSHOT = new ThreadLocal<>();
    private static final ThreadLocal<PreviewAttributes> PREVIEW = new ThreadLocal<>();
    private static final ThreadLocal<CastSelection> SOURCE = new ThreadLocal<>();
    private static final Map<UUID, CastSession> ACTIVE = new HashMap<>();
    private static final Map<Entity, Snapshot> ENTITIES = new WeakHashMap<>();

    private SpellCastHooks() {}

    public record Context(ItemStack stack, Player caster, int spellSlotIndex, int spellLevel,
                          ReforgeCache.Data data, SpellData spellData, boolean castContext) {
        public Context(ItemStack stack, Player caster, int spellSlotIndex, int spellLevel,
                       ReforgeCache.Data data, SpellData spellData) {
            this(stack, caster, spellSlotIndex, spellLevel, data, spellData, false);
        }
    }

    public record AttributeBonus(String attribute, double amount,
                                 net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation operation) {}

    public record Snapshot(UUID owner, String spellId, int spellLevel,
                           ReforgeCache.Data data, SpellEffects effects, java.util.List<AttributeBonus> attributes, boolean echo) {
        public Snapshot(UUID owner, String spellId, int spellLevel, ReforgeCache.Data data, SpellEffects effects) {
            this(owner, spellId, spellLevel, data, effects, java.util.List.of());
        }

        public Snapshot(UUID owner, String spellId, int spellLevel, ReforgeCache.Data data, SpellEffects effects,
                        java.util.List<AttributeBonus> attributes) {
            this(owner, spellId, spellLevel, data, effects, attributes, false);
        }

        public Snapshot { attributes = java.util.List.copyOf(attributes); }

        public Snapshot asEcho() {
            return echo ? this : new Snapshot(owner, spellId, spellLevel, data, effects, attributes, true);
        }

        @Override
        public String toString() {
            return "Snapshot[owner=" + owner + ", spellId=" + spellId + ", spellLevel=" + spellLevel
                    + ", data=" + data + ", effects=" + effects + ", attributeCount=" + attributes.size() + ", echo=" + echo + ']';
        }

        public CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("owner", owner);
            tag.putString("spell", spellId);
            tag.putInt("level", spellLevel);
            tag.put("data", data.write());
            tag.put("effects", effects.write());
            if (echo) tag.putBoolean("echo", true);
            var bonuses = new net.minecraft.nbt.ListTag();
            for (var bonus : attributes) {
                CompoundTag value = new CompoundTag();
                value.putString("attribute", bonus.attribute());
                value.putDouble("amount", bonus.amount());
                value.putInt("operation", bonus.operation().toValue());
                bonuses.add(value);
            }
            tag.put("attributes", bonuses);
            return tag;
        }

        public static Snapshot read(CompoundTag tag) {
            if (!tag.hasUUID("owner") || ResourceLocation.tryParse(tag.getString("spell")) == null) return null;
            java.util.List<AttributeBonus> bonuses = new java.util.ArrayList<>();
            for (var value : tag.getList("attributes", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                CompoundTag bonus = (CompoundTag) value;
                int operation = bonus.getInt("operation");
                if (ResourceLocation.tryParse(bonus.getString("attribute")) == null
                        || !Double.isFinite(bonus.getDouble("amount")) || operation < 0 || operation > 2) continue;
                bonuses.add(new AttributeBonus(bonus.getString("attribute"), bonus.getDouble("amount"),
                        net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.fromValue(operation)));
            }
            return new Snapshot(tag.getUUID("owner"), tag.getString("spell"), Math.max(1, tag.getInt("level")),
                    ReforgeCache.Data.read(tag.getCompound("data")), SpellEffects.read(tag.getCompound("effects")), bonuses,
                    tag.getBoolean("echo"));
        }
    }

    public interface SnapshotCarrier {
        Snapshot apoth$getSnapshot();
        void apoth$setSnapshot(Snapshot snapshot);
    }

    public static final class CastSession {
        private final Snapshot snapshot;
        private boolean successful;
        private boolean finished;

        public CastSession(Snapshot snapshot) { this.snapshot = snapshot; }
        public void markSuccessful() { if (!finished) successful = true; }
        public boolean complete(boolean cancelled) {
            if (finished) return false;
            finished = true;
            return successful && !cancelled;
        }
    }

    public static final class Scope implements AutoCloseable {
        private final Context previousContext;
        private final Snapshot previousSnapshot;
        private final PreviewAttributes previousPreview;
        private final BookAttributeHandler.AttributeScope attributes;
        private boolean closed;

        private Scope(Context context, Snapshot snapshot) {
            previousContext = CURRENT.get();
            previousSnapshot = SNAPSHOT.get();
            previousPreview = PREVIEW.get();
            PreviewAttributes preview = snapshot == null && context != null && !context.castContext()
                    && context.caster() != null
                    ? new PreviewAttributes(context.caster(), BookAttributeHandler.capture(context)) : null;
            setPreview(null);
            try {
                attributes = snapshot != null && context != null
                        && context.caster() instanceof net.minecraft.server.level.ServerPlayer player
                        && snapshot.owner().equals(player.getUUID())
                        ? new BookAttributeHandler.AttributeScope(player, snapshot.attributes()) : null;
            } catch (RuntimeException | Error failure) {
                setPreview(previousPreview);
                throw failure;
            }
            setCurrent(context, snapshot);
            setPreview(preview);
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                try {
                    if (attributes != null) attributes.close();
                } finally {
                    setCurrent(previousContext, previousSnapshot);
                    setPreview(previousPreview);
                }
            }
        }
    }

    private record PreviewAttributes(Player player, java.util.List<AttributeBonus> bonuses,
                                     Map<Attribute, AttributeInstance> values) {
        private PreviewAttributes(Player player, java.util.List<AttributeBonus> bonuses) {
            this(player, bonuses, new HashMap<>());
        }
    }

    private static void setPreview(PreviewAttributes preview) {
        if (preview == null) PREVIEW.remove(); else PREVIEW.set(preview);
    }

    public static AttributeInstance previewAttribute(LivingEntity caster, Attribute attribute) {
        PreviewAttributes preview = PREVIEW.get();
        return preview == null || preview.player() != caster ? null
                : preview.values().computeIfAbsent(attribute,
                        key -> BookAttributeHandler.previewAttribute(preview.player(), key, preview.bonuses()));
    }

    private record CastSelection(Player player, SpellSelectionManager.SelectionOption selection,
                                 ItemStack implement, String implementSlot) {}

    public static final class SourceScope implements AutoCloseable {
        private final CastSelection previous;
        private boolean closed;

        private SourceScope(CastSelection selection) {
            previous = SOURCE.get();
            SOURCE.set(selection);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) SOURCE.remove(); else SOURCE.set(previous);
        }
    }

    public static SourceScope enterSource(Player player, SpellSelectionManager.SelectionOption selection) {
        return new SourceScope(new CastSelection(player, selection, null, null));
    }

    public static SourceScope enterSource(Player player, SpellSelectionManager.SelectionOption selection,
                                         ItemStack implement, String equipmentSlot) {
        return new SourceScope(new CastSelection(player, selection, implement, equipmentSlot));
    }

    private static void setCurrent(Context context, Snapshot snapshot) {
        if (context == null) CURRENT.remove(); else CURRENT.set(context);
        if (snapshot == null) SNAPSHOT.remove(); else SNAPSHOT.set(snapshot);
    }

    public static Context get() { return CURRENT.get(); }
    public static Snapshot currentSnapshot() { return SNAPSHOT.get(); }
    public static void set(Context context) { setCurrent(context, null); PREVIEW.remove(); }
    public static void clear() { setCurrent(null, null); PREVIEW.remove(); }
    public static Scope enter(Context context) { return new Scope(context, null); }

    public static Scope enter(Snapshot snapshot, Player player) {
        Context context = snapshot == null ? null : new Context(ItemStack.EMPTY, player, -1, snapshot.spellLevel(),
                snapshot.data(), new SpellData(SpellRegistry.getSpell(snapshot.spellId()), snapshot.spellLevel()), true);
        return new Scope(context, snapshot);
    }

    public static boolean matches(AbstractSpell spell, Entity caster) {
        Context context = get();
        Snapshot snapshot = currentSnapshot();
        return context != null && context.data() != null
                && (caster == null || context.caster() == null || caster == context.caster())
                && (caster == null || snapshot == null || snapshot.owner().equals(caster.getUUID()))
                && context.spellData() != null && context.spellData().getSpell() != null
                && spell.getSpellId().equals(context.spellData().getSpell().getSpellId());
    }

    public static Context buildContext(ItemStack stack, Player caster, int index, int level, SpellData spellData) {
        ReforgeCache.Data data = ReforgeCache.Data.DEF;
        if (stack != null && !stack.isEmpty()) {
            if (stack.getItem() instanceof Scroll) data = ReforgeCache.getFromScroll(stack);
            else if (stack.getItem() instanceof SpellBook) data = ReforgeCache.getFromSpellBook(stack, index);
            if (spellData == null && index >= 0 && ISpellContainer.isSpellContainer(stack)) {
                spellData = ISpellContainer.get(stack).getSpellAtIndex(index);
            }
        }
        return new Context(stack, caster, index, level, data, spellData);
    }

    public static Context buildContext(ItemStack stack, Player caster, int index, int level) {
        return buildContext(stack, caster, index, level, null);
    }

    public static <T> T withPageContext(ItemStack stack, Player player, SpellSlot slot,
                                        java.util.function.Function<SpellSlot, T> render) {
        try (var scope = enter(buildContext(stack, player, slot.index(), slot.getLevel(), slot.spellData()))) {
            return render.apply(slot);
        }
    }

    public static Context resolveSelection(Player player, SpellSelectionManager.SelectionOption selection) {
        if (player == null || selection == null || selection.spellData == null) return null;
        ItemStack stack = equippedStack(player, selection.slot);
        SpellSlot slot = resolveSelectionSlot(stack, selection);
        return slot == null ? null : buildContext(stack, player, slot.index(), slot.spellData().getLevel(), slot.spellData());
    }

    public static SpellSlot resolveSelectionSlot(ItemStack stack, SpellSelectionManager.SelectionOption selection) {
        if (selection == null || selection.spellData == null) return null;
        if (stack.isEmpty() || !ISpellContainer.isSpellContainer(stack)) return null;
        var spells = ISpellContainer.get(stack).getActiveSpells();
        if (selection.slotIndex < 0 || selection.slotIndex >= spells.size()) return null;
        SpellSlot slot = spells.get(selection.slotIndex);
        if (!slot.spellData().getSpell().equals(selection.spellData.getSpell())) return null;
        return slot;
    }

    public static Context resolveSource(Player player, ItemStack stack, String equipmentSlot, String spellId, int level) {
        if (player == null) return null;
        CastSelection requested = SOURCE.get();
        if (requested != null && requested.player() == player) {
            Context selected = resolveSelection(player, requested.selection());
            if (selected == null || !selected.spellData().getSpell().getSpellId().equals(spellId)) return null;
            boolean sourceMatches = requested.selection().slot.equals(equipmentSlot)
                    && (stack == null || stack.isEmpty() || stack == selected.stack());
            boolean implementMatches = requested.implement() != null && !requested.implement().isEmpty()
                    && requested.implement() == stack && java.util.Objects.equals(requested.implementSlot(), equipmentSlot);
            if (!sourceMatches && !implementMatches) return null;
            return new Context(selected.stack(), player, selected.spellSlotIndex(), level, selected.data(), selected.spellData());
        }
        ItemStack source = stack == null ? ItemStack.EMPTY : stack;
        if (source.isEmpty() || !(source.getItem() instanceof Scroll || source.getItem() instanceof SpellBook)) {
            source = equippedStack(player, equipmentSlot);
        }
        if (source.isEmpty() && (equipmentSlot == null || equipmentSlot.isEmpty())) {
            var selection = new SpellSelectionManager(player).getSelection();
            Context selected = resolveSelection(player, selection);
            if (selected != null && selected.spellData().getSpell().getSpellId().equals(spellId)) return selected;
            return null;
        }
        if (source.isEmpty() || !ISpellContainer.isSpellContainer(source)) return null;
        Context selected = resolveSelection(player, new SpellSelectionManager(player).getSelection());
        if (selected != null && selected.stack() == source
                && selected.spellData().getSpell().getSpellId().equals(spellId)) {
            return new Context(source, player, selected.spellSlotIndex(), level, selected.data(), selected.spellData());
        }
        SpellSlot match = null;
        for (SpellSlot slot : ISpellContainer.get(source).getActiveSpells()) {
            if (slot.spellData().getSpell().getSpellId().equals(spellId)) {
                if (match != null) return null;
                match = slot;
            }
        }
        return match == null ? null : buildContext(source, player, match.index(), level, match.spellData());
    }

    public static ItemStack equippedStack(Player player, String slot) {
        if (player == null || slot == null || slot.isEmpty()) return ItemStack.EMPTY;
        for (EquipmentSlot equipment : EquipmentSlot.values()) {
            if (equipment.getName().equals(slot)) return player.getItemBySlot(equipment);
        }
        int separator = slot.lastIndexOf('_');
        String identifier = slot;
        int index = 0;
        if (separator >= 0) {
            try {
                index = Integer.parseInt(slot.substring(separator + 1));
                identifier = slot.substring(0, separator);
            } catch (NumberFormatException ignored) {
                return ItemStack.EMPTY;
            }
        }
        if (index < 0 || identifier.isEmpty()) return ItemStack.EMPTY;
        final String name = identifier;
        final int position = index;
        return CuriosApi.getCuriosInventory(player).resolve()
                .flatMap(inventory -> inventory.findCurio(name, position))
                .map(top.theillusivec4.curios.api.SlotResult::stack).orElse(ItemStack.EMPTY);
    }

    public static Snapshot capture(Player player, AbstractSpell spell, int level, Context context) {
        ReforgeCache.Data data = context == null ? ReforgeCache.Data.DEF : context.data();
        SpellEffects effects = SpellEffects.NONE;
        if (context != null && context.stack() != null && !context.stack().isEmpty()) {
            if (context.stack().getItem() instanceof Scroll) effects = ReforgeCache.getEffectsFromScroll(context.stack());
            else if (context.stack().getItem() instanceof SpellBook)
                effects = ReforgeCache.getEffectsFromSpellBook(context.stack(), context.spellSlotIndex());
        }
        return new Snapshot(player.getUUID(), spell.getSpellId(), level, data, effects, BookAttributeHandler.capture(context));
    }

    public static void begin(Player player, Snapshot snapshot) {
        ACTIVE.put(player.getUUID(), new CastSession(snapshot));
    }

    public static Snapshot active(Player player, String spellId) {
        CastSession session = ACTIVE.get(player.getUUID());
        return session != null && session.snapshot.spellId().equals(spellId) ? session.snapshot : null;
    }

    public static void successful(Player player, String spellId) {
        CastSession session = ACTIVE.get(player.getUUID());
        if (session != null && session.snapshot.spellId().equals(spellId)) session.markSuccessful();
    }

    public static Snapshot finish(Player player, String spellId, boolean cancelled) {
        CastSession session = ACTIVE.get(player.getUUID());
        if (session == null || !session.snapshot.spellId().equals(spellId)) return null;
        ACTIVE.remove(player.getUUID());
        return session.complete(cancelled) ? session.snapshot : null;
    }

    public static Snapshot forDamage(SpellDamageSource source) {
        if (source.spell() == null || !(source.getEntity() instanceof Player player)) return null;
        String spellId = source.spell().getSpellId();
        Entity direct = source.getDirectEntity();
        Snapshot snapshot = direct != null && direct != player ? entitySnapshot(direct) : null;
        if (snapshot != null) return snapshot.owner().equals(player.getUUID()) ? snapshot : null;
        snapshot = currentSnapshot();
        return snapshot != null && snapshot.owner().equals(player.getUUID())
                && snapshot.spellId().equals(spellId) ? snapshot : null;
    }

    public static void attach(Entity entity, Snapshot snapshot) {
        if (entity instanceof Player || snapshot == null || entity.level().isClientSide) return;
        if (!snapshot.echo() && snapshot.data().isDefault() && snapshot.effects().isEmpty() && snapshot.attributes().isEmpty()) return;
        ENTITIES.put(entity, snapshot);
        entity.getPersistentData().put(SNAPSHOT_KEY, snapshot.write());
    }

    public static Snapshot entitySnapshot(Entity entity) {
        if (entity == null || entity.level().isClientSide) return null;
        Snapshot snapshot = ENTITIES.get(entity);
        if (snapshot == null && entity.getPersistentData().contains(SNAPSHOT_KEY)) {
            snapshot = Snapshot.read(entity.getPersistentData().getCompound(SNAPSHOT_KEY));
            if (snapshot != null) ENTITIES.put(entity, snapshot);
        }
        return snapshot;
    }

    public static Player owner(Entity entity, Snapshot snapshot) {
        return snapshot != null && entity.level() instanceof ServerLevel level
                ? level.getServer().getPlayerList().getPlayer(snapshot.owner()) : null;
    }

    public static void forget(Player player) { ACTIVE.remove(player.getUUID()); }
    public static void reset() { ACTIVE.clear(); ENTITIES.clear(); SOURCE.remove(); clear(); }
}
