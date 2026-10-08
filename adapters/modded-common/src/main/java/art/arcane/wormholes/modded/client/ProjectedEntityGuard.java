package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.render.ProjectedEntityIdentity;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

public final class ProjectedEntityGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final AtomicBoolean REFUSAL_LOGGED = new AtomicBoolean();

    private ProjectedEntityGuard() {
    }

    public static boolean projected(Entity entity) {
        return ProjectedEntityIdentity.isEntityId(entity.getId());
    }

    public static boolean visualCopy(Entity entity) {
        int id = entity.getId();
        return ProjectedEntityIdentity.isEntityId(id) || ClientEntityIds.isProjected(id) || ClientEntityIds.isReflection(id);
    }

    public static List<Entity> pushTargets(Entity source, List<Entity> targets) {
        return visualCopy(source) ? List.of() : targets;
    }

    public static Predicate<Entity> excluding(Predicate<Entity> predicate) {
        return entity -> predicate.test(entity) && !projected(entity);
    }

    public static boolean refusesSpawn(int spawnedId, int playerId) {
        return spawnedId == playerId;
    }

    public static void refused(int spawnedId, String type) {
        if (REFUSAL_LOGGED.compareAndSet(false, true)) {
            LOGGER.warn("Refused a {} spawn that reused the local player's entity id {}", type, spawnedId);
        }
    }
}
