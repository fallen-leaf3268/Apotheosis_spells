package com.example.apotheosis_spells.handler;

import com.mojang.authlib.GameProfile;
import io.redspace.ironsspellbooks.api.events.SpellPreCastEvent;
import io.redspace.ironsspellbooks.api.item.CastingImplementData;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.gui.overlays.SpellSelection;
import io.redspace.ironsspellbooks.player.ServerPlayerEvents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder("apotheosis_spells")
@PrefixGameTestTemplate(false)
public final class CastingImplementGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void staffAndMarkedImplementUseSelectedPhysicalBookSlot(GameTestHelper helper) throws Exception {
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "[ImplementSlot]"));
        var book = new ItemStack(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("irons_spellbooks:netherite_spell_book")));
        book.setTag(TagParser.parseTag("""
                {"irons_spellbooks:spell_container":{maxSpells:12,mustEquip:1b,spellWheel:1b,
                data:[{index:0,id:"irons_spellbooks:fireball",level:1},{index:2,id:"irons_spellbooks:fireball",level:2},
                {index:4,id:"irons_spellbooks:scorch",level:1}]},
                apoth_book_affixes:{"2":{spell_id:"irons_spellbooks:fireball",affix_data:{rarity:"apotheosis:ancient",
                affixes:{"apotheosis_spells:scroll/spell_modifier/mana_cost":1.0f}}}}}
                """));
        io.redspace.ironsspellbooks.api.util.Utils.setPlayerSpellbookStack(player, book);
        var magic = MagicData.getPlayerMagicData(player);
        magic.setMana(100);
        var observed = new ArrayList<Float>();
        Consumer<SpellPreCastEvent> listener = event -> {
            if (event.getEntity() != player) return;
            var snapshot = SpellCastHooks.currentSnapshot();
            helper.assertTrue(snapshot != null, "Implement request lost its cast snapshot");
            observed.add(snapshot.data().mana());
            event.setCanceled(true);
        };
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, SpellPreCastEvent.class, listener);
        try {
            var staff = new ItemStack(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("irons_spellbooks:graybeard_staff")));
            helper.assertTrue(!staff.isEmpty() && staff.getItem() instanceof io.redspace.ironsspellbooks.item.CastingItem,
                    "Native staff fixture is unavailable");
            var marked = new ItemStack(Items.STICK);
            CastingImplementData.set(marked, true);
            for (var hand : InteractionHand.values()) {
                for (var implement : new ItemStack[]{staff, marked}) {
                    player.setItemInHand(hand, implement);
                    for (int selection : new int[]{1, 2}) {
                        magic.getSyncedData().setSpellSelection(new SpellSelection("spellbook", selection));
                        int before = observed.size();
                        if (implement == staff) {
                            staff.getItem().use(helper.getLevel(), player, hand);
                        } else {
                            ServerPlayerEvents.onUseItem(new PlayerInteractEvent.RightClickItem(player, hand));
                        }
                        float expected = selection == 1 ? .5f : 1;
                        helper.assertTrue(observed.size() == before + 1 && observed.get(before) == expected,
                                "Implement lost its selected book slot: hand=" + hand + " selection=" + selection + " observed=" + observed);
                        helper.assertTrue(player.getItemInHand(hand) == implement, "Implement request changed the held item");
                        helper.assertTrue(SpellCastHooks.get() == null && SpellCastHooks.currentSnapshot() == null,
                                "Cancelled implement request leaked context");
                    }
                    player.setItemInHand(hand, ItemStack.EMPTY);
                }
            }
            helper.succeed();
        } finally {
            MinecraftForge.EVENT_BUS.unregister(listener);
            BookAttributeHandler.onLogout(new PlayerEvent.PlayerLoggedOutEvent(player));
        }
    }
}
