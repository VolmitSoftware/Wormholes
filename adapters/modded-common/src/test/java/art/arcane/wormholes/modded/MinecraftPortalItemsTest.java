package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LocaleOverlay;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.PortalType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MinecraftPortalItemsTest {
    private static HolderLookup.Provider registries;
    private static MinecraftPortalItems english;

    @BeforeClass
    public static void bootstrap() {
        english();
    }

    static synchronized MinecraftPortalItems english() {
        if (english == null) {
            MinecraftPortalToolsTest.bootstrap();
            for (Item item : List.of(Items.PRISMARINE, Items.DARK_PRISMARINE)) {
                item.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
            }
            registries = VanillaRegistries.createLookup();
            english = new MinecraftPortalItems(registries, snapshot(List.of()));
        }
        return english;
    }

    @Test
    public void wandMatchesTheBukkitTemplate() {
        ItemStack wand = english.wand();
        assertTemplate(wand, Items.BLAZE_ROD, "Portal Wand", ChatFormatting.GOLD);
        assertTrue(MinecraftPortalTools.isWand(wand));
        assertTrue(ItemStack.isSameItemSameComponents(wand, english.wand()));
    }

    @Test
    public void runesMatchTheBukkitTemplates() {
        ItemStack portal = english.rune(PortalType.PORTAL);
        ItemStack wormhole = english.wormholeRune();
        assertTemplate(portal, Items.PRISMARINE, "Portal Rune", ChatFormatting.GOLD);
        assertTemplate(wormhole, Items.DARK_PRISMARINE, "Wormhole Rune", ChatFormatting.GOLD);
        assertEquals(Optional.of(PortalType.PORTAL), MinecraftPortalConstruction.runeType(portal));
        assertEquals(Optional.of(PortalType.WORMHOLE), MinecraftPortalConstruction.runeType(wormhole));
        assertTrue(MinecraftDoorItems.isWormholeRune(wormhole));
        assertFalse(MinecraftDoorItems.isWormholeRune(portal));
        assertTrue(ItemStack.isSameItemSameComponents(wormhole, english.rune(PortalType.WORMHOLE)));
        assertThrows(IllegalArgumentException.class, () -> english.rune(PortalType.GATEWAY));
        assertThrows(IllegalArgumentException.class, () -> english.rune(PortalType.RTP));
    }

    @Test
    public void namesFollowTheSuppliedServerLanguage() {
        MinecraftPortalItems translated = new MinecraftPortalItems(registries, snapshot(List.of(LocaleOverlay.builder("de_DE")
            .lines(WormholesMessages.ITEM_PORTAL_WAND.id(), "&6&lPortalstab&r")
            .lines(WormholesMessages.ITEM_PORTAL_RUNE.id(), "&6&lPortalrune&r")
            .lines(WormholesMessages.ITEM_WORMHOLE_RUNE.id(), "&b&lWurmlochrune&r")
            .build())));
        assertTemplate(translated.wand(), Items.BLAZE_ROD, "Portalstab", ChatFormatting.GOLD);
        assertTemplate(translated.rune(PortalType.PORTAL), Items.PRISMARINE, "Portalrune", ChatFormatting.GOLD);
        assertTemplate(translated.wormholeRune(), Items.DARK_PRISMARINE, "Wurmlochrune", ChatFormatting.AQUA);
        assertTrue(MinecraftPortalTools.isWand(translated.wand()));
        assertEquals(Optional.of(PortalType.PORTAL), MinecraftPortalConstruction.runeType(translated.rune(PortalType.PORTAL)));
        assertTrue(MinecraftDoorItems.isWormholeRune(translated.wormholeRune()));
        assertFalse(ItemStack.isSameItemSameComponents(translated.wand(), english.wand()));
    }

    @Test
    public void identityAcceptsEarlierNativeToolsAndRejectsLookalikes() {
        ItemStack earlierWand = new ItemStack(Items.BLAZE_ROD);
        earlierWand.set(DataComponents.CUSTOM_DATA, CustomData.of(tag("wormholes:wand")));
        earlierWand.set(DataComponents.CUSTOM_NAME, Component.literal("Wormholes Wand"));
        earlierWand.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Left click the selection to open a portal."))));
        assertTrue(MinecraftPortalTools.isWand(earlierWand));
        ItemStack earlierPortalRune = new ItemStack(Items.PRISMARINE);
        earlierPortalRune.set(DataComponents.CUSTOM_DATA, CustomData.of(rune("PORTAL")));
        earlierPortalRune.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        assertEquals(Optional.of(PortalType.PORTAL), MinecraftPortalConstruction.runeType(earlierPortalRune));
        ItemStack earlierWormholeRune = new ItemStack(Items.DARK_PRISMARINE);
        earlierWormholeRune.set(DataComponents.CUSTOM_DATA, CustomData.of(rune("WORMHOLE")));
        earlierWormholeRune.set(DataComponents.CUSTOM_NAME, Component.literal("Wormhole Rune"));
        earlierWormholeRune.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        assertTrue(MinecraftDoorItems.isWormholeRune(earlierWormholeRune));
        assertEquals(Optional.of(PortalType.WORMHOLE), MinecraftPortalConstruction.runeType(earlierWormholeRune));

        ItemStack unmarkedWand = english.wand();
        unmarkedWand.remove(DataComponents.CUSTOM_DATA);
        assertFalse(MinecraftPortalTools.isWand(unmarkedWand));
        ItemStack unmarkedRune = english.wormholeRune();
        unmarkedRune.remove(DataComponents.CUSTOM_DATA);
        assertFalse(MinecraftDoorItems.isWormholeRune(unmarkedRune));
        assertEquals(Optional.empty(), MinecraftPortalConstruction.runeType(unmarkedRune));
        ItemStack wrongMaterial = new ItemStack(Items.DARK_PRISMARINE);
        wrongMaterial.set(DataComponents.CUSTOM_DATA, CustomData.of(rune("PORTAL")));
        assertEquals(Optional.empty(), MinecraftPortalConstruction.runeType(wrongMaterial));
    }

    private static void assertTemplate(ItemStack item, Item material, String name, ChatFormatting color) {
        assertTrue(item.is(material));
        assertEquals(1, item.getCount());
        Set<DataComponentType<?>> patched = new HashSet<>();
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : item.getComponentsPatch().entrySet()) {
            assertTrue(entry.getValue().isPresent());
            patched.add(entry.getKey());
        }
        assertEquals(Set.of(DataComponents.CUSTOM_DATA, DataComponents.CUSTOM_NAME, DataComponents.ENCHANTMENTS), patched);
        Component customName = item.get(DataComponents.CUSTOM_NAME);
        assertEquals(name, customName.getString());
        List<Style> styles = new ArrayList<>();
        customName.visit((style, text) -> {
            if (!text.isEmpty()) {
                styles.add(style);
            }
            return Optional.empty();
        }, Style.EMPTY);
        assertEquals(List.of(Style.EMPTY.withColor(color).withBold(true).withItalic(false).withUnderlined(false)
            .withStrikethrough(false).withObfuscated(false)), styles);
        Holder<Enchantment> infinity = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.INFINITY);
        assertEquals(1, item.getEnchantments().size());
        assertEquals(1, item.getEnchantments().getLevel(infinity));
        TooltipDisplay tooltip = item.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT);
        assertFalse(tooltip.hideTooltip());
        assertTrue(tooltip.shows(DataComponents.ENCHANTMENTS));
        assertTrue(item.hasFoil());
        assertTrue(item.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().isEmpty());
    }

    private static LocalizationSnapshot snapshot(List<LocaleOverlay> overlays) {
        return LocalizationSnapshot.create(new LocalizationCandidate(WormholesMessages.catalog(), overlays, PluralSelector.oneOther()));
    }

    private static CompoundTag tag(String flag) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(flag, true);
        return tag;
    }

    private static CompoundTag rune(String type) {
        CompoundTag tag = new CompoundTag();
        tag.putString("wormholes:rune", type);
        return tag;
    }
}
