package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.localization.WormholesMessageRenderer;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.PortalType;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.Objects;

public record MinecraftPortalItems(HolderLookup.Provider registries, LocalizationSnapshot language) {
    public MinecraftPortalItems {
        Objects.requireNonNull(registries, "registries");
        Objects.requireNonNull(language, "language");
    }

    public static MinecraftPortalItems of(WormholesModRuntime runtime) {
        return new MinecraftPortalItems(runtime.server().registryAccess(), runtime.localization().snapshot(null));
    }

    public ItemStack wand() {
        return template(Items.BLAZE_ROD, WormholesMessages.ITEM_PORTAL_WAND, MinecraftPortalTools.WAND_IDENTITY);
    }

    public ItemStack wormholeRune() {
        return rune(PortalType.WORMHOLE);
    }

    public ItemStack rune(PortalType type) {
        return switch (type) {
            case PORTAL -> template(Items.PRISMARINE, WormholesMessages.ITEM_PORTAL_RUNE, runeIdentity(type));
            case WORMHOLE -> template(Items.DARK_PRISMARINE, WormholesMessages.ITEM_WORMHOLE_RUNE, runeIdentity(type));
            case GATEWAY, RTP -> throw new IllegalArgumentException("Only portal and wormhole runes can be placed");
        };
    }

    private ItemStack template(Item material, LinesKey name, CompoundTag identity) {
        ItemStack item = new ItemStack(material);
        item.set(DataComponents.CUSTOM_DATA, CustomData.of(identity));
        item.enchant(registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.INFINITY), 1);
        item.set(DataComponents.CUSTOM_NAME, MinecraftLegacyText.component(
            WormholesMessageRenderer.legacyLines(language.resolve(name, MessageArgs.empty())).getFirst()));
        return item;
    }

    private static CompoundTag runeIdentity(PortalType type) {
        CompoundTag identity = new CompoundTag();
        identity.putString("wormholes:rune", type.name());
        return identity;
    }
}
