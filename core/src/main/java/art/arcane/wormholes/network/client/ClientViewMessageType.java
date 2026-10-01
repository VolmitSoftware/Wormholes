package art.arcane.wormholes.network.client;

public enum ClientViewMessageType {
    OFFER(1, Direction.S2C),
    ACCEPT(2, Direction.S2C),
    DECLINE(3, Direction.S2C),
    PALETTE(4, Direction.S2C),
    PORTAL(5, Direction.S2C),
    PORTAL_DROP(6, Direction.S2C),
    PLATE_BEGIN(7, Direction.S2C),
    PLATE_BRICKS(8, Direction.S2C),
    PLATE_END(9, Direction.S2C),
    PLATE_PATCH(10, Direction.S2C),
    PLATE_HANDLE(11, Direction.S2C),
    ENTITY_FRAME(12, Direction.S2C),
    FX(13, Direction.S2C),
    ATMOSPHERE(14, Direction.S2C),
    SESSION_RESET(15, Direction.S2C),
    HELLO(32, Direction.C2S),
    BRICK_MISS(33, Direction.C2S),
    ACK(34, Direction.C2S),
    VIEW_STATS(35, Direction.C2S),
    PLATE_REFUSED(36, Direction.C2S);

    private static final ClientViewMessageType[] BY_ID = index();

    private final int id;
    private final Direction direction;

    ClientViewMessageType(int id, Direction direction) {
        this.id = id;
        this.direction = direction;
    }

    public static ClientViewMessageType byId(int id) {
        if (id < 0 || id >= BY_ID.length) {
            return null;
        }
        return BY_ID[id];
    }

    public int id() {
        return id;
    }

    public Direction direction() {
        return direction;
    }

    public boolean isServerbound() {
        return direction == Direction.C2S;
    }

    public boolean isClientbound() {
        return direction == Direction.S2C;
    }

    private static ClientViewMessageType[] index() {
        ClientViewMessageType[] table = new ClientViewMessageType[64];
        for (ClientViewMessageType type : values()) {
            table[type.id] = type;
        }
        return table;
    }

    public enum Direction {
        S2C,
        C2S
    }
}
