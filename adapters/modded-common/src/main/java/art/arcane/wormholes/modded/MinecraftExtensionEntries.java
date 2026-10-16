package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.localization.AccessMessages;
import art.arcane.wormholes.localization.FidelityMessages;
import art.arcane.wormholes.localization.NexusMessages;
import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.localization.TransitMessages;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.access.AccessRoutes;
import art.arcane.wormholes.menu.fidelity.FidelityRoutes;
import art.arcane.wormholes.menu.nexus.NexusRoutes;
import art.arcane.wormholes.menu.portal.MenuExtensionEntry;
import art.arcane.wormholes.menu.rules.RulesRoutes;
import art.arcane.wormholes.menu.transit.TransitRoutes;
import art.arcane.wormholes.nexus.PortalNetwork;
import art.arcane.wormholes.transit.ScaleRuleSettings;

import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

final class MinecraftExtensionEntries {
    private MinecraftExtensionEntries() {
    }

    static List<MenuExtensionEntry> create(WormholesModRuntime runtime) {
        BiPredicate<MenuViewer, UUID> always = (viewer, portalId) -> true;
        return List.of(
            new MenuExtensionEntry("access", "minecraft:name_tag", "access", AccessMessages.MENU_ENTRY, 0, portalId -> MessageArgs.empty(), always,
                glint(runtime, portal -> !portal.getRoles().isEmpty()), AccessRoutes::roles),
            new MenuExtensionEntry("rules", "minecraft:book", "settings", RulesMessages.MENU_ENTRY, 0, portalId -> MessageArgs.empty(), always,
                glint(runtime, portal -> !runtime.rules().document(portal).isInert()), RulesRoutes::profile),
            new MenuExtensionEntry("nexus-network", "minecraft:compass", "network", NexusMessages.MENU_ENTRY, 1,
                portalId -> networkArguments(runtime, portalId), always,
                glint(runtime, portal -> MinecraftNexusValues.networkId(portal) != null && !MinecraftNexusValues.address(portal).isEmpty()),
                NexusRoutes::network),
            new MenuExtensionEntry("transit", "minecraft:feather", "transit", TransitMessages.MENU_ENTRY, 1, portalId -> MessageArgs.empty(), always,
                glint(runtime, MinecraftExtensionEntries::customTransit), TransitRoutes::main),
            new MenuExtensionEntry("fidelity", "minecraft:spyglass", "projection", FidelityMessages.MENU_ENTRY, 1, portalId -> MessageArgs.empty(),
                always, (viewer, portalId) -> false, FidelityRoutes::main));
    }

    private static BiPredicate<MenuViewer, UUID> glint(WormholesModRuntime runtime, Predicate<MinecraftPortal> test) {
        return (viewer, portalId) -> {
            MinecraftPortal portal = runtime.portals().get(portalId);
            return portal != null && test.test(portal);
        };
    }

    private static MessageArgs networkArguments(WormholesModRuntime runtime, UUID portalId) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        if (portal == null) {
            return MessageArgs.empty();
        }
        UUID networkId = MinecraftNexusValues.networkId(portal);
        PortalNetwork network = networkId == null ? null : runtime.nexus().networks().byId(networkId);
        return MinecraftPortalText.arguments(
            "name", network == null ? "" : network.name(),
            "address", MinecraftNexusValues.address(portal));
    }

    private static boolean customTransit(MinecraftPortal portal) {
        return portal.getMomentum() != null || portal.getOrientation() != null || portal.isMembrane() || portal.isBounce()
            || !portal.getTransitionProfile().isNone() || !ScaleRuleSettings.isDefault(portal.getScaleRule());
    }
}
