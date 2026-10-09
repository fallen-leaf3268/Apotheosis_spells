package com.example.apotheosis_spells.audit;

import com.example.apotheosis_spells.ApotheosisSpells;
import com.example.apotheosis_spells.affix.SpellAffix;
import com.example.apotheosis_spells.api.ReforgeCache;
import com.example.apotheosis_spells.api.SpellEffects;
import com.example.apotheosis_spells.handler.SiphoningBeamRange;
import com.example.apotheosis_spells.handler.BookAttributeHandler;
import com.example.apotheosis_spells.handler.SpellCastHooks;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.netty.buffer.Unpooled;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.item.CastingImplementData;
import io.redspace.ironsspellbooks.api.magic.SpellSelectionManager;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import io.redspace.ironsspellbooks.capabilities.magic.SyncedSpellData;
import io.redspace.ironsspellbooks.gui.inscription_table.InscriptionTableMenu;
import io.redspace.ironsspellbooks.gui.inscription_table.InscriptionTableScreen;
import io.redspace.ironsspellbooks.gui.overlays.SpellWheelOverlay;
import io.redspace.ironsspellbooks.item.SpellBook;
import io.redspace.ironsspellbooks.player.ClientMagicData;
import io.redspace.ironsspellbooks.player.ClientInputEvents;
import io.redspace.ironsspellbooks.player.ClientPlayerEvents;
import io.redspace.ironsspellbooks.render.SpellRenderingHelper;
import io.redspace.ironsspellbooks.spells.blood.RayOfSiphoningSpell;
import io.redspace.ironsspellbooks.util.TooltipsUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.chat.FormattedText;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class FullDisplayClientCases {
    private FullDisplayClientCases() {}

    static void scrolls(Minecraft minecraft) throws Exception {
        int checked = 0;
        int hidden = 0;
        for (var spell : SpellRegistry.getEnabledSpells()) {
            for (String profile : profiles(spell)) {
                ItemStack scroll = scroll(spell, profile);
                var before = scroll.getTag().copy();
                var data = ISpellContainer.get(scroll).getSpellAtIndex(0);
                var expected = values(minecraft, scroll, data, 0, CastSource.SCROLL);
                List<Component> lines = new ArrayList<>();
                scroll.getItem().appendHoverText(scroll, minecraft.level, lines, TooltipFlag.NORMAL);
                checkTooltip(lines, spell, expected, spell.obfuscateStats(minecraft.player), true, "scroll " + spell.getSpellId() + "/" + profile);
                check(before.equals(scroll.getTag()) && SpellCastHooks.get() == null, "Scroll tooltip changed NBT or context");
                if (spell.obfuscateStats(minecraft.player)) hidden++;
                checked++;
            }
        }
        ApotheosisSpells.LOGGER.info("UI_PROBE_MATRIX scrolls={} hidden_native_stats={}", checked, hidden);
    }

    static void pages(Minecraft minecraft) throws Exception {
        int checked = 0;
        for (var spell : SpellRegistry.getEnabledSpells()) {
            for (String profile : profiles(spell)) {
                ItemStack book = book(spell, profile);
                var before = book.getTag().copy();
                var pages = ((SpellBook) book.getItem()).getPages(book);
                var slots = ISpellContainer.get(book).getActiveSpells();
                check(pages.size() == slots.size(), "Native pages lost a sparse spell slot");
                for (int i = 0; i < pages.size(); i++) {
                    var slot = slots.get(i);
                    var expected = values(minecraft, book, slot.spellData(), slot.index(), CastSource.SPELLBOOK);
                    var text = hoverText(pages.get(i));
                    checkTooltip(List.of(text), spell, expected, spell.obfuscateStats(minecraft.player), false,
                            "page " + spell.getSpellId() + "/" + profile + "/" + slot.index());
                    checked++;
                }
                check(before.equals(book.getTag()) && SpellCastHooks.get() == null, "Page rendering changed NBT or context");
            }
        }
        ApotheosisSpells.LOGGER.info("UI_PROBE_MATRIX sparse_duplicate_pages={}", checked);
    }

    static void expandedDuplicateBook(Minecraft minecraft) throws Exception {
        var spell = SpellRegistry.FIREBALL_SPELL.get();
        var book = book(spell, "mana_cost");
        var entries = book.getTag().getCompound("irons_spellbooks:spell_container").getList("data", 10);
        entries.getCompound(0).putInt("level", secondLevel(spell));
        ReforgeCache.setBookAffix(book, 0, affixes(spell, "combined"));
        var saved = book.getTag().copy();
        var previousBook = Utils.getPlayerSpellbookStack(minecraft.player);
        var managerField = field(ClientMagicData.class, "spellSelectionManager");
        var previousManager = managerField.get(null);
        boolean expanded = ClientInputEvents.isShowExpandedTooltip();
        var outer = new SpellCastHooks.Context(ItemStack.EMPTY, minecraft.player, -1, 1, ReforgeCache.Data.DEF, null);
        try {
            Utils.setPlayerSpellbookStack(minecraft.player, book);
            var manager = new SpellSelectionManager(minecraft.player);
            managerField.set(null, manager);
            var options = manager.getAllSpells().stream().filter(value -> value.slot.equals("spellbook")
                    && value.spellData.getSpell() == spell).toList();
            check(options.size() == 1, "Native equal-level duplicate fixture was not merged by selection manager");
            field(SpellSelectionManager.class, "selectionIndex").setInt(manager, manager.getAllSpells().indexOf(options.get(0)));
            check(SpellCastHooks.resolveSelectionSlot(book, options.get(0)).index() == 0,
                    "Native duplicate selection did not retain its first physical slot");
            var selected = ISpellContainer.get(book).getAllSpells()[0];
            var expected = values(minecraft, book, selected.spellData(), 0, CastSource.SPELLBOOK);
            var other = values(minecraft, book, ISpellContainer.get(book).getAllSpells()[2].spellData(), 2, CastSource.SPELLBOOK);
            check(expected.mana != other.mana && !expected.unique.equals(other.unique), "Duplicate fixture has indistinguishable slot affixes");
            ClientInputEvents.setShowExpandedTooltip(true);
            List<Component> lines = new ArrayList<>();
            try (var scope = SpellCastHooks.enter(outer)) {
                book.getItem().appendHoverText(book, minecraft.level, lines, TooltipFlag.NORMAL);
                check(SpellCastHooks.get() == outer, "Expanded book details leaked their physical-slot context");
            }
            checkTooltip(lines, spell, expected, false, false, "expanded same-level duplicate selected slot0");
            check(saved.equals(book.getTag()) && SpellCastHooks.get() == null, "Expanded book tooltip changed NBT or leaked context");
        } finally {
            ClientInputEvents.setShowExpandedTooltip(expanded);
            managerField.set(null, previousManager);
            Utils.setPlayerSpellbookStack(minecraft.player, previousBook == null ? ItemStack.EMPTY : previousBook);
        }
    }

    static void slotAttributes(Minecraft minecraft) throws Exception {
        var spell = SpellRegistry.FIREBALL_SPELL.get();
        var book = book(spell, "combined");
        var affixData = ReforgeCache.getBookAffix(book, 2);
        affixData.getCompound("affixes").putFloat("apotheosis_spells:scroll/attribute/fire_spell_power", 1);
        ReforgeCache.setBookAffix(book, 2, affixData);
        var saved = book.getTag().copy();
        var slot = ISpellContainer.get(book).getAllSpells()[2];
        var context = SpellCastHooks.buildContext(book, minecraft.player, 2, slot.getLevel(), slot.spellData());
        var bonuses = BookAttributeHandler.capture(context);
        check(bonuses.stream().anyMatch(value -> value.attribute().equals("irons_spellbooks:fire_spell_power")
                        && value.operation() == AttributeModifier.Operation.ADDITION && Math.abs(value.amount() - 0.28) < 0.000001),
                "Real ancient attribute affix did not provide its independent JSON endpoint of +0.28");
        var attribute = ForgeRegistries.ATTRIBUTES.getValue(ResourceLocation.parse("irons_spellbooks:fire_spell_power"));
        var instance = minecraft.player.getAttribute(attribute);
        check(instance != null, "Native client fire power attribute is absent");
        double beforeValue = instance.getValue();
        var beforeModifiers = java.util.Set.copyOf(instance.getModifiers());
        var oracle = new AttributeModifier(UUID.randomUUID(), "ui_probe_native_oracle", 0.28, AttributeModifier.Operation.ADDITION);
        Values expected;
        try {
            instance.addTransientModifier(oracle);
            expected = values(minecraft, book, slot.spellData(), 2, CastSource.SPELLBOOK);
        } finally {
            instance.removeModifier(oracle.getId());
        }
        var unpowered = values(minecraft, book, ISpellContainer.get(book).getAllSpells()[0].spellData(), 0, CastSource.SPELLBOOK);
        var pages = ((SpellBook) book.getItem()).getPages(book);
        checkTooltip(List.of(hoverText(pages.get(0))), spell, unpowered, false, false,
                "real attribute affix unrelated slot0");
        checkTooltip(List.of(hoverText(pages.get(1))), spell, expected, false, false,
                "real attribute affix page slot2");
        checkTooltip(TooltipsUtils.formatActiveSpellTooltip(book, slot.spellData(), CastSource.SPELLBOOK, minecraft.player),
                spell, expected, false, false, "real attribute affix active formatter");
        var menu = new InscriptionTableMenu(0, minecraft.player.getInventory(), ContainerLevelAccess.NULL);
        menu.getSpellBookSlot().set(book);
        var screen = new InscriptionTableScreen(menu, minecraft.player.getInventory(), Component.literal("UI attributes"));
        screen.init(minecraft, minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
        method(InscriptionTableScreen.class, "generateSpellSlots").invoke(screen);
        field(InscriptionTableScreen.class, "selectedSpellIndex").setInt(screen, 2);
        var lore = new RecordingGraphics(minecraft);
        method(InscriptionTableScreen.class, "renderLorePage", GuiGraphics.class, float.class, int.class, int.class)
                .invoke(screen, lore, 1f, -1000, -1000);
        checkInscription(lore.text, spell, expected, false, "real attribute affix slot2");
        var previousBook = Utils.getPlayerSpellbookStack(minecraft.player);
        var managerField = field(ClientMagicData.class, "spellSelectionManager");
        var previousManager = managerField.get(null);
        var previousScreen = minecraft.screen;
        boolean grabbed = minecraft.mouseHandler.isMouseGrabbed();
        double previousX = minecraft.mouseHandler.xpos();
        double previousY = minecraft.mouseHandler.ypos();
        var wheel = new SpellWheelOverlay();
        try {
            Utils.setPlayerSpellbookStack(minecraft.player, book);
            var manager = new SpellSelectionManager(minecraft.player);
            managerField.set(null, manager);
            var option = manager.getAllSpells().stream().filter(value -> value.slot.equals("spellbook")
                    && SpellCastHooks.resolveSelectionSlot(book, value).index() == 2).findFirst().orElseThrow();
            int desired = manager.getAllSpells().indexOf(option);
            field(SpellSelectionManager.class, "selectionIndex").setInt(manager, desired);
            var implement = new ItemStack(Items.STICK);
            CastingImplementData.set(implement, true);
            List<Component> lines = new ArrayList<>();
            lines.add(implement.getHoverName());
            ClientPlayerEvents.imbuedWeaponTooltips(new ItemTooltipEvent(implement, minecraft.player, lines, TooltipFlag.NORMAL));
            checkTooltip(lines, spell, expected, false, false, "real attribute affix marked-item entry");
            minecraft.screen = null;
            wheel.open();
            var graphics = new RecordingGraphics(minecraft);
            var outer = new SpellCastHooks.Context(ItemStack.EMPTY, minecraft.player, -1, 1, ReforgeCache.Data.DEF, null);
            renderSelection(minecraft, wheel, graphics, desired, manager.getSpellCount(), field(SpellWheelOverlay.class, "wheelSelection"),
                    field(minecraft.mouseHandler.getClass(), "xpos"), field(minecraft.mouseHandler.getClass(), "ypos"), outer);
            checkWheel(graphics.text, spell, expected, false, "real attribute affix slot2");
        } finally {
            if (wheel.active) wheel.close();
            managerField.set(null, previousManager);
            Utils.setPlayerSpellbookStack(minecraft.player, previousBook == null ? ItemStack.EMPTY : previousBook);
            minecraft.screen = previousScreen;
            field(minecraft.mouseHandler.getClass(), "xpos").setDouble(minecraft.mouseHandler, previousX);
            field(minecraft.mouseHandler.getClass(), "ypos").setDouble(minecraft.mouseHandler, previousY);
            if (grabbed) minecraft.mouseHandler.grabMouse(); else minecraft.mouseHandler.releaseMouse();
        }
        check(Math.abs(instance.getValue() - beforeValue) < 0.000001 && beforeModifiers.equals(java.util.Set.copyOf(instance.getModifiers())),
                "Attribute previews modified the real LocalPlayer attribute map");
        check(saved.equals(book.getTag()) && SpellCastHooks.get() == null, "Attribute preview changed source NBT or leaked context");
    }

    static void scrollAttributePreviews(Minecraft minecraft) throws Exception {
        var player = minecraft.player;
        var spell = SpellRegistry.getSpell("irons_spellbooks:magic_missile");
        var affixData = new CompoundTag();
        affixData.putString("rarity", "apotheosis:ancient");
        var affixes = new CompoundTag();
        affixes.putFloat("apotheosis_spells:scroll/spell_modifier/spell_level", 1);
        affixes.putFloat("apotheosis_spells:scroll/spell_modifier/spell_power", 1);
        affixes.putFloat("apotheosis_spells:scroll/attribute/ender_spell_power", 1);
        affixData.put("affixes", affixes);
        var scroll = ReforgeCache.createAffixedScroll(spell, 10, affixData);
        var heldScroll = ReforgeCache.createAffixedScroll(spell, 10, affixData.copy());
        var book = book(spell, "plain");
        book.getTag().getCompound("irons_spellbooks:spell_container").getList("data", 10).getCompound(1).putInt("level", 10);
        ReforgeCache.setBookAffix(book, 2, affixData);
        var slot = ISpellContainer.get(book).getAllSpells()[2];
        var data = ISpellContainer.get(scroll).getSpellAtIndex(0);
        var scrollContext = SpellCastHooks.buildContext(scroll, player, 0, data.getLevel(), data);
        check(scrollContext.data().lvl() == 5 && Math.abs(scrollContext.data().dmg() - 1.5) < 0.000001,
                "Magic Missile regression fixture did not resolve level +5 and power +50%");
        check(BookAttributeHandler.capture(scrollContext).isEmpty(), "Direct scroll casts must retain native equipment attributes");
        var attribute = ForgeRegistries.ATTRIBUTES.getValue(ResourceLocation.parse("irons_spellbooks:ender_spell_power"));
        var raw = player.getAttributes().getInstance(attribute);
        var power = player.getAttributes().getInstance(io.redspace.ironsspellbooks.api.registry.AttributeRegistry.SPELL_POWER.get());
        check(raw != null && power != null, "Native client power attributes are absent");
        var original = new AttributeInstance(attribute, ignored -> {});
        original.replaceFrom(raw);
        var originalPower = new AttributeInstance(io.redspace.ironsspellbooks.api.registry.AttributeRegistry.SPELL_POWER.get(), ignored -> {});
        originalPower.replaceFrom(power);
        ItemStack mainhand = player.getMainHandItem();
        ItemStack offhand = player.getOffhandItem();
        var nativeModifiers = new LinkedHashMap<EquipmentSlot, List<AttributeModifier>>();
        for (var hand : List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)) {
            var modifiers = List.copyOf(heldScroll.getAttributeModifiers(hand).get(attribute));
            check(modifiers.size() == 1 && modifiers.get(0).getOperation() == AttributeModifier.Operation.ADDITION
                            && Math.abs(modifiers.get(0).getAmount() - 0.28) < 0.000001,
                    "Native " + hand + " scroll attributes did not provide the ancient +0.28 endpoint");
            nativeModifiers.put(hand, modifiers);
        }
        var savedScroll = scroll.getTag().copy();
        var savedHeld = heldScroll.getTag().copy();
        var savedBook = book.getTag().copy();
        var menu = new InscriptionTableMenu(0, player.getInventory(), ContainerLevelAccess.NULL);
        menu.getSpellBookSlot().set(book);
        var screen = new InscriptionTableScreen(menu, player.getInventory(), Component.literal("UI scroll attributes"));
        screen.init(minecraft, minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
        method(InscriptionTableScreen.class, "generateSpellSlots").invoke(screen);
        field(InscriptionTableScreen.class, "selectedSpellIndex").setInt(screen, 2);
        var render = method(InscriptionTableScreen.class, "renderLorePage", GuiGraphics.class, float.class, int.class, int.class);
        var outer = new SpellCastHooks.Context(ItemStack.EMPTY, player, -1, 1, ReforgeCache.Data.DEF, null);
        try {
            player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            for (var instance : List.of(raw, power)) {
                for (var modifier : List.copyOf(instance.getModifiers())) instance.removeModifier(modifier.getId());
                instance.setBaseValue(1);
            }
            for (String mode : List.of("empty", "mainhand", "offhand")) {
                var hand = mode.equals("empty") ? null : mode.equals("mainhand") ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
                if (hand != null) {
                    player.setItemSlot(hand, heldScroll);
                    nativeModifiers.get(hand).forEach(raw::addTransientModifier);
                }
                try {
                    var beforeModifiers = java.util.Set.copyOf(raw.getModifiers());
                    double beforeValue = raw.getValue();
                    var direct = values(minecraft, scroll, data, 0, CastSource.SCROLL);
                    var oracle = new AttributeModifier(UUID.randomUUID(), "ui_probe_scroll_attribute_oracle", 0.28,
                            AttributeModifier.Operation.ADDITION);
                    Values expected;
                    try {
                        raw.addTransientModifier(oracle);
                        expected = values(minecraft, book, slot.spellData(), 2, CastSource.SPELLBOOK);
                    } finally {
                        raw.removeModifier(oracle.getId());
                    }
                    check(expected.level == 15, "Magic Missile regression fixture did not reach effective level 15");
                    check(!direct.unique.stream().map(Component::getString).toList().equals(expected.unique.stream().map(Component::getString).toList()),
                            "Direct and inscribed scroll fixtures have indistinguishable native damage: " + mode);
                    var graphics = new RecordingGraphics(minecraft);
                    List<Component> lines = new ArrayList<>();
                    try (var scope = SpellCastHooks.enter(outer)) {
                        render.invoke(screen, graphics, 1f, -1000, -1000);
                        checkInscription(graphics.text, spell, expected, false, "scroll attribute oracle " + mode);
                        check(SpellCastHooks.get() == outer, "Attribute table preview leaked its surrounding context");
                        scroll.getItem().appendHoverText(scroll, minecraft.level, lines, TooltipFlag.NORMAL);
                        check(SpellCastHooks.get() == outer, "Attribute scroll tooltip leaked its surrounding context");
                    }
                    int heading = -1;
                    int directLabels = 0;
                    int inscribedLabels = 0;
                    for (int index = 0; index < lines.size(); index++) {
                        if (!(lines.get(index).getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents contents)) continue;
                        if (contents.getKey().equals("tooltip.apotheosis_spells.scroll_direct")) directLabels++;
                        if (contents.getKey().equals("tooltip.irons_spellbooks.scroll_tooltip")) {
                            heading = index;
                            inscribedLabels++;
                        }
                    }
                    check(heading > 0 && directLabels == 1 && inscribedLabels == 1,
                            "Attribute scroll tooltip did not retain exactly one direct and native inscribed heading: " + mode);
                    String directText = lines.subList(0, heading).stream().map(Component::getString).collect(java.util.stream.Collectors.joining("\n"));
                    String inscribedText = lines.subList(heading + 1, lines.size()).stream().map(Component::getString).collect(java.util.stream.Collectors.joining("\n"));
                    for (var unique : direct.unique) contains(directText, unique.getString(), "scroll direct-use attribute preview " + mode);
                    for (var unique : expected.unique) contains(inscribedText, unique.getString(), "scroll book-result attribute preview " + mode);
                    check(expected.unique.stream().noneMatch(unique -> directText.contains(unique.getString()))
                                    && direct.unique.stream().noneMatch(unique -> inscribedText.contains(unique.getString())),
                            "Attribute scroll tooltip mixed direct and inscribed damage sections: " + mode);
                    check(BookAttributeHandler.capture(scrollContext).isEmpty(), "Scroll preview changed execution attribute capture");
                    check(Math.abs(raw.getValue() - beforeValue) < 0.000001
                                    && beforeModifiers.equals(java.util.Set.copyOf(raw.getModifiers())),
                            "Scroll/table preview modified native equipment attributes: " + mode);
                    check(savedScroll.equals(scroll.getTag()) && savedHeld.equals(heldScroll.getTag()) && savedBook.equals(book.getTag())
                                    && SpellCastHooks.get() == null,
                            "Scroll/table preview changed NBT or leaked context: " + mode);
                    ApotheosisSpells.LOGGER.info("UI_PROBE_SCROLL_ATTRIBUTE mode={} native_power={} book_result={}",
                            mode, beforeValue, expected.unique.stream().map(Component::getString).toList());
                } finally {
                    if (hand != null) {
                        nativeModifiers.get(hand).forEach(modifier -> raw.removeModifier(modifier.getId()));
                        player.setItemSlot(hand, ItemStack.EMPTY);
                    }
                }
            }
            var scalarAffixes = affixData.copy();
            scalarAffixes.getCompound("affixes").remove("apotheosis_spells:scroll/attribute/ender_spell_power");
            var scalarScroll = ReforgeCache.createAffixedScroll(spell, 10, scalarAffixes);
            var scalarExpected = values(minecraft, scalarScroll, ISpellContainer.get(scalarScroll).getSpellAtIndex(0), 0, CastSource.SCROLL);
            var scalarLines = TooltipsUtils.formatScrollTooltip(scalarScroll, player);
            check(scalarLines.stream().noneMatch(line -> line.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents contents
                            && contents.getKey().equals("tooltip.apotheosis_spells.scroll_direct")),
                    "A scroll without attribute bonuses changed its native single-value layout");
            checkTooltip(scalarLines, spell, scalarExpected, false, true, "scroll without attribute bonuses");
            for (var unique : scalarExpected.unique) check(scalarLines.stream().filter(line -> line.getString().strip().equals(unique.getString().strip())).count() == 1,
                    "A scroll without attribute bonuses duplicated its native unique info");
            check(SpellCastHooks.get() == null, "A scroll without attribute bonuses leaked its preview context");
        } finally {
            player.setItemSlot(EquipmentSlot.MAINHAND, mainhand);
            player.setItemSlot(EquipmentSlot.OFFHAND, offhand);
            raw.replaceFrom(original);
            power.replaceFrom(originalPower);
        }
        check(raw.getBaseValue() == original.getBaseValue() && raw.getValue() == original.getValue()
                        && java.util.Set.copyOf(raw.getModifiers()).equals(java.util.Set.copyOf(original.getModifiers()))
                        && power.getBaseValue() == originalPower.getBaseValue() && power.getValue() == originalPower.getValue()
                        && java.util.Set.copyOf(power.getModifiers()).equals(java.util.Set.copyOf(originalPower.getModifiers())),
                "Scroll attribute regression fixture did not restore the original attribute maps");
        check(player.getMainHandItem() == mainhand && player.getOffhandItem() == offhand,
                "Scroll attribute regression fixture did not restore native equipment");
        ApotheosisSpells.LOGGER.info("UI_PROBE_MATRIX scroll_attribute_book_results=3 native_equipment_sources=2");
    }

    static void wheel(Minecraft minecraft) throws Exception {
        var previousScreen = minecraft.screen;
        ItemStack previousBook = Utils.getPlayerSpellbookStack(minecraft.player);
        Field managerField = field(ClientMagicData.class, "spellSelectionManager");
        Object previousManager = managerField.get(null);
        Field selection = field(SpellWheelOverlay.class, "wheelSelection");
        Field mouseX = field(minecraft.mouseHandler.getClass(), "xpos");
        Field mouseY = field(minecraft.mouseHandler.getClass(), "ypos");
        double previousX = minecraft.mouseHandler.xpos();
        double previousY = minecraft.mouseHandler.ypos();
        boolean previousGrab = minecraft.mouseHandler.isMouseGrabbed();
        boolean previousWindowActive = minecraft.isWindowActive();
        SpellWheelOverlay wheel = new SpellWheelOverlay();
        var outer = new SpellCastHooks.Context(ItemStack.EMPTY, minecraft.player, -1, 1, ReforgeCache.Data.DEF, null);
        int checked = 0;
        int focusedCloses = 0;
        try {
            minecraft.screen = null;
            for (var spell : SpellRegistry.getEnabledSpells()) {
                for (String profile : profiles(spell)) {
                    ItemStack book = book(spell, profile);
                    var before = book.getTag().copy();
                    Utils.setPlayerSpellbookStack(minecraft.player, book);
                    var manager = new SpellSelectionManager(minecraft.player);
                    managerField.set(null, manager);
                    check(manager.getSpellCount() > 0, "Wheel fixture has no selectable spell");
                    wheel.open();
                    check(wheel.active && !minecraft.mouseHandler.isMouseGrabbed(), "Native wheel open did not release the mouse");
                    for (int desired = 0; desired < manager.getSpellCount(); desired++) {
                        var graphics = new RecordingGraphics(minecraft);
                        renderSelection(minecraft, wheel, graphics, desired, manager.getSpellCount(), selection, mouseX, mouseY, outer);
                        var option = manager.getSpellSlot(desired);
                        var context = SpellCastHooks.resolveSelection(minecraft.player, option);
                        check(context != null, "Wheel selection did not resolve its physical source");
                        var expected = values(minecraft, context.stack(), option.spellData, context.spellSlotIndex(), option.getCastSource());
                        checkWheel(graphics.text, spell, expected, spell.obfuscateStats(minecraft.player), spell.getSpellId() + "/" + profile);
                        check(SpellCastHooks.get() == null, "Wheel frame leaked its selection context");
                        checked++;
                    }
                    int last = selection.getInt(wheel);
                    minecraft.setWindowActive(true);
                    wheel.close();
                    check(!wheel.active && minecraft.mouseHandler.isMouseGrabbed(), "Native wheel close did not restore the mouse");
                    check(manager.getSelectionIndex() == last, "Native wheel close did not commit its rendered selection");
                    check(before.equals(book.getTag()), "Wheel rendering changed source NBT");
                    focusedCloses++;
                }
            }
            ItemStack book = book(SpellRegistry.FIREBALL_SPELL.get(), "combined");
            Utils.setPlayerSpellbookStack(minecraft.player, book);
            managerField.set(null, new SpellSelectionManager(minecraft.player));
            wheel.open();
            boolean failed = false;
            try (var scope = SpellCastHooks.enter(outer)) {
                try {
                    var graphics = new RecordingGraphics(minecraft);
                    graphics.failOnText = true;
                    wheel.render((ForgeGui) minecraft.gui, graphics, 1f,
                            minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
                } catch (IllegalStateException expected) {
                    failed = expected.getMessage().equals("UI_PROBE_EXPECTED_RENDER_FAILURE");
                }
                check(failed && SpellCastHooks.get() == outer, "Failed real wheel frame did not restore its outer scope");
            }
            int inactiveSelection = selection.getInt(wheel);
            minecraft.setWindowActive(false);
            wheel.close();
            check(!wheel.active && !minecraft.mouseHandler.isMouseGrabbed(), "Native unfocused wheel close grabbed the mouse");
            check(ClientMagicData.getSpellSelectionManager().getSelectionIndex() == inactiveSelection,
                    "Native unfocused wheel close did not commit its rendered selection");
        } finally {
            if (wheel.active) wheel.close();
            managerField.set(null, previousManager);
            Utils.setPlayerSpellbookStack(minecraft.player, previousBook == null ? ItemStack.EMPTY : previousBook);
            minecraft.screen = previousScreen;
            mouseX.setDouble(minecraft.mouseHandler, previousX);
            mouseY.setDouble(minecraft.mouseHandler, previousY);
            try {
                if (previousGrab) {
                    minecraft.setWindowActive(true);
                    minecraft.mouseHandler.grabMouse();
                } else minecraft.mouseHandler.releaseMouse();
            } finally {
                minecraft.setWindowActive(previousWindowActive);
            }
        }
        ApotheosisSpells.LOGGER.info("UI_PROBE_MATRIX actual_wheel_frames={} focused_closes={} unfocused_closes=1 cast_time=N/A_native_has_no_row", checked, focusedCloses);
    }

    static void inscription(Minecraft minecraft) throws Exception {
        Method generate = method(InscriptionTableScreen.class, "generateSpellSlots");
        Method render = method(InscriptionTableScreen.class, "renderLorePage", GuiGraphics.class, float.class, int.class, int.class);
        Field selected = field(InscriptionTableScreen.class, "selectedSpellIndex");
        int checked = 0;
        for (var spell : SpellRegistry.getEnabledSpells()) {
            for (String profile : profiles(spell)) {
                var book = book(spell, profile);
                var before = book.getTag().copy();
                var menu = new InscriptionTableMenu(0, minecraft.player.getInventory(), ContainerLevelAccess.NULL);
                menu.getSpellBookSlot().set(book);
                var screen = new InscriptionTableScreen(menu, minecraft.player.getInventory(), Component.literal("UI probe"));
                screen.init(minecraft, minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
                generate.invoke(screen);
                for (int index : List.of(0, 2)) {
                    selected.setInt(screen, index);
                    var slot = ISpellContainer.get(book).getAllSpells()[index];
                    var expected = values(minecraft, book, slot.spellData(), index, CastSource.SPELLBOOK);
                    var graphics = new RecordingGraphics(minecraft);
                    render.invoke(screen, graphics, 1f, -1000, -1000);
                    checkInscription(graphics.text, spell, expected, spell.obfuscateStats(minecraft.player), spell.getSpellId() + "/" + profile + "/" + index);
                    check(SpellCastHooks.get() == null, "Inscription lore page leaked context");
                    checked++;
                }
                check(before.equals(book.getTag()), "Inscription lore page changed NBT");
                menu.setSelectedSpell(2);
                var result = menu.getResultSlot().getItem();
                check(!result.isEmpty(), "Native inscription menu did not expose its extraction result");
                var extracted = ISpellContainer.get(result).getSpellAtIndex(0);
                check(extracted.getSpell() == spell && extracted.getLevel() == secondLevel(spell), "Native extraction result changed the stored spell");
                check(ReforgeCache.getFromScroll(result).equals(ReforgeCache.getFromSpellBook(book, 2)), "Extraction preview lost its selected affixes");
                var expected = values(minecraft, result, extracted, 0, CastSource.SCROLL);
                List<Component> resultLines = new ArrayList<>();
                result.getItem().appendHoverText(result, minecraft.level, resultLines, TooltipFlag.NORMAL);
                checkTooltip(resultLines, spell, expected, spell.obfuscateStats(minecraft.player), true, "extracted " + spell.getSpellId() + "/" + profile);
                check(before.equals(book.getTag()), "Extraction preview changed the source book");
            }
        }
        ApotheosisSpells.LOGGER.info("UI_PROBE_MATRIX inscription_lore_pages={} extraction_previews={}", checked, checked / 2);
    }

    static void siphoning(Minecraft minecraft) throws Exception {
        var local = minecraft.player;
        var position = local.position();
        float yaw = local.getYRot();
        float pitch = local.getXRot();
        float oldRange = ((SiphoningBeamRange.Data) local).apoth$getSiphoningRange();
        var remote = new RemotePlayer(minecraft.level, new GameProfile(UUID.randomUUID(), "UIBeamRemote"));
        remote.setId(19070001);
        minecraft.level.addPlayer(remote.getId(), remote);
        Map<BlockPos, BlockState> replaced = new LinkedHashMap<>();
        try {
            local.setPos(position.x, 128, position.z);
            local.setYRot(0); local.setXRot(0);
            remote.setPos(position.x + 4, 128, position.z);
            remote.setYRot(0); remote.setXRot(0);
            float base;
            try (var scope = SpellCastHooks.enter((SpellCastHooks.Context) null)) {
                base = RayOfSiphoningSpell.getRange(0);
            }
            ((SiphoningBeamRange.Data) local).apoth$setSiphoningRange(0);
            assertBeam(minecraft, local, base, "idle_native");
            var server = minecraft.getSingleplayerServer();
            check(server != null, "Siphoning renderer needs the isolated integrated server");
            var packets = server.submit(() -> {
                var caster = server.getPlayerList().getPlayer(local.getUUID());
                var other = FakePlayerFactory.get(caster.serverLevel(), remote.getGameProfile());
                other.setId(remote.getId());
                List<ClientboundSetEntityDataPacket> result = new ArrayList<>();
                List<ClientboundSetEntityDataPacket> ended = new ArrayList<>();
                for (var player : List.of(caster, other)) {
                    var magic = MagicData.getPlayerMagicData(player);
                    magic.getSyncedData();
                    try (var scope = SpellCastHooks.enter(new SpellCastHooks.Snapshot(player.getUUID(),
                            "irons_spellbooks:ray_of_siphoning", 1,
                            new ReforgeCache.Data(1, 1, 1, 1, 0, player == caster ? 1.3f : 2f, 1), SpellEffects.NONE), player)) {
                        magic.initiateCast(SpellRegistry.RAY_OF_SIPHONING_SPELL.get(), 1, 40, CastSource.SPELLBOOK, "spellbook");
                    }
                    var values = player.getEntityData().getNonDefaultValues();
                    check(values != null, "Server startup produced no entity metadata");
                    result.add(new ClientboundSetEntityDataPacket(player.getId(), values));
                    magic.resetCastingState();
                    var cleared = player.getEntityData().packDirty();
                    check(cleared != null, "Server stop produced no dirty metadata");
                    ended.add(new ClientboundSetEntityDataPacket(player.getId(), cleared));
                }
                result.addAll(ended);
                return result;
            }).get();
            for (var packet : packets.subList(0, 2)) deliverMetadata(minecraft, packet);
            assertBeam(minecraft, local, base * 1.3f, "local_snapshot_metadata");
            assertBeam(minecraft, remote, base * 2f, "remote_snapshot_metadata");
            check(Math.abs(((SiphoningBeamRange.Data) local).apoth$getSiphoningRange() - base * 1.3f) < 0.001,
                    "Remote renderer changed the local caster's range");
            for (var caster : List.of(local, remote)) {
                var wall = BlockPos.containing(caster.getX(), caster.getY() + 1, caster.getZ() + 8);
                for (int x = -1; x <= 1; x++) for (int y = -1; y <= 2; y++) {
                    var block = wall.offset(x, y, 0);
                    replaced.putIfAbsent(block, minecraft.level.getBlockState(block));
                    minecraft.level.setBlock(block, Blocks.STONE.defaultBlockState(), 3);
                }
                double distance = assertBeam(minecraft, caster, caster == local ? base * 1.3f : base * 2, "wall_occlusion");
                check(distance < 10, "Native beam ignored the test wall");
            }
            deliverMetadata(minecraft, packets.get(2));
            check(((SiphoningBeamRange.Data) local).apoth$getSiphoningRange() == 0, "Stopped cast metadata retained its range");
            assertBeam(minecraft, local, base, "stopped_falls_back_native");
            var stoppedVertices = new RecordingVertices();
            SpellRenderingHelper.renderSpellHelper(new SyncedSpellData(local), local, new PoseStack(), type -> stoppedVertices, 1f);
            check(stoppedVertices.count == 0, "Idle native render dispatcher emitted a siphoning beam");
        } finally {
            for (var entry : replaced.entrySet()) minecraft.level.setBlock(entry.getKey(), entry.getValue(), 3);
            minecraft.level.removeEntity(remote.getId(), Entity.RemovalReason.DISCARDED);
            ((SiphoningBeamRange.Data) local).apoth$setSiphoningRange(oldRange);
            local.setPos(position); local.setYRot(yaw); local.setXRot(pitch);
        }
        check(SpellCastHooks.get() == null, "Siphoning renderer leaked a preview context");
        ApotheosisSpells.LOGGER.info("UI_PROBE_MATRIX real_siphoning_geometry=6 native_metadata_packet_handler=3 occlusion=2 idle_dispatch=1");
    }

    private static double assertBeam(Minecraft minecraft, net.minecraft.world.entity.LivingEntity caster,
                                     float range, String name) {
        var hit = Utils.raycastForEntity(minecraft.level, caster, range, true);
        double expected = caster.getEyePosition().distanceTo(hit.getLocation());
        var vertices = new RecordingVertices();
        var synced = new SyncedSpellData(caster);
        synced.setIsCasting(true, "irons_spellbooks:ray_of_siphoning", 1, "spellbook");
        SpellRenderingHelper.renderSpellHelper(synced, caster, new PoseStack(), type -> vertices, 1f);
        check(vertices.count > 0, name + " did not execute native vertex rendering");
        check(Math.abs(vertices.maxZ - expected) < 0.56,
                name + " rendered endpoint " + vertices.maxZ + " differs from native raycast " + expected + " for range " + range);
        return expected;
    }

    private static void deliverMetadata(Minecraft minecraft, ClientboundSetEntityDataPacket packet) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.write(buffer);
            minecraft.getConnection().handleSetEntityData(new ClientboundSetEntityDataPacket(buffer));
        } finally {
            buffer.release();
        }
    }

    private static void renderSelection(Minecraft minecraft, SpellWheelOverlay wheel, RecordingGraphics graphics,
                                         int desired, int count, Field selection, Field mouseX, Field mouseY,
                                         SpellCastHooks.Context outer) throws Exception {
        boolean found = false;
        double x = minecraft.getWindow().getScreenWidth() * 0.5;
        double y = minecraft.getWindow().getScreenHeight() * 0.5;
        try (var scope = SpellCastHooks.enter(outer)) {
            for (int step = 0; step < Math.max(16, count * 4); step++) {
                double angle = step * Math.PI * 2 / Math.max(16, count * 4);
                mouseX.setDouble(minecraft.mouseHandler, x + Math.cos(angle) * 130);
                mouseY.setDouble(minecraft.mouseHandler, y + Math.sin(angle) * 130);
                graphics.text.clear();
                wheel.render((ForgeGui) minecraft.gui, graphics, 1f,
                        minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
                check(SpellCastHooks.get() == outer, "Wheel frame failed to restore the surrounding context");
                if (selection.getInt(wheel) == desired) { found = true; break; }
            }
        }
        check(found && !graphics.text.isEmpty(), "Mouse motion never rendered requested wheel selection " + desired);
    }

    private record Values(int level, int storedLevel, int mana, int cooldown, int castTime,
                          List<Component> unique) {}

    private static Values values(Minecraft minecraft, ItemStack source, SpellData data, int index, CastSource castSource) {
        var spell = data.getSpell();
        var context = SpellCastHooks.buildContext(source, minecraft.player, index, data.getLevel(), data);
        CompoundTag affixData = source.getItem() instanceof SpellBook ? ReforgeCache.getBookAffix(source, index)
                : ReforgeCache.scrollAffixData(source);
        check(affixData == null || affixData.isEmpty() || !context.data().equals(ReforgeCache.Data.DEF),
                "Affixed UI fixture silently degraded to default data for " + spell.getSpellId() + " slot " + index);
        int level;
        try (var ignored = SpellCastHooks.enter((SpellCastHooks.Context) null)) {
            level = spell.getLevelFor(data.getLevel(), minecraft.player) + context.data().lvl();
        }
        var snapshot = new SpellCastHooks.Snapshot(minecraft.player.getUUID(), spell.getSpellId(), level, context.data(), SpellEffects.NONE);
        try (var scope = SpellCastHooks.enter(snapshot, minecraft.player)) {
            return new Values(level, data.getLevel(), spell.getManaCost(level),
                    MagicManager.getEffectiveSpellCooldown(spell, minecraft.player, castSource),
                    spell.getEffectiveCastTime(level, minecraft.player), new ArrayList<>(spell.getUniqueInfo(level, minecraft.player)));
        }
    }

    private static void checkTooltip(List<? extends Component> lines, AbstractSpell spell, Values expected,
                                      boolean hidden, boolean scroll, String label) {
        String text = String.join("\n", lines.stream().map(Component::getString).toList());
        var level = Component.literal(levelText(expected));
        var title = scroll ? Component.translatable("tooltip.irons_spellbooks.level", level)
                : Component.translatable("tooltip.irons_spellbooks.selected_spell", spell.getDisplayName(Minecraft.getInstance().player), level);
        contains(text, title.getString(), label + " complete level/title");
        if (expected.mana > 0) contains(text, TooltipsUtils.getManaCostComponent(spell.getCastType(), expected.mana).getString(), label + " mana");
        if (spell.getSpellCooldown() > 0) contains(text, Component.translatable("tooltip.irons_spellbooks.cooldown_length_seconds",
                Utils.timeFromTicks(expected.cooldown, 2)).getString(), label + " cooldown");
        if (spell.getCastType() != CastType.INSTANT) contains(text,
                TooltipsUtils.getCastTimeComponent(spell.getCastType(), Utils.timeFromTicks(expected.castTime, 2)).getString(), label + " cast time");
        if (!hidden) for (var line : expected.unique) contains(text, line.getString(), label + " native unique info");
    }

    private static void checkWheel(List<String> lines, AbstractSpell spell, Values expected, boolean hidden, String label) {
        String text = String.join("\n", lines);
        contains(text, Component.translatable("ui.irons_spellbooks.level", Component.literal(levelText(expected))).getString(),
                "wheel " + label + " complete level row");
        contains(text, Component.translatable("ui.irons_spellbooks.mana_cost", expected.mana).getString(), "wheel " + label + " mana");
        contains(text, Component.translatable("tooltip.irons_spellbooks.cooldown_length_seconds", Utils.timeFromTicks(expected.cooldown, 2)).getString(), "wheel " + label + " cooldown");
        if (!hidden) for (var line : expected.unique) contains(text, line.getString(), "wheel " + label + " unique info");
    }

    private static void checkInscription(List<String> lines, AbstractSpell spell, Values expected, boolean hidden, String label) {
        String text = String.join("\n", lines);
        contains(text, Component.translatable("ui.irons_spellbooks.level", expected.level).getString(), "inscription " + label + " level");
        contains(text, Component.translatable("ui.irons_spellbooks.mana_cost", Component.literal(String.valueOf(expected.mana))).getString(),
                "inscription " + label + " mana");
        contains(text, Component.translatable("ui.irons_spellbooks.cooldown", Component.literal(Utils.timeFromTicks(expected.cooldown, 1))).getString(),
                "inscription " + label + " cooldown");
        if (spell.getCastType() != CastType.INSTANT) contains(text,
                TooltipsUtils.getCastTimeComponent(spell.getCastType(), Utils.timeFromTicks(expected.castTime, 1)).getString(), "inscription " + label + " cast time");
        if (!hidden) for (var line : expected.unique) contains(text, line.getString(), "inscription " + label + " unique info");
    }

    private static String levelText(Values expected) {
        int delta = expected.level - expected.storedLevel;
        return expected.level + (delta == 0 ? "" : delta > 0 ? " (+" + delta + ")" : " (" + delta + ")");
    }

    private static List<String> profiles(AbstractSpell spell) {
        var result = new ArrayList<>(List.of("plain", "spell_level", "mana_cost", "cooldown", "cast_time", "spell_power", "combined"));
        if (SpellAffix.supportsRadius(spell)) result.add("spell_radius");
        if (SpellAffix.supportsDuration(spell)) result.add("spell_duration");
        return result;
    }

    private static ItemStack scroll(AbstractSpell spell, String profile) throws Exception {
        var result = item("scroll");
        result.setTag(TagParser.parseTag("{\"irons_spellbooks:spell_container\":{maxSpells:1,mustEquip:0b,spellWheel:0b,data:[{index:0,id:\""
                + spell.getSpellId() + "\",level:" + secondLevel(spell) + "}]}}"));
        if (!profile.equals("plain")) result.getOrCreateTag().put("affix_data", affixes(spell, profile));
        return result;
    }

    private static ItemStack book(AbstractSpell spell, String profile) throws Exception {
        var result = item("netherite_spell_book");
        result.setTag(TagParser.parseTag("{\"irons_spellbooks:spell_container\":{maxSpells:12,mustEquip:1b,spellWheel:1b,data:"
                + "[{index:0,id:\"" + spell.getSpellId() + "\",level:1},{index:2,id:\"" + spell.getSpellId()
                + "\",level:" + secondLevel(spell) + "}]}}"));
        if (!profile.equals("plain")) ReforgeCache.setBookAffix(result, 2, affixes(spell, profile));
        return result;
    }

    private static CompoundTag affixes(AbstractSpell spell, String profile) {
        var result = new CompoundTag();
        result.putString("rarity", "apotheosis:ancient");
        var entries = new CompoundTag();
        for (var name : List.of("spell_level", "mana_cost", "cooldown", "cast_time", "spell_power", "spell_radius", "spell_duration")) {
            if (name.equals("spell_radius") && !SpellAffix.supportsRadius(spell)
                    || name.equals("spell_duration") && !SpellAffix.supportsDuration(spell)) continue;
            if (profile.equals(name) || profile.equals("combined")) entries.putFloat("apotheosis_spells:scroll/spell_modifier/" + name, 1);
        }
        result.put("affixes", entries);
        return result;
    }

    private static ItemStack item(String path) {
        var item = ForgeRegistries.ITEMS.getValue(ResourceLocation.fromNamespaceAndPath("irons_spellbooks", path));
        check(item != null, "Missing native item " + path);
        return new ItemStack(item);
    }

    private static int secondLevel(AbstractSpell spell) { return Math.min(2, spell.getMaxLevel()); }

    private static Component hoverText(Component component) {
        var found = findHoverText(component);
        check(found != null, "Native page and its title siblings have no SHOW_TEXT spell detail");
        return found;
    }

    private static Component findHoverText(Component component) {
        var hover = component.getStyle().getHoverEvent();
        if (hover != null && hover.getValue(HoverEvent.Action.SHOW_TEXT) != null) return hover.getValue(HoverEvent.Action.SHOW_TEXT);
        for (var child : component.getSiblings()) {
            var found = findHoverText(child);
            if (found != null) return found;
        }
        return null;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }

    private static Method method(Class<?> type, String name, Class<?>... arguments) throws Exception {
        var method = type.getDeclaredMethod(name, arguments); method.setAccessible(true); return method;
    }

    private static void contains(String text, String expected, String label) {
        check(text.contains(expected), label + " omitted [" + expected + "] in [" + text + "]");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class RecordingGraphics extends GuiGraphics {
        private final List<String> text = new ArrayList<>();
        private boolean failOnText;

        private RecordingGraphics(Minecraft minecraft) { super(minecraft, minecraft.renderBuffers().bufferSource()); }

        private void record(String value) {
            if (failOnText) throw new IllegalStateException("UI_PROBE_EXPECTED_RENDER_FAILURE");
            text.add(value);
        }

        @Override public int drawString(Font font, Component value, int x, int y, int color, boolean shadow) {
            record(value.getString()); return super.drawString(font, value, x, y, color, shadow);
        }
        @Override public int drawString(Font font, String value, int x, int y, int color, boolean shadow) {
            record(value); return super.drawString(font, value, x, y, color, shadow);
        }
        @Override public int drawString(Font font, FormattedCharSequence value, int x, int y, int color, boolean shadow) {
            var rendered = new StringBuilder();
            value.accept((index, style, point) -> { rendered.appendCodePoint(point); return true; });
            record(rendered.toString()); return super.drawString(font, value, x, y, color, shadow);
        }
        @Override public int drawString(Font font, String value, float x, float y, int color, boolean shadow) {
            record(value); return super.drawString(font, value, x, y, color, shadow);
        }
        @Override public int drawString(Font font, FormattedCharSequence value, float x, float y, int color, boolean shadow) {
            var rendered = new StringBuilder();
            value.accept((index, style, point) -> { rendered.appendCodePoint(point); return true; });
            record(rendered.toString()); return super.drawString(font, value, x, y, color, shadow);
        }
        @Override public void drawWordWrap(Font font, FormattedText value, int x, int y, int width, int color) {
            record(value.getString()); super.drawWordWrap(font, value, x, y, width, color);
        }
    }

    private static final class RecordingVertices implements VertexConsumer {
        private int count;
        private double maxZ = -Double.MAX_VALUE;
        @Override public VertexConsumer vertex(double x, double y, double z) { maxZ = Math.max(maxZ, z); return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer uv(float u, float v) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public void endVertex() { count++; }
        @Override public void defaultColor(int r, int g, int b, int a) {}
        @Override public void unsetDefaultColor() {}
    }
}
