package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

/**
 * Negotiated peer capabilities. Each constant owns one bit of the 64-bit set exchanged in Hello and
 * Challenge (and in the status-bridge envelope). A peer link exposes the intersection of both sides;
 * every message type added after protocol 21 must be gated on a bit here so an older peer that lacks
 * the bit is never sent a frame it cannot decode (unknown types close the link).
 *
 * Bit ranges are reserved per lane; take the next free bit inside your range and never reuse a bit.
 *   0-7   protocol baseline (seam)
 *   8-15  mesh lane (federation, proxy, destination policy, load beacons)
 *   16-23 transit lane (convoy transfer)
 *   24-31 view lane (block entities, atmosphere, acoustics)
 *   32-39 nexus lane (network membership, dial state)
 *   40-47 ops lane
 *   48-63 unassigned
 */
public enum WireCapability {
    /** Baseline for every protocol-21 peer. Always set. */
    PROTOCOL_21(0),
    /** Signed PEER_ANNOUNCE and PEER_TOMBSTONE relay announcements (mesh lane). */
    MESH_ANNOUNCE(8),
    /** LOAD_BEACON frames carrying player load, TPS and drain state (mesh lane). */
    LOAD_BEACON(9),
    /** PORTAL_QUERY and PORTAL_QUERY_RESULT on-demand directory pulls (mesh lane). */
    PORTAL_QUERY(10),
    /** HANDOFF_QUEUE_STATUS position echoes for queued travelers (mesh lane). */
    HANDOFF_QUEUE(11),
    /** Handshake transcript written at the negotiated protocol version instead of the local layout (mesh lane). */
    VERSIONED_TRANSCRIPT(12),
    /** Transit lane: CONVOY_TRANSFER (38) and CONVOY_ACK (39) plus the optional HandoffRequest group id. */
    CONVOY(16),
    /** View slices carry the optional block-entity map (view lane). */
    VIEW_BLOCK_ENTITIES(24),
    /** The peer accepts VIEW_SOUND frames for subscribed portals (view lane). */
    VIEW_ACOUSTICS(25),
    /** The peer accepts VIEW_WEATHER frames beside VIEW_TIME (view lane). */
    VIEW_ATMOSPHERE(26);

    private final int bit;

    WireCapability(int bit) {
        if (bit < 0 || bit > 63) {
            throw new IllegalArgumentException("capability bit out of range: " + bit);
        }
        this.bit = bit;
    }

    public int bit() {
        return bit;
    }

    public long mask() {
        return 1L << bit;
    }

    public boolean in(long set) {
        return (set & mask()) != 0L;
    }

    /** Every bit this build knows. {@link #localSet(NetworkConfig)} is what this server actually offers. */
    public static long localSet() {
        long set = 0L;
        for (WireCapability capability : values()) {
            set |= capability.mask();
        }
        return set;
    }

    /**
     * The set this server advertises: the build's bits minus the lanes its configuration turns off, so
     * a peer's gate answers "is that feature on over there" rather than "does that build know the id".
     * A null config means the whole build, which is what tests and the pre-configuration paths want.
     */
    public static long localSet(NetworkConfig config) {
        long set = localSet();
        if (config == null || config.mesh == null || config.mesh.enabled) {
            return set;
        }
        return set & ~(MESH_ANNOUNCE.mask() | LOAD_BEACON.mask() | PORTAL_QUERY.mask() | HANDOFF_QUEUE.mask());
    }
}
