package art.arcane.wormholes.render;

import java.util.concurrent.atomic.AtomicInteger;

import art.arcane.optics.entity.PlayerNaming;

public final class ProjectedEntityIdentity {
    public static final PlayerNaming NAMING = new PlayerNaming("PortalPlayer", "wh");
    private static final String TEAM_PREFIX = "whpn";
    private static final int FIRST_ENTITY_ID = 1_900_000_000;
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(FIRST_ENTITY_ID);
    private static final AtomicInteger NEXT_TEAM_ID = new AtomicInteger();

    private ProjectedEntityIdentity() {
    }

    public static int nextEntityId() {
        return NEXT_ENTITY_ID.getAndIncrement();
    }

    public static String nextTeamName() {
        return TEAM_PREFIX + Integer.toUnsignedString(NEXT_TEAM_ID.getAndIncrement(), 36);
    }
}
