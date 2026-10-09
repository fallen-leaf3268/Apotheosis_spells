package com.example.apotheosis_spells.audit;

import com.example.apotheosis_spells.ApotheosisSpells;
import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.handler.SpellCastHooks;
import io.redspace.ironsspellbooks.api.events.ModifySpellLevelEvent;
import io.redspace.ironsspellbooks.api.item.CastingImplementData;
import io.redspace.ironsspellbooks.api.magic.SpellSelectionManager;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.gui.inscription_table.InscriptionTableMenu;
import io.redspace.ironsspellbooks.gui.inscription_table.InscriptionTableScreen;
import io.redspace.ironsspellbooks.player.ClientMagicData;
import io.redspace.ironsspellbooks.player.ClientPlayerEvents;
import io.redspace.ironsspellbooks.util.TooltipsUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Mod.EventBusSubscriber(modid = ApotheosisSpells.MODID, value = Dist.CLIENT)
public final class DisplayUiClientProbe {
    private static boolean started;
    private static int readyTicks;
    private static String lastScreen;

    @Mod.EventBusSubscriber(modid = ApotheosisSpells.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class TargetLoad {
        @SubscribeEvent
        public static void setup(FMLClientSetupEvent event) {
            if (!Boolean.getBoolean("apotheosis_spells.uiProbe")) return;
            event.enqueueWork(() -> {
                try {
                    for (String name : List.of("io.redspace.ironsspellbooks.util.TooltipsUtils",
                            "io.redspace.ironsspellbooks.render.SpellRenderingHelper",
                            "io.redspace.ironsspellbooks.gui.overlays.SpellWheelOverlay",
                            "io.redspace.ironsspellbooks.item.SpellBook",
                            "io.redspace.ironsspellbooks.player.ClientPlayerEvents",
                            "io.redspace.ironsspellbooks.gui.inscription_table.InscriptionTableScreen")) {
                        Class.forName(name);
                    }
                    ApotheosisSpells.LOGGER.info("UI_PROBE_TARGETS_LOADED");
                } catch (Throwable error) {
                    ApotheosisSpells.LOGGER.error("UI_PROBE_TARGET_LOAD_FAIL", error);
                    Minecraft.getInstance().stop();
                }
            });
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("apotheosis_spells.uiProbe") || started || event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null && minecraft.screen != null
                && !minecraft.screen.getClass().getName().equals(lastScreen)) {
            lastScreen = minecraft.screen.getClass().getName();
            ApotheosisSpells.LOGGER.info("UI_PROBE_SCREEN {} {}", lastScreen, minecraft.screen.getTitle().getContents());
        }
        if (minecraft.player == null && minecraft.screen instanceof net.minecraft.client.gui.screens.ConfirmScreen screen
                && screen.getTitle().getContents() instanceof TranslatableContents title
                && title.getKey().equals("selectWorld.backupQuestion.experimental")) {
            for (var child : screen.children()) {
                if (child instanceof net.minecraft.client.gui.components.Button button
                        && button.getMessage().getContents() instanceof TranslatableContents text
                        && text.getKey().equals("gui.proceed")) {
                    ApotheosisSpells.LOGGER.info("UI_PROBE_TEST_WORLD_CONFIRMED");
                    button.onPress();
                    break;
                }
            }
            return;
        }
        if (minecraft.player == null || minecraft.level == null || ++readyTicks < 20) return;
        started = true;
        List<Throwable> failures = new ArrayList<>();
        run("book_titles", () -> bookTitles(minecraft), failures);
        run("casting_implement", () -> castingImplement(minecraft), failures);
        run("inscription_level", () -> inscriptionLevel(minecraft), failures);
        if (failures.isEmpty()) ApotheosisSpells.LOGGER.info("UI_PROBE_PASS cases=3");
        else ApotheosisSpells.LOGGER.error("UI_PROBE_FAIL cases=" + failures.size());
        minecraft.stop();
    }

    private static void bookTitles(Minecraft minecraft) throws Exception {
        ItemStack book = book("dragon_breath", 10, 10);
        var saved = book.getTag().copy();
        var slots = ISpellContainer.get(book).getActiveSpells();
        check(slots.size() == 2 && slots.get(1).index() == 2, "Sparse duplicate book fixture lost physical slots");
        List<String> expected = slots.stream().map(slot -> SpellCastHooks.withPageContext(book, minecraft.player, slot,
                current -> TooltipsUtils.getTitleComponent(current.spellData(), minecraft.player).getString())).toList();
        check(!expected.get(0).equals(expected.get(1)), "Book fixture did not activate its level affix");
        var outer = new SpellCastHooks.Context(ItemStack.EMPTY, minecraft.player, -1, 1, ReforgeCache.Data.DEF, null);
        List<Integer> observedSlots = new ArrayList<>();
        Consumer<ModifySpellLevelEvent> observe = event -> {
            var context = SpellCastHooks.get();
            if (context != null && context.stack() == book) observedSlots.add(context.spellSlotIndex());
        };
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ModifySpellLevelEvent.class, observe);
        try (var scope = SpellCastHooks.enter(outer)) {
            List<Component> lines = new ArrayList<>();
            book.getItem().appendHoverText(book, minecraft.level, lines, TooltipFlag.NORMAL);
            for (String title : expected) check(lines.stream().anyMatch(line -> line.getString().contains(title)),
                    "Native book tooltip omitted title: " + title + " in " + lines);
            check(observedSlots.containsAll(List.of(0, 2)), "Book titles did not enter each physical slot: " + observedSlots);
            check(SpellCastHooks.get() == outer, "Native book tooltip leaked its title scope");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(observe);
        }
        Consumer<ModifySpellLevelEvent> fail = event -> {
            var context = SpellCastHooks.get();
            if (context != null && context.stack() == book && context.spellSlotIndex() == 2) {
                throw new IllegalStateException("UI_PROBE_EXPECTED_TITLE_FAILURE");
            }
        };
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ModifySpellLevelEvent.class, fail);
        try (var scope = SpellCastHooks.enter(outer)) {
            boolean thrown = false;
            try {
                book.getItem().appendHoverText(book, minecraft.level, new ArrayList<>(), TooltipFlag.NORMAL);
            } catch (RuntimeException expectedFailure) {
                thrown = true;
            }
            check(thrown && SpellCastHooks.get() == outer, "Failed title render did not restore the surrounding scope");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(fail);
        }
        check(saved.equals(book.getTag()) && SpellCastHooks.get() == null, "Title rendering changed NBT or leaked context");
    }

    private static void castingImplement(Minecraft minecraft) throws Exception {
        ItemStack book = book("fireball", 1, 2);
        var saved = book.getTag().copy();
        ItemStack previousBook = Utils.getPlayerSpellbookStack(minecraft.player);
        Field managerField = ClientMagicData.class.getDeclaredField("spellSelectionManager");
        managerField.setAccessible(true);
        Object previousManager = managerField.get(null);
        try {
            Utils.setPlayerSpellbookStack(minecraft.player, book);
            SpellSelectionManager manager = new SpellSelectionManager(minecraft.player);
            var option = manager.getAllSpells().stream().filter(value -> value.slot.equals("spellbook")
                    && SpellCastHooks.resolveSelectionSlot(book, value) != null
                    && SpellCastHooks.resolveSelectionSlot(book, value).index() == 2).findFirst().orElseThrow();
            Field selectionIndex = SpellSelectionManager.class.getDeclaredField("selectionIndex");
            selectionIndex.setAccessible(true);
            selectionIndex.setInt(manager, manager.getAllSpells().indexOf(option));
            managerField.set(null, manager);
            var context = SpellCastHooks.resolveSelection(minecraft.player, option);
            check(context != null && context.spellSlotIndex() == 2 && context.data().lvl() > 0
                    && context.data().mana() < 1, "Selected book fixture did not resolve active affixes");
            List<String> expected = TooltipsUtils.formatActiveSpellTooltip(book, option.spellData,
                    option.getCastSource(), minecraft.player).stream().map(Component::getString).toList();
            ItemStack implement = new ItemStack(Items.STICK);
            ItemStack otherBook = book.copy();
            otherBook.getOrCreateTag().remove(ReforgeCache.BOOK_AFFIXES);
            var outer = new SpellCastHooks.Context(ItemStack.EMPTY, minecraft.player, -1, 1, ReforgeCache.Data.DEF, null);
            try (var scope = SpellCastHooks.enter(outer)) {
                for (ItemStack hovered : List.of(implement, otherBook)) {
                    CastingImplementData.set(hovered, true);
                    List<Component> lines = new ArrayList<>();
                    lines.add(hovered.getHoverName());
                    ClientPlayerEvents.imbuedWeaponTooltips(new ItemTooltipEvent(hovered, minecraft.player, lines, TooltipFlag.NORMAL));
                    for (String text : expected) check(text.isBlank() || lines.stream().anyMatch(line -> line.getString().contains(text)),
                            "Native marked-item tooltip omitted selected-book detail: " + text + " in " + lines);
                    check(SpellCastHooks.get() == outer, "Casting implement tooltip leaked its selected scope");
                }
            }
            check(saved.equals(book.getTag()) && SpellCastHooks.get() == null, "Marked-item rendering changed source NBT or leaked context");
        } finally {
            managerField.set(null, previousManager);
            Utils.setPlayerSpellbookStack(minecraft.player, previousBook == null ? ItemStack.EMPTY : previousBook);
        }
    }

    private static void inscriptionLevel(Minecraft minecraft) throws Exception {
        ItemStack book = book("fireball", 1, 2);
        var saved = book.getTag().copy();
        var slot = ISpellContainer.get(book).getAllSpells()[2];
        Consumer<ModifySpellLevelEvent> affinity = event -> {
            if (event.getEntity() == minecraft.player && event.getSpell() == slot.getSpell()) event.addLevels(2);
        };
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ModifySpellLevelEvent.class, affinity);
        try {
            int nativeLevel = slot.getSpell().getLevelFor(slot.getLevel(), minecraft.player);
            check(nativeLevel == slot.getLevel() + 2, "Native level event fixture did not apply");
            var menu = new InscriptionTableMenu(0, minecraft.player.getInventory(), ContainerLevelAccess.NULL);
            menu.getSpellBookSlot().set(book);
            var screen = new InscriptionTableScreen(menu, minecraft.player.getInventory(), Component.literal("UI probe"));
            screen.init(minecraft, minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
            var generate = InscriptionTableScreen.class.getDeclaredMethod("generateSpellSlots");
            generate.setAccessible(true);
            generate.invoke(screen);
            Field selected = InscriptionTableScreen.class.getDeclaredField("selectedSpellIndex");
            selected.setAccessible(true);
            selected.setInt(screen, 2);
            var render = InscriptionTableScreen.class.getDeclaredMethod("renderLorePage", GuiGraphics.class,
                    float.class, int.class, int.class);
            render.setAccessible(true);
            var graphics = new RecordingGraphics(minecraft);
            var outer = new SpellCastHooks.Context(ItemStack.EMPTY, minecraft.player, -1, 1, ReforgeCache.Data.DEF, null);
            try (var scope = SpellCastHooks.enter(outer)) {
                render.invoke(screen, graphics, 0f, -1000, -1000);
                check(SpellCastHooks.get() == outer, "Inscription rendering leaked its selected scope");
            }
            int expected = nativeLevel + ReforgeCache.getFromSpellBook(book, 2).lvl();
            check(graphics.levels.equals(List.of(expected)), "Inscription page omitted native level bonuses: "
                    + graphics.levels + ", expected " + expected);
            check(saved.equals(book.getTag()) && SpellCastHooks.get() == null, "Inscription rendering changed NBT or leaked context");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(affinity);
        }
    }

    private static ItemStack book(String spell, int first, int second) throws Exception {
        ItemStack book = new ItemStack(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("irons_spellbooks:netherite_spell_book")));
        book.setTag(TagParser.parseTag("""
                {"irons_spellbooks:spell_container":{maxSpells:12,mustEquip:1b,spellWheel:1b,
                data:[{index:0,id:"irons_spellbooks:%s",level:%d},{index:2,id:"irons_spellbooks:%s",level:%d}]},
                apoth_book_affixes:{"2":{spell_id:"irons_spellbooks:%s",affix_data:{rarity:"apotheosis:ancient",
                affixes:{"apotheosis_spells:scroll/spell_modifier/spell_level":1.0f,
                "apotheosis_spells:scroll/spell_modifier/mana_cost":1.0f,
                "apotheosis_spells:scroll/spell_modifier/cooldown":1.0f,
                "apotheosis_spells:scroll/spell_modifier/spell_power":1.0f}}}}}
                """.formatted(spell, first, spell, second, spell)));
        return book;
    }

    private static void run(String name, CheckedAction action, List<Throwable> failures) {
        try {
            action.run();
            ApotheosisSpells.LOGGER.info("UI_PROBE_CASE_PASS " + name);
        } catch (Throwable error) {
            failures.add(error);
            ApotheosisSpells.LOGGER.error("UI_PROBE_CASE_FAIL " + name, error);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface CheckedAction {
        void run() throws Exception;
    }

    private static final class RecordingGraphics extends GuiGraphics {
        private final List<Integer> levels = new ArrayList<>();

        private RecordingGraphics(Minecraft minecraft) {
            super(minecraft, minecraft.renderBuffers().bufferSource());
        }

        @Override
        public int drawString(Font font, Component text, int x, int y, int color, boolean shadow) {
            if (text.getContents() instanceof TranslatableContents contents
                    && contents.getKey().equals("ui.irons_spellbooks.level")) levels.add((Integer) contents.getArgs()[0]);
            return super.drawString(font, text, x, y, color, shadow);
        }
    }
}
