package art.arcane.wormholes.network;

/**
 * Wire message ids. BY_ID is a 64-slot table, so ids run 0-63. Reserved blocks for headline lanes
 * (take the next free id inside your block; gate the type on a {@link WireCapability} bit):
 *   13-19  mesh lane (peer announce, introductions, load beacons, policy)
 *   22-23  view lane (block-entity and atmosphere channels)
 *   27-29  view lane (acoustics, particle relay)
 *   38-39  transit lane (convoy transfer)
 *   46-49  transit lane
 *   50-55  nexus lane (network membership, dial state)
 *   56-59  ops lane
 *   61-63  unassigned
 */
public enum WireMessageType {
    HELLO(0),
    CHALLENGE(1),
    AUTH(2),
    READY(3),
    PING(4),
    PONG(5),
    ROUTED(6),
    DICT_OFFER(7),
    DICT_REQUEST(8),
    DICT_DATA(9),
    PORTAL_DIRECTORY(10),
    PORTAL_UPSERT(11),
    PORTAL_REMOVE(12),
    PEER_ANNOUNCE(13),
    PEER_TOMBSTONE(14),
    LOAD_BEACON(15),
    PORTAL_QUERY(16),
    PORTAL_QUERY_RESULT(17),
    HANDOFF_QUEUE_STATUS(18),
    VIEW_SUBSCRIBE(20),
    VIEW_UNSUBSCRIBE(21),
    VIEW_ENTITIES(24),
    VIEW_ENTITY_ANIMATION(25),
    VIEW_TIME(26),
    VIEW_SOUND(27),
    VIEW_WEATHER(28),
    HANDOFF_REQUEST(30),
    HANDOFF_ACK(31),
    HANDOFF_DENY(32),
    HANDOFF_CANCEL(33),
    ENTITY_TRANSFER(34),
    ENTITY_TRANSFER_ACK(35),
    HANDOFF_RESULT(36),
    HANDOFF_STATUS(37),
    CONVOY_TRANSFER(38),
    CONVOY_ACK(39),
    CHUNK_BULK(40),
    CHUNK_DIFF(41),
    CHUNK_HASH_PROBE(42),
    CHUNK_RESYNC_REQUEST(43),
    VIEW_BULK_COMPLETE(44),
    PORTAL_SETTINGS_UPDATE(45),
    SIDEBAND_FRAGMENT(60);

    private static final WireMessageType[] BY_ID = new WireMessageType[64];

    static {
        for (WireMessageType type : values()) {
            BY_ID[type.id] = type;
        }
    }

    private final int id;

    WireMessageType(int id) {
        this.id = id;
    }

    public byte id() {
        return (byte) id;
    }

    public static WireMessageType byId(byte id) {
        int index = id & 0xFF;
        if (index >= BY_ID.length) {
            return null;
        }
        return BY_ID[index];
    }
}
