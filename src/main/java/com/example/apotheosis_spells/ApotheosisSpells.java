package com.example.apotheosis_spells;

import com.example.apotheosis_spells.gem.GemRegistryHook;
import com.example.apotheosis_spells.handler.ScrollLootCategory;
import com.example.apotheosis_spells.handler.BookAttributeHandler;
import com.example.apotheosis_spells.handler.SpellCastHooks;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.magic.SpellSelectionManager;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Mod(ApotheosisSpells.MODID)
public class ApotheosisSpells {

    public static final String MODID = "apotheosis_spells";
    public static final Logger LOGGER = LogManager.getLogger();

    public ApotheosisSpells() {
        // 注册两个 LootCategory：scroll（作用于 Scroll 本体）和 spellbook_slot（作用于 SpellBook 内 SpellSlot）
        ScrollLootCategory.register();
        // 注册默认 SpellGemBonus
        GemRegistryHook.registerDefaults();
        com.example.apotheosis_spells.handler.SpellEffectHandler.EchoAnimation.register();
        LOGGER.info("Apotheosis Spells loaded.");
        Diagnostics.log("STARTUP", () -> "enabled=" + Diagnostics.enabled() + " command=/apothspells_debug on|off|dump");
    }

    @Mod.EventBusSubscriber(modid = MODID)
    public static final class Diagnostics {
        private static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("apotheosis_spells.diagnostics", "false"));
        private static final ConcurrentHashMap<String, Object> STATES = new ConcurrentHashMap<>();
        private static long windowStart = System.nanoTime();
        private static int windowLines;
        private static int suppressed;

        private record SelectionState(String selection, String item, int index, String reason, CompoundTag affixes) {}

        public static boolean enabled() { return enabled; }

        public static synchronized boolean log(String event, Supplier<String> details) {
            if (!enabled) return false;
            long now = System.nanoTime();
            if (now - windowStart >= 1_000_000_000L) {
                if (suppressed > 0) LOGGER.info("[APOTH-SPELLS-DIAG] THROTTLE suppressed={} previous-second", suppressed);
                windowStart = now;
                windowLines = 0;
                suppressed = 0;
            }
            if (++windowLines > 200) { suppressed++; return false; }
            try {
                LOGGER.info("[APOTH-SPELLS-DIAG] {} {}", event, details.get());
                return true;
            } catch (RuntimeException e) {
                LOGGER.warn("[APOTH-SPELLS-DIAG] failed to format {}", event, e);
                return false;
            }
        }

        public static String player(Player player) {
            return "player=" + player.getId() + " tick=" + player.tickCount
                    + " side=" + (player.level().isClientSide ? "client" : "server")
                    + " creative=" + player.isCreative();
        }

        public static String stack(ItemStack stack, boolean includeData) {
            if (stack == null || stack.isEmpty()) return "EMPTY";
            String summary = ForgeRegistries.ITEMS.getKey(stack.getItem()) + " x" + stack.getCount()
                    + " class=" + stack.getItem().getClass().getName();
            if (!includeData || stack.getTag() == null) return summary;
            CompoundTag data = new CompoundTag();
            for (String key : new String[]{"affix_data", "apoth_book_affixes", "irons_spellbooks:spell_container", "ISB_Spells"}) {
                if (stack.getTag().contains(key)) data.put(key, stack.getTag().get(key).copy());
            }
            return summary + " data=" + data;
        }

        public static String affixes(CompoundTag data) {
            if (data == null) return "NONE";
            java.util.List<String> definitions = new java.util.ArrayList<>();
            for (String key : data.getCompound("affixes").getAllKeys()) {
                var id = net.minecraft.resources.ResourceLocation.tryParse(key);
                if (id == null) { definitions.add(key + "=INVALID_ID"); continue; }
                var holder = dev.shadowsoffire.apotheosis.adventure.affix.AffixRegistry.INSTANCE.holder(id);
                definitions.add(key + '=' + (holder.isBound() ? holder.get().getClass().getName() : "UNBOUND"));
            }
            return "rarity=" + data.getString("rarity") + " definitions=" + definitions;
        }

