package art.arcane.wormholes.modded.client;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ClientEntityIdsTest {
    @Test
    public void projectedAndReflectionRangesAreNegativeAndDisjoint() {
        assertTrue(ClientEntityIds.REFLECTION_MIN <= ClientEntityIds.REFLECTION_MAX);
        assertTrue(ClientEntityIds.REFLECTION_MAX < ClientEntityIds.PROJECTED_MIN);
        assertTrue(ClientEntityIds.PROJECTED_MIN <= ClientEntityIds.PROJECTED_MAX);
        assertTrue(ClientEntityIds.PROJECTED_MAX < 0);
        int[] edges = {ClientEntityIds.REFLECTION_MIN, ClientEntityIds.REFLECTION_MAX, ClientEntityIds.PROJECTED_MIN, ClientEntityIds.PROJECTED_MAX};
        for (int id : edges) {
            assertTrue("id " + id + " must belong to exactly one range", ClientEntityIds.isReflection(id) != ClientEntityIds.isProjected(id));
        }
        assertFalse(ClientEntityIds.isProjected(0));
        assertFalse(ClientEntityIds.isReflection(0));
    }

    @Test
    public void projectedIdsCountDownAndWrapInsideTheirRange() {
        assertEquals(ClientEntityIds.PROJECTED_MAX - 1, ClientEntityIds.nextProjected(ClientEntityIds.PROJECTED_MAX));
        assertEquals(ClientEntityIds.PROJECTED_MAX, ClientEntityIds.nextProjected(ClientEntityIds.PROJECTED_MIN));
        int id = ClientEntityIds.PROJECTED_MIN + 3;
        for (int step = 0; step < 8; step++) {
            id = ClientEntityIds.nextProjected(id);
            assertTrue("projected id " + id + " left its range", ClientEntityIds.isProjected(id));
        }
    }

    @Test
    public void reflectionIdsTakeTheFirstFreeSlotOfTheirRange() {
        IntOpenHashSet taken = new IntOpenHashSet();
        int first = ClientEntityIds.freeReflection(taken::contains);
        assertTrue(ClientEntityIds.isReflection(first));
        taken.add(first);
        int second = ClientEntityIds.freeReflection(taken::contains);
        assertTrue(ClientEntityIds.isReflection(second));
        assertNotEquals(first, second);
        taken.remove(first);
        assertEquals(first, ClientEntityIds.freeReflection(taken::contains));
        assertEquals(ClientEntityIds.NONE, ClientEntityIds.freeReflection(candidate -> true));
    }
}
