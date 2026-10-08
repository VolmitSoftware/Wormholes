package art.arcane.wormholes.render;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProjectedEntityIdentityTest {
    @Test
    void reservedRangeIsNegativeAndExcludesVanillaAndSentinelIds() {
        assertEquals(-0x3FFFFFFF, ProjectedEntityIdentity.MIN_ENTITY_ID);
        assertEquals(-0x20000000, ProjectedEntityIdentity.MAX_ENTITY_ID);
        assertTrue(ProjectedEntityIdentity.isEntityId(ProjectedEntityIdentity.MIN_ENTITY_ID));
        assertTrue(ProjectedEntityIdentity.isEntityId(ProjectedEntityIdentity.MAX_ENTITY_ID));
        assertFalse(ProjectedEntityIdentity.isEntityId(ProjectedEntityIdentity.MIN_ENTITY_ID - 1));
        assertFalse(ProjectedEntityIdentity.isEntityId(ProjectedEntityIdentity.MAX_ENTITY_ID + 1));
        int[] outside = {Integer.MIN_VALUE, -1, 0, 1, 1_900_000_000, Integer.MAX_VALUE};
        for (int id : outside) {
            assertFalse(ProjectedEntityIdentity.isEntityId(id), "id " + id + " must stay outside the reserved range");
        }
    }

    @Test
    void allocatedIdsStayInsideTheReservedRangeAndNeverRepeat() {
        int count = 4096;
        Set<Integer> seen = new HashSet<Integer>(count * 2);
        for (int index = 0; index < count; index++) {
            int id = ProjectedEntityIdentity.nextEntityId();
            assertTrue(ProjectedEntityIdentity.isEntityId(id), "allocated id " + id + " left the reserved range");
            assertTrue(seen.add(Integer.valueOf(id)), "allocated id " + id + " was handed out twice");
        }
    }

    @Test
    void idsCountDownAndWrapToTheTopOfTheRange() {
        assertEquals(ProjectedEntityIdentity.MAX_ENTITY_ID - 1, ProjectedEntityIdentity.entityIdAfter(ProjectedEntityIdentity.MAX_ENTITY_ID));
        assertEquals(ProjectedEntityIdentity.MIN_ENTITY_ID, ProjectedEntityIdentity.entityIdAfter(ProjectedEntityIdentity.MIN_ENTITY_ID + 1));
        assertEquals(ProjectedEntityIdentity.MAX_ENTITY_ID, ProjectedEntityIdentity.entityIdAfter(ProjectedEntityIdentity.MIN_ENTITY_ID));
        assertEquals(ProjectedEntityIdentity.MAX_ENTITY_ID, ProjectedEntityIdentity.entityIdAfter(0));
        assertEquals(ProjectedEntityIdentity.MAX_ENTITY_ID, ProjectedEntityIdentity.entityIdAfter(Integer.MIN_VALUE));
        assertEquals(ProjectedEntityIdentity.MAX_ENTITY_ID, ProjectedEntityIdentity.entityIdAfter(Integer.MAX_VALUE));
    }

    @Test
    void wrappingSequenceKeepsIdsUniqueAcrossTheBoundary() {
        int steps = 64;
        Set<Integer> seen = new HashSet<Integer>(steps * 2);
        int id = ProjectedEntityIdentity.MIN_ENTITY_ID + steps / 2;
        for (int step = 0; step < steps; step++) {
            assertTrue(ProjectedEntityIdentity.isEntityId(id), "id " + id + " left the reserved range");
            assertTrue(seen.add(Integer.valueOf(id)), "id " + id + " repeated before the range was exhausted");
            id = ProjectedEntityIdentity.entityIdAfter(id);
        }
        assertTrue(seen.contains(Integer.valueOf(ProjectedEntityIdentity.MIN_ENTITY_ID)));
        assertTrue(seen.contains(Integer.valueOf(ProjectedEntityIdentity.MAX_ENTITY_ID)));
    }
}
