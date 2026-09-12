package art.arcane.wormholes.proxy.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Text for {@code /whproxy status|servers|portals|transfers}, shared by both proxy platforms. */
public final class ProxyConsole {
    public static final String PERMISSION = "wormholes.proxy.admin";
    public static final String USAGE = "Usage: /whproxy status|servers|portals|transfers";

    private ProxyConsole() {
    }

    public static List<String> render(String subcommand, ProxyRegistry registry, ProxyHandoffBroker broker, long nowMillis) {
        List<String> lines = new ArrayList<>();
        String command = subcommand == null ? "" : subcommand.trim().toLowerCase(Locale.ROOT);
        switch (command) {
            case "status" -> lines.add("WormholesProxy: " + registry.backends().size() + " backend(s) enrolled, " + broker.pending() + " transfer(s) pending");
            case "servers" -> {
                List<ProxyRegistry.Backend> backends = registry.backends();
                if (backends.isEmpty()) {
                    lines.add("No backends have enrolled yet.");
                }
                for (ProxyRegistry.Backend backend : backends) {
                    lines.add(backend.serverName() + " (proxy server " + backend.proxyServer() + "): seen " + ((nowMillis - backend.lastSeenMillis()) / 1_000L)
                        + "s ago, capabilities 0x" + Long.toHexString(backend.capabilities()));
                }
            }
            case "portals" -> {
                List<ProxyRegistry.Backend> backends = registry.backends();
                if (backends.isEmpty()) {
                    lines.add("No backends have enrolled yet.");
                }
                for (ProxyRegistry.Backend backend : backends) {
                    lines.add(backend.serverName() + ": " + (backend.portals().isEmpty() ? "no shareable portals" : String.join(", ", backend.portals())));
                }
            }
            case "transfers" -> {
                List<ProxyHandoffBroker.Reservation> reservations = broker.snapshot();
                if (reservations.isEmpty()) {
                    lines.add("No transfers in flight.");
                }
                for (ProxyHandoffBroker.Reservation reservation : reservations) {
                    lines.add(reservation.transferId() + ": " + reservation.playerId() + " " + reservation.sourceServer() + " -> " + reservation.targetServer()
                        + " (" + ((nowMillis - reservation.reservedAtMillis()) / 1_000L) + "s)");
                }
            }
            default -> lines.add(USAGE);
        }
        return lines;
    }
}
