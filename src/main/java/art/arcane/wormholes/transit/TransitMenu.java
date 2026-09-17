package art.arcane.wormholes.transit;

import java.util.Locale;
import java.util.function.Consumer;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import art.arcane.volmlib.util.data.MaterialBlock;
import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.inventorygui.UIPaneDecorator;
import art.arcane.volmlib.util.inventorygui.UIWindow;
import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.volmlib.util.inventorygui.WindowResolution;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.TransitConfig;
import art.arcane.wormholes.localization.TransitMessages;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.network.PortalSyncService;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.service.WormholesAudience;

/**
 * Per-portal transit settings: momentum mode and factor, orientation, membrane, bounce, and the
 * transition profile (sounds, threshold particle, mask override). Every change saves the portal and
 * replicates through the settings bag when the portal syncs.
 */
public final class TransitMenu {
    static final double MIN_FACTOR = 0.0D;
    static final double MAX_FACTOR = 10.0D;
    static final int MAX_MASK_OVERRIDE_TICKS = 200;

    private final LocalPortal portal;

    public TransitMenu(LocalPortal portal) {
        this.portal = portal;
    }

    public void open(Player viewer) {
        TransitPortalExtension transit = portal.extension(TransitPortalExtension.class);
        if (transit == null || Wormholes.instance == null) {
            return;
        }
        UIWindow window = new UIWindow(Wormholes.instance, viewer);
        window.setTitle(portal.getRouter(true));
        window.setResolution(WindowResolution.W9_H6);
        window.setViewportHeight(3);
        window.setDecorator(new UIPaneDecorator(Material.CYAN_STAINED_GLASS_PANE));
        window.setElement(-2, 1, momentumElement(window, viewer, transit));
        window.setElement(-1, 1, orientationElement(window, viewer, transit));
        window.setElement(0, 1, membraneElement(window, viewer, transit));
        window.setElement(1, 1, bounceElement(window, viewer, transit));
        window.setElement(2, 1, profileElement(window, viewer, transit));
        window.setElement(0, 2, backElement(window, viewer));
        window.setVisible(true);
    }

    private UIElement momentumElement(Window window, Player viewer, TransitPortalExtension transit) {
        UIElement element = new UIElement("transit-momentum");
        element.setMaterial(new MaterialBlock(Material.SLIME_BALL));
        applyMomentum(element, transit);
        element.onLeftClick(event -> {
            MomentumPolicy current = transit.effectiveMomentum(TransitSubsystem.config());
            transit.setMomentum(current.withMode(current.mode().next()));
            changed(transit);
            applyMomentum(element, transit);
            window.updateInventory();
        });
        element.onRightClick(event -> prompt(window, viewer, text -> {
            Double factor = parseFactor(text);
            if (factor != null) {
                MomentumPolicy current = transit.effectiveMomentum(TransitSubsystem.config());
                transit.setMomentum(current.withFactor(factor.doubleValue()));
                changed(transit);
            }
        }));
        return element;
    }

    private void applyMomentum(UIElement element, TransitPortalExtension transit) {
        TransitConfig config = TransitSubsystem.config();
        MomentumPolicy policy = transit.effectiveMomentum(config);
        element.setEnchanted(transit.momentum() != null);
        Wormholes.text().apply(element, TransitMessages.MENU_MOMENTUM, args(
            MessageArgument.untrusted("mode", policy.mode().label()),
            MessageArgument.untrusted("value", formatFactor(policy.factor()))));
    }

    private UIElement orientationElement(Window window, Player viewer, TransitPortalExtension transit) {
        UIElement element = new UIElement("transit-orientation");
        element.setMaterial(new MaterialBlock(Material.COMPASS));
        applyOrientation(element, transit);
        element.onLeftClick(event -> {
            transit.setOrientation(transit.effectiveOrientation(TransitSubsystem.config()).next());
            changed(transit);
            applyOrientation(element, transit);
            window.updateInventory();
        });
        return element;
    }

    private void applyOrientation(UIElement element, TransitPortalExtension transit) {
        element.setEnchanted(transit.orientation() != null);
        Wormholes.text().apply(element, TransitMessages.MENU_ORIENTATION, args(
            MessageArgument.untrusted("mode", transit.effectiveOrientation(TransitSubsystem.config()).label())));
    }

    private UIElement membraneElement(Window window, Player viewer, TransitPortalExtension transit) {
        UIElement element = new UIElement("transit-membrane");
        element.setMaterial(new MaterialBlock(Material.PHANTOM_MEMBRANE));
        applyToggle(element, TransitMessages.MENU_MEMBRANE, transit.isMembrane());
        element.onLeftClick(event -> {
            transit.setMembrane(!transit.isMembrane());
            changed(transit);
            applyToggle(element, TransitMessages.MENU_MEMBRANE, transit.isMembrane());
            window.updateInventory();
        });
        return element;
    }

