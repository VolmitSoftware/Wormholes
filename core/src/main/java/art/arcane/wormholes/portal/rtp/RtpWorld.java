package art.arcane.wormholes.portal.rtp;

import java.util.Objects;
import java.util.UUID;

public record RtpWorld(UUID id, String key, int minimumHeight, int maximumHeight, int seaLevel) {
    public RtpWorld {
        Objects.requireNonNull(key, "key");
    }
}
