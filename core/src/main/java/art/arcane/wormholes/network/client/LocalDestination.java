package art.arcane.wormholes.network.client;

import java.util.Objects;

public record LocalDestination(int portalKey, int geometryRevision, String destinationWorld) {
    public LocalDestination {
        Objects.requireNonNull(destinationWorld, "destinationWorld");
        if (portalKey <= 0 || geometryRevision <= 0 || destinationWorld.length() > 256
            || !destinationWorld.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Portal local destination");
        }
    }
}
