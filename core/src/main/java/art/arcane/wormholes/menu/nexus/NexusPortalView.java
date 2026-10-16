package art.arcane.wormholes.menu.nexus;

import java.util.Objects;
import java.util.UUID;

import art.arcane.wormholes.nexus.DestinationPolicy;
import art.arcane.wormholes.nexus.DialState;
import art.arcane.wormholes.nexus.FrameIo;
import art.arcane.wormholes.portal.PortalType;

public record NexusPortalView(
    UUID id,
    String name,
    PortalType type,
    boolean mirror,
    String routerTitle,
    UUID networkId,
    String address,
    boolean reciprocal,
    DestinationPolicy policy,
    FrameIo frameIo,
    DialState dial
) {
    public NexusPortalView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(routerTitle, "routerTitle");
        address = address == null ? "" : address;
        policy = policy == null ? DestinationPolicy.empty() : policy;
        frameIo = frameIo == null ? FrameIo.none() : frameIo;
        dial = dial == null ? DialState.idle() : dial;
    }

    public boolean joined() {
        return networkId != null;
    }
}
