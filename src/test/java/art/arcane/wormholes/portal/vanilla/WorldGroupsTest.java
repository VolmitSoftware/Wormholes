package art.arcane.wormholes.portal.vanilla;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGroupsTest {
    private static final List<String> GROUPS = List.of("world,world_nether,world_the_end", "arena,arena");

    @Test
    void everyWorldPairsWithEveryOtherWhenNoGroupsAreConfigured() {
        assertTrue(WorldGroups.canPair("world", "anything", List.of()));
        assertEquals(Optional.empty(), WorldGroups.groupOf("world", List.of()));
        assertFalse(WorldGroups.isDisabled("world", List.of()));
    }

    @Test
    void worldsPairOnlyInsideTheirGroup() {
        assertTrue(WorldGroups.canPair("world", "world_nether", GROUPS));
        assertFalse(WorldGroups.canPair("world", "arena", GROUPS));
        assertEquals(Optional.of("world,world_nether,world_the_end"), WorldGroups.groupOf("world_the_end", GROUPS));
    }

    @Test
    void aWorldPairedToItselfHasItsPortalsDisabled() {
        assertTrue(WorldGroups.isDisabled("arena", GROUPS));
        assertFalse(WorldGroups.isDisabled("world", GROUPS));
        assertFalse(WorldGroups.canPair("arena", "arena", GROUPS));
        assertFalse(WorldGroups.canPair("arena", "world", GROUPS));
    }

    @Test
    void aWorldOutsideEveryGroupPairsWithNothingGrouped() {
        assertFalse(WorldGroups.canPair("wild", "world", GROUPS));
        assertTrue(WorldGroups.canPair("wild", "outback", GROUPS));
        assertEquals(Optional.empty(), WorldGroups.groupOf("wild", GROUPS));
    }
}
