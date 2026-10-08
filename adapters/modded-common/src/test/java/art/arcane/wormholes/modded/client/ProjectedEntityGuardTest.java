package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.render.ProjectedEntityIdentity;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProjectedEntityGuardTest {
    @Test
    public void spawnReusingTheLocalPlayerIdIsRefused() {
        assertTrue(ProjectedEntityGuard.refusesSpawn(417, 417));
        assertTrue(ProjectedEntityGuard.refusesSpawn(ProjectedEntityIdentity.MAX_ENTITY_ID, ProjectedEntityIdentity.MAX_ENTITY_ID));
    }

    @Test
    public void spawnWithAnotherIdIsKept() {
        assertFalse(ProjectedEntityGuard.refusesSpawn(418, 417));
        assertFalse(ProjectedEntityGuard.refusesSpawn(ProjectedEntityIdentity.MIN_ENTITY_ID, 417));
    }
}
