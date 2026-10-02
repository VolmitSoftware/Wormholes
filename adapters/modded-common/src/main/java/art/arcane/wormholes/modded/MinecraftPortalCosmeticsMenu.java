package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.BlackoutColor;
import art.arcane.wormholes.portal.PortalSurfaceSkins;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.Objects;

final class MinecraftPortalCosmeticsMenu {
    private static final String GLASS_SKIN = "minecraft:glass";

    private final MinecraftPortalMenus menus;

    MinecraftPortalCosmeticsMenu(MinecraftPortalMenus menus) {
        this.menus = Objects.requireNonNull(menus);
    }

    MinecraftElement blackoutElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = new MinecraftElement("blackout-background");
        element.onLeftClick(clicked -> {
            boolean enabled = !portal.isBlackoutBackground();
            menus.update(viewer, portal, target -> target.setBlackoutBackground(enabled));
            applyBlackoutElement(viewer, element, portal);
            window.updateInventory();
            MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED, MinecraftPortalText.arguments(
                "label", MinecraftPortalText.localized(viewer, WormholesMessages.PORTAL_NETWORK_LABEL_BLACKOUT),
                "value", MinecraftPortalText.onOffLabel(viewer, portal.isBlackoutBackground())));
        });
        element.onRightClick(clicked -> menus.runEntity(viewer, () -> {
            window.close();
            uiOpenBlackoutColorMenu(viewer, portal);
        }));
        applyBlackoutElement(viewer, element, portal);
        return element;
    }

    private void applyBlackoutElement(ServerPlayer viewer, MinecraftElement element, MinecraftPortal portal) {
        MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_BLACKOUT, MinecraftPortalText.arguments(
            "state", MinecraftPortalText.onOffLabel(viewer, portal.isBlackoutBackground()),
            "color", portal.getBlackoutColor().displayName()));
        element.setMaterial(portal.isBlackoutBackground() ? blackoutColorMaterial(portal.getBlackoutColor()) : Items.GLASS);
        element.setEnchanted(portal.isBlackoutBackground());
    }

    private void uiOpenBlackoutColorMenu(ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftWindow window = menus.window(viewer, portal);
        window.setViewportHeight(4);
        window.setDecorator(Items.STAINED_GLASS_PANE.black());
        refreshBlackoutColorOptions(window, viewer, portal);
        window.setElement(0, 3, menus.settings().backToSettingsMenuElement(window, viewer, portal));
        window.open();
    }

    private void refreshBlackoutColorOptions(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        window.setElement(0, 0, MinecraftPortalText.localizedElement(viewer, "blackout-color-placard",
            WormholesMessages.PORTAL_MENU_BLACKOUT_COLOR_PLACARD,
            MinecraftPortalText.arguments("color", portal.getBlackoutColor().displayName()), blackoutColorMaterial(portal.getBlackoutColor())));
        BlackoutColor[] colors = BlackoutColor.values();
        for (int index = 0; index < colors.length; index++) {
            int row = 1 + (index / 8);
            int column = -4 + (index % 8);
            window.setElement(column, row, blackoutColorOptionElement(window, viewer, portal, colors[index]));
        }
    }

    private MinecraftElement blackoutColorOptionElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal, BlackoutColor color) {
        MinecraftElement element = MinecraftPortalText.localizedElement(viewer, "blackout-color-" + color.name().toLowerCase(Locale.ROOT),
            WormholesMessages.PORTAL_MENU_BLACKOUT_COLOR_OPTION, MinecraftPortalText.arguments("color", color.displayName()),
            blackoutColorMaterial(color));
        element.setEnchanted(color == portal.getBlackoutColor());
        element.onLeftClick(clicked -> {
            menus.update(viewer, portal, target -> target.setBlackoutColor(color));
            refreshBlackoutColorOptions(window, viewer, portal);
            window.updateInventory();
            MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED, MinecraftPortalText.arguments(
                "label", MinecraftPortalText.localized(viewer, WormholesMessages.PORTAL_NETWORK_LABEL_BLACKOUT_COLOR),
                "value", color.displayName()));
        });
        return element;
    }

    private static Item blackoutColorMaterial(BlackoutColor color) {
        return Items.CONCRETE.pick(DyeColor.valueOf(color.name()));
    }

    MinecraftElement ambientParticlesElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = new MinecraftElement("ambient-particles");
        element.onLeftClick(clicked -> {
            AmbientParticleStyle next = portal.getAmbientStyle().next();
            menus.update(viewer, portal, target -> target.setAmbientStyle(next));
            applyAmbientParticlesElement(viewer, element, portal);
            window.updateInventory();
            MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED, MinecraftPortalText.arguments(
                "label", MinecraftPortalText.localized(viewer, WormholesMessages.PORTAL_NETWORK_LABEL_AMBIENT_STYLE),
                "value", ambientStyleDisplay(viewer, portal)));
        });
        element.onRightClick(clicked -> menus.runEntity(viewer, () -> {
            window.close();
            uiOpenAmbientColorMenu(viewer, portal);
        }));
        applyAmbientParticlesElement(viewer, element, portal);
        return element;
    }

    private void applyAmbientParticlesElement(ServerPlayer viewer, MinecraftElement element, MinecraftPortal portal) {
        MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_AMBIENT_PARTICLES, MinecraftPortalText.arguments(
            "style", ambientStyleDisplay(viewer, portal),
            "color", ambientColorHex(portal)));
        element.setMaterial(switch (portal.getAmbientStyle()) {
            case SPARKS -> Items.FIREWORK_STAR;
            case OUTLINE -> Items.BLAZE_ROD;
            case CORNERS -> Items.END_ROD;
            case OFF -> Items.GLASS;
        });
        element.setEnchanted(portal.getAmbientStyle() != AmbientParticleStyle.OFF);
    }

    private void uiOpenAmbientColorMenu(ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftWindow window = menus.window(viewer, portal);
        window.setViewportHeight(5);
        window.setDecorator(Items.STAINED_GLASS_PANE.black());
        refreshAmbientColorMenu(window, viewer, portal);
        window.setElement(0, 4, menus.settings().backToSettingsMenuElement(window, viewer, portal));
        window.open();
    }

    private void refreshAmbientColorMenu(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        window.setElement(0, 0, MinecraftPortalText.localizedElement(viewer, "ambient-color-placard",
            WormholesMessages.PORTAL_MENU_AMBIENT_COLOR_PLACARD, MinecraftPortalText.arguments("color", ambientColorHex(portal)), Items.FIREWORK_STAR));
        window.setElement(-2, 1, ambientChannelElement(window, viewer, portal, AmbientChannel.RED));
        window.setElement(0, 1, ambientChannelElement(window, viewer, portal, AmbientChannel.GREEN));
        window.setElement(2, 1, ambientChannelElement(window, viewer, portal, AmbientChannel.BLUE));
        DyeColor[] dyes = DyeColor.values();
        for (int index = 0; index < dyes.length; index++) {
            int row = 2 + (index / 8);
            int column = -4 + (index % 8);
            window.setElement(column, row, ambientColorOptionElement(window, viewer, portal, dyes[index]));
        }
    }

    private MinecraftElement ambientChannelElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal, AmbientChannel channel) {
        MinecraftElement element = new MinecraftElement(channel.id());
        element.onLeftClick(clicked -> adjustAmbientChannel(window, viewer, portal, channel, 8));
        element.onRightClick(clicked -> adjustAmbientChannel(window, viewer, portal, channel, -8));
        element.onShiftLeftClick(clicked -> adjustAmbientChannel(window, viewer, portal, channel, 32));
        element.onShiftRightClick(clicked -> adjustAmbientChannel(window, viewer, portal, channel, -32));
        applyAmbientChannelElement(viewer, element, channel, channel.get(portal.getAmbientColor()));
        return element;
    }

    private void adjustAmbientChannel(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal, AmbientChannel channel, int delta) {
        int previous = channel.get(portal.getAmbientColor());
        menus.update(viewer, portal, target -> target.setAmbientColor(channel.set(target.getAmbientColor(), previous + delta)));
        int current = channel.get(portal.getAmbientColor());
        refreshAmbientColorMenu(window, viewer, portal);
        window.updateInventory();
        if (current != previous) {
            MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED,
                MinecraftPortalText.arguments("label", MinecraftPortalText.localized(viewer, channel.label()), "value", current));
        }
    }

    private void applyAmbientChannelElement(ServerPlayer viewer, MinecraftElement element, AmbientChannel channel, int value) {
        MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_AMBIENT_CHANNEL, MinecraftPortalText.arguments(
            "label", MinecraftPortalText.localized(viewer, channel.label()),
            "value", value,
            "step", 8,
            "large_step", 32));
        element.setMaterial(channel.material());
    }

    private MinecraftElement ambientColorOptionElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal, DyeColor color) {
        int rgb = dyeRgb(color);
        MinecraftElement element = MinecraftPortalText.localizedElement(viewer, "ambient-color-" + color.name().toLowerCase(Locale.ROOT),
            WormholesMessages.PORTAL_MENU_AMBIENT_COLOR_OPTION, MinecraftPortalText.arguments("color", dyeDisplayName(color)),
            Items.WOOL.pick(color));
        element.setEnchanted(nearestDye(portal.getAmbientColor()) == color);
        element.onLeftClick(clicked -> {
            menus.update(viewer, portal, target -> target.setAmbientColor(rgb));
            refreshAmbientColorMenu(window, viewer, portal);
            window.updateInventory();
            MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED, MinecraftPortalText.arguments(
                "label", MinecraftPortalText.localized(viewer, WormholesMessages.PORTAL_NETWORK_LABEL_AMBIENT_COLOR),
                "value", dyeDisplayName(color)));
        });
        return element;
    }

    private static int clampColorChannel(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > 255) {
            return 255;
        }
        return value;
    }

    private static String ambientColorHex(MinecraftPortal portal) {
        return String.format(Locale.ROOT, "#%06X", portal.getAmbientColor());
    }

    private static String ambientStyleDisplay(ServerPlayer viewer, MinecraftPortal portal) {
        return MinecraftPortalText.localized(viewer, switch (portal.getAmbientStyle()) {
            case SPARKS -> WormholesMessages.PORTAL_LABEL_AMBIENT_STYLE_SPARKS;
            case OUTLINE -> WormholesMessages.PORTAL_LABEL_AMBIENT_STYLE_OUTLINE;
            case CORNERS -> WormholesMessages.PORTAL_LABEL_AMBIENT_STYLE_CORNERS;
            case OFF -> WormholesMessages.PORTAL_LABEL_AMBIENT_STYLE_OFF;
        });
    }

    private static int dyeRgb(DyeColor color) {
        return color.getTextureDiffuseColor() & 0xFFFFFF;
    }

    private static DyeColor nearestDye(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        DyeColor best = DyeColor.WHITE;
        long bestDistance = Long.MAX_VALUE;
        for (DyeColor color : DyeColor.values()) {
            int candidate = dyeRgb(color);
            long dr = red - ((candidate >> 16) & 0xFF);
            long dg = green - ((candidate >> 8) & 0xFF);
            long db = blue - (candidate & 0xFF);
            long distance = (dr * dr) + (dg * dg) + (db * db);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = color;
            }
        }
        return best;
    }

    private static String dyeDisplayName(DyeColor color) {
        String[] parts = color.name().split("_");
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < parts.length; index++) {
            if (index > 0) {
                builder.append(' ');
            }
            String part = parts[index];
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            builder.append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }

    MinecraftElement surfaceSkinElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = new MinecraftElement("surface-skin");
        element.onLeftClick(clicked -> {
            if (!hasSurfaceSkin(portal)) {
                return;
            }
            if (!menus.ensureCanEditSurfaceSkin(viewer, portal)) {
                return;
            }
            menus.update(viewer, portal, target -> target.setSurfaceSkin(""));
            applySurfaceSkinElement(viewer, element, portal);
            window.updateInventory();
            notifySurfaceSkin(viewer, portal);
        });
        element.onRightClick(clicked -> menus.runEntity(viewer, () -> {
            window.close();
            uiOpenSurfaceSkinMenu(viewer, portal);
        }));
        applySurfaceSkinElement(viewer, element, portal);
        return element;
    }

    private void applySurfaceSkinElement(ServerPlayer viewer, MinecraftElement element, MinecraftPortal portal) {
        MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_SURFACE_SKIN,
            MinecraftPortalText.arguments("skin", surfaceSkinDisplay(viewer, portal)));
        element.setMaterial(surfaceSkinIcon(portal));
        element.setEnchanted(hasSurfaceSkin(portal));
    }

    private void uiOpenSurfaceSkinMenu(ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftWindow window = menus.window(viewer, portal);
        window.setViewportHeight(3);
        window.setDecorator(Items.STAINED_GLASS_PANE.cyan());
        refreshSurfaceSkinMenu(window, viewer, portal);
        window.setElement(0, 2, menus.settings().backToSettingsMenuElement(window, viewer, portal));
        window.open();
    }

    private void refreshSurfaceSkinMenu(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        window.setElement(0, 0, MinecraftPortalText.localizedElement(viewer, "surface-skin-placard",
            WormholesMessages.PORTAL_MENU_SURFACE_SKIN_PLACARD,
            MinecraftPortalText.arguments("skin", surfaceSkinDisplay(viewer, portal)), surfaceSkinIcon(portal)));
        window.setElement(-1, 1, surfaceSkinGlassElement(window, viewer, portal));
        window.setElement(1, 1, surfaceSkinClearElement(window, viewer, portal));
    }

    private MinecraftElement surfaceSkinGlassElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = MinecraftPortalText.localizedElement(viewer, "surface-skin-glass",
            WormholesMessages.PORTAL_MENU_SURFACE_SKIN_GLASS, MessageArgs.empty(), Items.GLASS);
        element.setEnchanted(GLASS_SKIN.equals(portal.getSurfaceSkin()));
        element.onLeftClick(clicked -> {
            if (!menus.ensureCanEditSurfaceSkin(viewer, portal)) {
                return;
            }
            menus.update(viewer, portal, target -> target.setSurfaceSkin(GLASS_SKIN));
            refreshSurfaceSkinMenu(window, viewer, portal);
            window.updateInventory();
            notifySurfaceSkin(viewer, portal);
        });
        return element;
    }

    private MinecraftElement surfaceSkinClearElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = MinecraftPortalText.localizedElement(viewer, "surface-skin-clear",
            WormholesMessages.PORTAL_MENU_SURFACE_SKIN_CLEAR, MessageArgs.empty(), Items.BARRIER);
        element.onLeftClick(clicked -> {
            if (!hasSurfaceSkin(portal)) {
                return;
            }
            if (!menus.ensureCanEditSurfaceSkin(viewer, portal)) {
                return;
            }
            menus.update(viewer, portal, target -> target.setSurfaceSkin(""));
            refreshSurfaceSkinMenu(window, viewer, portal);
            window.updateInventory();
            notifySurfaceSkin(viewer, portal);
        });
        return element;
    }

    boolean applySurfaceSkinFromInteraction(ServerPlayer player, MinecraftPortal portal, String skin) {
        if (!menus.ensureCanEditSurfaceSkin(player, portal)) {
            return false;
        }
        String previous = portal.getSurfaceSkin();
        menus.update(player, portal, target -> target.setSurfaceSkin(skin));
        if (!previous.equals(portal.getSurfaceSkin())) {
            notifySurfaceSkin(player, portal);
        }
        return true;
    }

    private static void notifySurfaceSkin(ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED, MinecraftPortalText.arguments(
            "label", MinecraftPortalText.localized(viewer, WormholesMessages.PORTAL_NETWORK_LABEL_SURFACE_SKIN),
            "value", surfaceSkinDisplay(viewer, portal)));
    }

    private static boolean hasSurfaceSkin(MinecraftPortal portal) {
        return !portal.getSurfaceSkin().isEmpty();
    }

    private static String surfaceSkinDisplay(ServerPlayer viewer, MinecraftPortal portal) {
        if (!hasSurfaceSkin(portal)) {
            return MinecraftPortalText.localized(viewer, WormholesMessages.PORTAL_LABEL_SKIN_NONE);
        }
        return portal.getSurfaceSkin();
    }

    private static Item surfaceSkinIcon(MinecraftPortal portal) {
        if (!hasSurfaceSkin(portal)) {
            return Items.GLASS;
        }
        String skin = portal.getSurfaceSkin();
        if (PortalSurfaceSkins.isFluid(skin)) {
            return skin.contains("lava") ? Items.LAVA_BUCKET : Items.WATER_BUCKET;
        }
        Item material = skinMaterial(skin);
        return material == null || material == Items.AIR ? Items.GLASS : material;
    }

    private static Item skinMaterial(String skin) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, skin, false).blockState().getBlock().asItem();
        } catch (CommandSyntaxException ex) {
            return null;
        }
    }

    private enum AmbientChannel {
        RED("ambient-red", WormholesMessages.PORTAL_LABEL_AMBIENT_RED, 16),
        GREEN("ambient-green", WormholesMessages.PORTAL_LABEL_AMBIENT_GREEN, 8),
        BLUE("ambient-blue", WormholesMessages.PORTAL_LABEL_AMBIENT_BLUE, 0);

        private final String id;
        private final TextKey label;
        private final int shift;

        AmbientChannel(String id, TextKey label, int shift) {
            this.id = id;
            this.label = label;
            this.shift = shift;
        }

        String id() {
            return id;
        }

        TextKey label() {
            return label;
        }

        Item material() {
            return switch (this) {
                case RED -> Items.DYE.red();
                case GREEN -> Items.DYE.green();
                case BLUE -> Items.DYE.blue();
            };
        }

        int get(int color) {
            return (color >> shift) & 0xFF;
        }

        int set(int color, int value) {
            return (color & ~(0xFF << shift) & 0xFFFFFF) | (clampColorChannel(value) << shift);
        }
    }
}
