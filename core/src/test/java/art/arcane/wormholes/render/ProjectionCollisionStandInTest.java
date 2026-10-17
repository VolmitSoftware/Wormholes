package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class ProjectionCollisionStandInTest {
    private static final ProjectionCollisionStandIn<String> RULE = new ProjectionCollisionStandIn<String>(
        block -> block.endsWith("air"), block -> Set.of("stone", "bedrock", "oak_slab").contains(block), "barrier");

    @Test
    void projectedAirOverACollidableBlockIsSentAsABarrier() {
        assertEquals("barrier", RULE.outgoing("air", "stone"));
        assertEquals("barrier", RULE.outgoing("cave_air", "bedrock"));
        assertEquals("barrier", RULE.outgoing("void_air", "oak_slab"));
    }

    @Test
    void projectedAirOverAPassableBlockStaysAir() {
        assertEquals("air", RULE.outgoing("air", "air"));
        assertEquals("air", RULE.outgoing("air", "short_grass"));
        assertEquals("air", RULE.outgoing("air", "water"));
    }

    @Test
    void projectedBlocksOtherThanAirAreSentUnchanged() {
        assertEquals("grass_block", RULE.outgoing("grass_block", "bedrock"));
        assertEquals("water", RULE.outgoing("water", "stone"));
        assertEquals("stone", RULE.outgoing("stone", "air"));
    }

    @Test
    void anUnknownLocalBlockKeepsTheProjection() {
        assertEquals("air", RULE.outgoing("air", null));
    }

    @Test
    void onlyProjectedAirNeedsTheLocalBlock() {
        assertTrue(RULE.needsLocal("air"));
        assertFalse(RULE.needsLocal("stone"));
        assertFalse(RULE.needsLocal(null));
    }
}
