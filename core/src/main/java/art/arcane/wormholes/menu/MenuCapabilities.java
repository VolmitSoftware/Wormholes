package art.arcane.wormholes.menu;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MenuCapabilities {
    private final Map<UUID, MenuCapability> capabilities = new ConcurrentHashMap<>();

    public Optional<MenuCapability> accept(UUID viewerId, int clientVersion) {
        Optional<MenuCapability> accepted = MenuNegotiation.accept(clientVersion);
        accepted.ifPresent(capability -> capabilities.put(Objects.requireNonNull(viewerId, "viewerId"), capability));
        return accepted;
    }

    public Optional<MenuCapability> capability(UUID viewerId) {
        return Optional.ofNullable(capabilities.get(viewerId));
    }

    public void clear(UUID viewerId) {
        capabilities.remove(viewerId);
    }
}
