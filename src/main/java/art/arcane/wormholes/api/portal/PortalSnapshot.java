package art.arcane.wormholes.api.portal;

import java.util.Optional;
import java.util.UUID;

/**
 * An immutable view of one portal, refreshed once a second. Reading a snapshot never loads a chunk
 * and never touches the owning region thread.
 *
 * @param id           the portal's stable id
 * @param name         the display name the owner set
 * @param type         PORTAL, WORMHOLE, GATEWAY, or RTP
 * @param worldKey     the destination-independent world key, for example {@code minecraft:overworld}
 * @param centerX      aperture centre
 * @param centerY      aperture centre
 * @param centerZ      aperture centre
 * @param frameNormal  the direction the aperture faces: U, D, N, S, E, or W
 * @param open         whether the portal is currently open
 * @param destinationId the linked portal's id, or null when unlinked
 * @param destinationServer the peer server name for a cross-server link, or null when local
 * @param owner        the owning player
 * @param listed       false when the owner hid the portal from public listings
 */
public record PortalSnapshot(UUID id, String name, String type, String worldKey, double centerX, double centerY,
                             double centerZ, String frameNormal, boolean open, UUID destinationId,
                             String destinationServer, UUID owner, boolean listed) {
    public Optional<UUID> destination() {
        return Optional.ofNullable(destinationId);
    }

    public Optional<String> server() {
        return Optional.ofNullable(destinationServer);
    }

    public boolean linked() {
        return destinationId != null;
    }
}
