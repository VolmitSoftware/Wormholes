package art.arcane.wormholes.api;

import art.arcane.wormholes.api.portal.PortalSnapshot;
import art.arcane.wormholes.ops.SnapshotPortalQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalQueryTest {
    private static final UUID OWNER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000000");
    private static final UUID OTHER_OWNER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000000");

    @Test
    void lookupsFilterTheCurrentSnapshotWithoutCopyingItAway() {
        PortalSnapshot hub = snapshot("Hub", "minecraft:overworld", OWNER, UUID.randomUUID());
        PortalSnapshot nether = snapshot("Nether", "minecraft:the_nether", OWNER, null);
        PortalSnapshot arena = snapshot("Arena", "minecraft:overworld", OTHER_OWNER, null);
        SnapshotPortalQuery query = new SnapshotPortalQuery();
        query.publish(List.of(hub, nether, arena));

        assertEquals(3, query.all().size());
        assertEquals(hub, query.byId(hub.id()).orElseThrow());
        assertTrue(query.byId(UUID.randomUUID()).isEmpty());
        assertEquals(2, query.byWorld("minecraft:overworld").size());
        assertEquals(2, query.byOwner(OWNER).size());
        assertEquals(List.of(arena), query.byName("arena"));
        assertTrue(query.byName("missing").isEmpty());
    }

    @Test
    void anEmptySnapshotAnswersEmptyRatherThanThrowing() {
        SnapshotPortalQuery query = new SnapshotPortalQuery();

        assertTrue(query.all().isEmpty());
        assertTrue(query.byId(UUID.randomUUID()).isEmpty());
        assertTrue(query.byWorld("minecraft:overworld").isEmpty());
        assertTrue(query.byOwner(OWNER).isEmpty());
        assertTrue(query.byName(null).isEmpty());
    }

    @Test
    void aSnapshotReportsItsLinkAndServerThroughTheHelpers() {
        UUID destination = UUID.randomUUID();
        PortalSnapshot linked = snapshot("Hub", "minecraft:overworld", OWNER, destination);
        PortalSnapshot unlinked = snapshot("Solo", "minecraft:overworld", OWNER, null);

        assertTrue(linked.linked());
        assertEquals(destination, linked.destination().orElseThrow());
        assertFalse(unlinked.linked());
        assertTrue(unlinked.destination().isEmpty());
        assertTrue(unlinked.server().isEmpty());
    }

    private static PortalSnapshot snapshot(String name, String worldKey, UUID owner, UUID destination) {
        return new PortalSnapshot(UUID.randomUUID(), name, "PORTAL", worldKey, 1.0D, 2.0D, 3.0D, "N",
            true, destination, null, owner, true);
    }
}
