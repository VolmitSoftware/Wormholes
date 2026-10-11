package art.arcane.wormholes.network.client;

import java.util.Objects;

public record RootSource(int portalKey, int geometryRevision, String sourceWorld) {
    public RootSource {
        Objects.requireNonNull(sourceWorld, "sourceWorld");
        if (portalKey <= 0 || geometryRevision <= 0 || sourceWorld.length() > 256
            || !sourceWorld.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Portal root source");
        }
    }
}
