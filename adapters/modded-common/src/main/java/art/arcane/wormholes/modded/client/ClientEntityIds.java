package art.arcane.wormholes.modded.client;

import java.util.function.IntPredicate;

import art.arcane.wormholes.render.ProjectedEntityIdentity;

public final class ClientEntityIds {
    public static final int NONE = 0;
    public static final int REFLECTION_SLOTS = 0x4000;
    public static final int REFLECTION_MIN = Integer.MIN_VALUE;
    public static final int REFLECTION_MAX = REFLECTION_MIN + REFLECTION_SLOTS - 1;
    public static final int PROJECTED_MIN = REFLECTION_MAX + 1;
    public static final int PROJECTED_MAX = ProjectedEntityIdentity.MIN_ENTITY_ID - 1;

    private ClientEntityIds() {
    }

    public static int nextProjected(int id) {
        return id <= PROJECTED_MIN ? PROJECTED_MAX : id - 1;
    }

    public static int freeReflection(IntPredicate taken) {
        for (int id = REFLECTION_MIN; id <= REFLECTION_MAX; id++) {
            if (!taken.test(id)) {
                return id;
            }
        }
        return NONE;
    }

    public static boolean isProjected(int id) {
        return id >= PROJECTED_MIN && id <= PROJECTED_MAX;
    }

    public static boolean isReflection(int id) {
        return id >= REFLECTION_MIN && id <= REFLECTION_MAX;
    }
}
