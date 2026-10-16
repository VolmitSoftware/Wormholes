package art.arcane.wormholes.menu;

import java.util.UUID;

@FunctionalInterface
public interface MenuTransport {
    boolean send(UUID viewerId, byte[] frame);
}