        public static void selection(Player player, SpellSelectionManager.SelectionOption selection,
                ItemStack source, int index, CompoundTag affixes, String reason) {
            if (!enabled) return;
            String selected = selection == null ? "NONE" : selection.slot + ":" + selection.slotIndex + ":"
                    + (selection.spellData == null ? "NONE" : selection.spellData.getSpell().getSpellId());
            var state = new SelectionState(selected, stack(source, false), index, reason, affixes);
            String key = player.getUUID() + ":selection";
            if (Objects.equals(STATES.get(key), state)) return;
            if (log("SELECTION", () -> player(player) + " wheel=" + selected + " physicalSlot=" + index + " reason=" + reason
                    + " rawSelection=" + MagicData.getPlayerMagicData(player).getSyncedData().getSpellSelection().serializeNBT()
                    + " source=" + stack(source, true))) STATES.put(key, state);
        }

        public static void calculation(String event, String key, String values) {
            if (!enabled || Objects.equals(STATES.get(key + ':' + event), values)) return;
            if (log(event, () -> key + ' ' + values)) STATES.put(key + ':' + event, values);
        }

        @SubscribeEvent
        public static void commands(RegisterCommandsEvent event) {
            event.getDispatcher().register(Commands.literal("apothspells_debug").requires(source -> source.hasPermission(2))
                    .executes(ctx -> {
                        ctx.getSource().sendSuccess(() -> Component.literal("神化法术诊断日志：" + (enabled ? "开启" : "关闭")), false);
                        return 1;
                    })
                    .then(Commands.literal("on").executes(ctx -> {
                        enabled = true;
                        STATES.clear();
                        ctx.getSource().sendSuccess(() -> Component.literal("已开启神化法术诊断日志，标记 APOTH-SPELLS-DIAG"), false);
                        return 1;
                    }))
                    .then(Commands.literal("off").executes(ctx -> {
                        enabled = false;
                        STATES.clear();
                        ctx.getSource().sendSuccess(() -> Component.literal("已关闭神化法术诊断日志"), false);
                        return 1;
                    }))
                    .then(Commands.literal("dump").executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        enabled = true;
                        STATES.remove(player.getUUID() + ":selection");
                        BookAttributeHandler.refresh(player);
                        var selection = new SpellSelectionManager(player).getSelection();
                        var context = SpellCastHooks.resolveSelection(player, selection);
                        log("DUMP", () -> player(player)
                                + " creativeMana=" + io.redspace.ironsspellbooks.config.ServerConfigs.CREATIVE_MANA_COST.get()
                                + " creativeCooldowns=" + io.redspace.ironsspellbooks.config.ServerConfigs.CREATIVE_COOLDOWN.get()
                                + " context=" + context);
                        ForgeRegistries.ATTRIBUTES.getEntries().stream()
                                .filter(entry -> entry.getKey().location().getNamespace().equals("irons_spellbooks"))
                                .forEach(entry -> {
                                    var attribute = player.getAttribute(entry.getValue());
                                    if (attribute != null) log("PLAYER_ATTRIBUTE", () -> player(player) + " attribute="
                                            + entry.getKey().location() + " base=" + attribute.getBaseValue()
                                            + " total=" + attribute.getValue() + " modifiers=" + attribute.getModifiers());
                                });
                        ctx.getSource().sendSuccess(() -> Component.literal("当前法术、词条与属性已写入 logs/latest.log"), false);
                        return 1;
                    })));
        }

        @SubscribeEvent
        public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
            String prefix = event.getEntity().getUUID().toString();
            STATES.keySet().removeIf(key -> key.startsWith(prefix));
        }

        @SubscribeEvent
        public static void stopped(ServerStoppedEvent event) { STATES.clear(); }
    }
}
