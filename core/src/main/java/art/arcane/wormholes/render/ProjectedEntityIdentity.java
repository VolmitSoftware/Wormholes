package art.arcane.wormholes.render;

import java.util.concurrent.atomic.AtomicInteger;

import art.arcane.optics.entity.PlayerNaming;

public final class ProjectedEntityIdentity {
    public static final PlayerNaming NAMING = new PlayerNaming("PortalPlayer", "wh");
    public static final int MIN_ENTITY_ID = -0x3FFFFFFF;
    public static final int MAX_ENTITY_ID = -0x20000000;
    private static final String TEAM_PREFIX = "whpn";
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(MAX_ENTITY_ID);
    private static final AtomicInteger NEXT_TEAM_ID = new AtomicInteger();

    private ProjectedEntityIdentity() {
    }

    public static int nextEntityId() {
        return NEXT_ENTITY_ID.getAndUpdate(ProjectedEntityIdentity::entityIdAfter);
    }

    public static boolean isEntityId(int id) {
        return id >= MIN_ENTITY_ID && id <= MAX_ENTITY_ID;
    }

    public static String nextTeamName() {
        return TEAM_PREFIX + Integer.toUnsignedString(NEXT_TEAM_ID.getAndIncrement(), 36);
    }

    static int entityIdAfter(int id) {
        return id > MIN_ENTITY_ID && id <= MAX_ENTITY_ID ? id - 1 : MAX_ENTITY_ID;
    }
}
