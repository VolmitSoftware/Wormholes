package art.arcane.wormholes.access;

import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AccessLinkCheckTest {
    private World world;

    @BeforeEach
    void makeWorld() {
        world = AccessTestPortals.world("linking");
    }

    @AfterEach
    void clearGuard() {
        AccessGuards.clear();
    }

    @Test
    void aClaimOnTheDestinationRefusesTheLink() {
        RecordingGuard guard = new RecordingGuard(false);
        AccessGuards.install(guard);
        LocalPortal source = AccessTestPortals.portal(world);
        LocalPortal destination = AccessTestPortals.portal(world);
        destination.setName("Far Side");
        UUID ownerId = UUID.randomUUID();
        source.setOwner(ownerId);

        assertFalse(source.setDestination(destination));
        assertSame(PlacementKind.LINK, guard.kind);
        assertEquals(ownerId, guard.actorId);
        assertEquals("Far Side", guard.subject);
    }

    @Test
    void anAllowedDestinationLinksAsBefore() {
        AccessGuards.install(new RecordingGuard(true));
        LocalPortal source = AccessTestPortals.portal(world);
        LocalPortal destination = AccessTestPortals.portal(world);

        assertTrue(source.setDestination(destination));
        assertNotNull(source.getTunnel());
    }

    @Test
    void withoutTheLaneInstalledLinkingIsUnchanged() {
        LocalPortal source = AccessTestPortals.portal(world);
        LocalPortal destination = AccessTestPortals.portal(world);

        assertTrue(source.setDestination(destination));
    }

    private static final class RecordingGuard implements AccessGuard {
        private final boolean allow;
        private UUID actorId;
        private PlacementKind kind;
        private String subject;
        private List<int[]> cells = new ArrayList<>();

        private RecordingGuard(boolean allow) {
            this.allow = allow;
        }

        @Override
        public boolean allowConstruct(UUID ownerId, Set<Block> cells, PortalType type) {
            return true;
        }

        @Override
        public boolean allowPlacement(UUID actorId, World world, List<int[]> requested, PlacementKind requestedKind, String requestedSubject) {
            this.actorId = actorId;
            this.kind = requestedKind;
            this.subject = requestedSubject;
            this.cells = List.copyOf(requested);
            return allow;
        }
    }
}