    private UIElement bounceElement(Window window, Player viewer, TransitPortalExtension transit) {
        UIElement element = new UIElement("transit-bounce");
        element.setMaterial(new MaterialBlock(Material.SLIME_BLOCK));
        applyToggle(element, TransitMessages.MENU_BOUNCE, transit.isBounce());
        element.onLeftClick(event -> {
            transit.setBounce(!transit.isBounce());
            changed(transit);
            applyToggle(element, TransitMessages.MENU_BOUNCE, transit.isBounce());
            window.updateInventory();
        });
        return element;
    }

    private void applyToggle(UIElement element, LinesKey key, boolean enabled) {
        element.setEnchanted(enabled);
        Wormholes.text().apply(element, key, args(MessageArgument.untrusted("state",
            Wormholes.text().plain(enabled ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF))));
    }

    private UIElement profileElement(Window window, Player viewer, TransitPortalExtension transit) {
        UIElement element = new UIElement("transit-profile");
        element.setMaterial(new MaterialBlock(Material.NOTE_BLOCK));
        applyProfile(element, transit);
        element.onLeftClick(event -> prompt(window, viewer, text -> {
            transit.setProfile(transit.profile().withThresholdEffect(soundOrEmpty(text)));
            changed(transit);
        }));
        element.onRightClick(event -> prompt(window, viewer, text -> {
            transit.setProfile(transit.profile().withArrivalSound(soundOrEmpty(text)));
            changed(transit);
        }));
        element.onShiftLeftClick(event -> prompt(window, viewer, text -> {
            Integer ticks = parseMaskTicks(text);
            if (ticks != null) {
                transit.setProfile(transit.profile().withMaskOverrideTicks(ticks.intValue()));
                changed(transit);
            }
        }));
        return element;
    }

    private void applyProfile(UIElement element, TransitPortalExtension transit) {
        TransitionProfile profile = transit.profile();
        element.setEnchanted(!profile.isNone());
        Wormholes.text().apply(element, TransitMessages.MENU_PROFILE, args(
            MessageArgument.untrusted("mode", labelOrDefault(profile.thresholdEffect())),
            MessageArgument.untrusted("state", labelOrDefault(profile.arrivalSound())
                + (profile.overridesMask() ? " / " + profile.maskOverrideTicks() + "t" : ""))));
    }

    private UIElement backElement(Window window, Player viewer) {
        UIElement element = new UIElement("transit-back");
        element.setMaterial(new MaterialBlock(Material.ARROW));
        Wormholes.text().apply(element, WormholesMessages.PORTAL_MENU_BACK_SETTINGS);
        element.onLeftClick(event -> {
            window.close();
            portal.uiOpenPortalMenu(viewer);
        });
        return element;
    }

    /** Closes the menu, asks in chat, applies the answer on the player's thread, and reopens the menu. */
    private void prompt(Window window, Player viewer, Consumer<String> apply) {
        window.close();
        String cancelWord = Wormholes.text().plain(WormholesMessages.PORTAL_INPUT_CANCEL);
        WormholesAudience.sendMessage(viewer, Wormholes.text().component(viewer, TransitMessages.MENU_PROMPT,
            args(MessageArgument.untrusted("cancel", cancelWord))));
        Wormholes.awaitChatInput(viewer, text -> {
            String answer = text == null ? "" : text.trim();
            if (!answer.equalsIgnoreCase(cancelWord)) {
                apply.accept(answer);
            }
            open(viewer);
        });
    }

    private void changed(TransitPortalExtension transit) {
        portal.save();
        portal.refreshOpenMenus();
        if (portal.isSettingsSyncEnabled() && Wormholes.portalSyncService != null && !PortalSyncService.isApplyingRemote()) {
            Wormholes.portalSyncService.broadcastSettings(portal);
        }
    }

    /** A factor between 0 and 10, or null for anything else. */
    static Double parseFactor(String text) {
        try {
            double factor = Double.parseDouble(text.trim());
            if (!Double.isFinite(factor) || factor < MIN_FACTOR || factor > MAX_FACTOR) {
                return null;
            }
            return Double.valueOf(factor);
        } catch (NumberFormatException | NullPointerException malformed) {
            return null;
        }
    }

    /** A mask override in ticks: -1 or an empty answer clears it, 0..200 sets it, anything else is ignored. */
    static Integer parseMaskTicks(String text) {
        String answer = text == null ? "" : text.trim();
        if (answer.isEmpty()) {
            return Integer.valueOf(-1);
        }
        try {
            int ticks = Integer.parseInt(answer);
            if (ticks < -1 || ticks > MAX_MASK_OVERRIDE_TICKS) {
                return null;
            }
            return Integer.valueOf(ticks);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    /** Sound and particle keys are lower-cased namespaced ids; "-" or an empty answer clears the override. */
    static String soundOrEmpty(String text) {
        String answer = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (answer.isEmpty() || answer.equals("-") || answer.equals("none") || answer.equals("default")) {
            return "";
        }
        return answer;
    }

    private static String labelOrDefault(String value) {
        return value == null || value.isBlank() ? "default" : value;
    }

    private static String formatFactor(double factor) {
        return factor == Math.rint(factor) ? Integer.toString((int) factor) : String.format(Locale.ROOT, "%.2f", factor);
    }

    private static MessageArgs args(MessageArgument... arguments) {
        return WormholesLocalization.args(arguments);
    }
}
