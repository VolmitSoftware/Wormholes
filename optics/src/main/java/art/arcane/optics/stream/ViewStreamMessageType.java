package art.arcane.optics.stream;

public enum ViewStreamMessageType {
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
    MESH_BEGIN(16, Direction.S2C),
    MESH_SECTION(17, Direction.S2C),
    MESH_DROP(18, Direction.S2C),
    ENVIRONMENT(19, Direction.S2C),
    ENTITY_EVENT(20, Direction.S2C),
    HELLO(32, Direction.C2S),
    BRICK_MISS(33, Direction.C2S),
    ACK(34, Direction.C2S),
    VIEW_STATS(35, Direction.C2S),
    PLATE_REFUSED(36, Direction.C2S),
    MESH_ACK(37, Direction.C2S),
    MESH_LOCAL(38, Direction.C2S),
    MESH_CACHED(39, Direction.C2S),
    MESH_REUSE(40, Direction.S2C),
    TRAVEL_BEGIN(41, Direction.S2C),
    TRAVEL_CHUNK(42, Direction.S2C),
    TRAVEL_END(43, Direction.S2C),
    TRAVEL_READY(44, Direction.C2S),
    TRAVEL_COMMIT(45, Direction.S2C),
    TRAVEL_CANCEL(46, Direction.BOTH),
    TRAVEL_CROSS(47, Direction.C2S),
    TRAVEL_REUSE(48, Direction.S2C),
    TRAVEL_CACHED(49, Direction.C2S),
    ENTITY_SELF(50, Direction.S2C);

    private static final ViewStreamMessageType[] BY_ID = index();

    private final int id;
    private final Direction direction;

    ViewStreamMessageType(int id, Direction direction) {
        this.id = id;
        this.direction = direction;
    }

    public static ViewStreamMessageType byId(int id) {
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
        return direction != Direction.S2C;
    }

    public boolean isClientbound() {
        return direction != Direction.C2S;
    }

    private static ViewStreamMessageType[] index() {
        ViewStreamMessageType[] table = new ViewStreamMessageType[64];
        for (ViewStreamMessageType type : values()) {
            table[type.id] = type;
        }
        return table;
    }

    public enum Direction {
        S2C,
        C2S,
        BOTH
    }
}
