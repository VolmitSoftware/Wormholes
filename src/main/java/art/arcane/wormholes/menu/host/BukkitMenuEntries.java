package art.arcane.wormholes.menu.host;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.portal.LocalPortal;

public final class BukkitMenuEntries {
    private BukkitMenuEntries() {
    }

    public static Function<UUID, MessageArgs> arguments(Function<LocalPortal, MessageArgs> arguments) {
        return portalId -> portal(portalId).map(arguments).orElse(MessageArgs.empty());
    }

    public static BiPredicate<MenuViewer, UUID> always() {
        return (viewer, portalId) -> true;
    }

    public static BiPredicate<MenuViewer, UUID> never() {
        return (viewer, portalId) -> false;
    }

    public static BiPredicate<MenuViewer, UUID> when(Predicate<LocalPortal> test) {
        return (viewer, portalId) -> portal(portalId).map(test::test).orElse(false);
    }

    private static Optional<LocalPortal> portal(UUID portalId) {
        PortalManager manager = Wormholes.portalManager;
        return manager == null ? Optional.empty() : BukkitLocalPortals.local(manager, portalId);
    }
}
