package art.arcane.wormholes.network;

/**
 * The capability that guards each message type. SPEC 6.5 requires every mesh id to be gated; keeping
 * the table here rather than at the send sites means a new type cannot ship ungated by accident, and
 * {@link NetworkManager#send} refuses a guarded frame for a peer whose negotiated set lacks the bit
 * instead of handing it an id it would close the link over.
 */
public final class WireGuard {
    private WireGuard() {
    }

    /** The capability a peer needs before this type may be sent to it, or null when every peer accepts it. */
    public static WireCapability of(WireMessageType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case PEER_ANNOUNCE, PEER_TOMBSTONE -> WireCapability.MESH_ANNOUNCE;
            case LOAD_BEACON -> WireCapability.LOAD_BEACON;
            case PORTAL_QUERY, PORTAL_QUERY_RESULT -> WireCapability.PORTAL_QUERY;
            case HANDOFF_QUEUE_STATUS -> WireCapability.HANDOFF_QUEUE;
            case VIEW_SOUND -> WireCapability.VIEW_ACOUSTICS;
            case VIEW_WEATHER -> WireCapability.VIEW_ATMOSPHERE;
            case CONVOY_TRANSFER, CONVOY_ACK -> WireCapability.CONVOY;
            default -> null;
        };
    }
}
