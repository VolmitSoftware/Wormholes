package art.arcane.wormholes.nexus;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * One portal inside a {@link PortalNetwork}. {@code serverName} is null for a portal on this server
 * and the peer name for a portal reached through a gateway link.
 */
public record NetworkMember(UUID portalId, String address, String label, long joinedAtMillis, String serverName) {
    public NetworkMember {
        Objects.requireNonNull(portalId, "portalId");
        address = normalizeAddress(address);
        label = label == null ? "" : label;
        serverName = serverName == null || serverName.isBlank() ? null : serverName.trim();
    }

    public boolean isLocal() {
        return serverName == null;
    }

    public NetworkMember withAddress(String newAddress) {
        return new NetworkMember(portalId, newAddress, label, joinedAtMillis, serverName);
    }

    public NetworkMember withLabel(String newLabel) {
        return new NetworkMember(portalId, address, newLabel, joinedAtMillis, serverName);
    }

    public static String normalizeAddress(String value) {
        Objects.requireNonNull(value, "address");
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
