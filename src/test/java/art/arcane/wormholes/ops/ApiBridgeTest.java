package art.arcane.wormholes.ops;

import art.arcane.wormholes.api.destination.DestinationResolver;
import art.arcane.wormholes.api.network.PeerSnapshot;
import art.arcane.wormholes.api.portal.PortalSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiBridgeTest {
    private static final UUID PORTAL = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TRAVELER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CHOSEN = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void theFirstResolverWithAnAnswerWinsAndFailuresAreSkipped() {
        DestinationResolver silent = (portal, traveler) -> Optional.empty();
        DestinationResolver broken = (portal, traveler) -> {
            throw new IllegalStateException("resolver blew up");
        };
        DestinationResolver answering = (portal, traveler) -> Optional.of(CHOSEN);
        DestinationResolver later = (portal, traveler) -> Optional.of(UUID.randomUUID());

        Optional<UUID> chosen = ApiDestinationResolvers.choose(List.of(silent, broken, answering, later),
            snapshot(), TRAVELER);

        assertEquals(CHOSEN, chosen.orElseThrow());
        assertTrue(ApiDestinationResolvers.choose(List.of(silent), snapshot(), TRAVELER).isEmpty());
        assertTrue(ApiDestinationResolvers.choose(List.of(), snapshot(), TRAVELER).isEmpty());
    }

    @Test
    void aPortalDiffReportsCreationsDestructionsAndChangedLinks() {
        PortalSnapshot before = snapshot();
        PortalSnapshot relinked = new PortalSnapshot(PORTAL, "Hub", "PORTAL", "minecraft:overworld",
            1.0D, 2.0D, 3.0D, "N", true, CHOSEN, null, TRAVELER, true);
        PortalSnapshot fresh = new PortalSnapshot(UUID.randomUUID(), "New", "PORTAL", "minecraft:overworld",
            0.0D, 0.0D, 0.0D, "S", true, null, null, TRAVELER, true);

        ApiSnapshotDiff.Change first = ApiSnapshotDiff.portals(Map.of(), List.of(before));
        assertEquals(1, first.created().size());
        assertTrue(first.linked().isEmpty());
        assertTrue(first.destroyed().isEmpty());

        Map<UUID, PortalSnapshot> previous = Map.of(before.id(), before);
        ApiSnapshotDiff.Change second = ApiSnapshotDiff.portals(previous, List.of(relinked, fresh));
        assertEquals(List.of(fresh), second.created());
        assertEquals(List.of(relinked), second.linked());
        assertTrue(second.destroyed().isEmpty());

        ApiSnapshotDiff.Change third = ApiSnapshotDiff.portals(previous, List.of());
        assertEquals(List.of(before), third.destroyed());
    }

    @Test
    void aPeerDiffReportsConnectsAndDisconnectsOnce() {
        PeerSnapshot beta = new PeerSnapshot("beta", "TCP", true, 12L, 3);
        PeerSnapshot betaDown = new PeerSnapshot("beta", "TCP", false, 0L, 3);

        ApiSnapshotDiff.PeerChange first = ApiSnapshotDiff.peers(Map.of(), List.of(beta));
        assertEquals(List.of(beta), first.connected());
        assertTrue(first.disconnected().isEmpty());

        ApiSnapshotDiff.PeerChange steady = ApiSnapshotDiff.peers(Map.of("beta", beta), List.of(beta));
        assertTrue(steady.connected().isEmpty());
        assertTrue(steady.disconnected().isEmpty());

        ApiSnapshotDiff.PeerChange dropped = ApiSnapshotDiff.peers(Map.of("beta", beta), List.of(betaDown));
        assertEquals(List.of("beta"), dropped.disconnected());

        ApiSnapshotDiff.PeerChange forgotten = ApiSnapshotDiff.peers(Map.of("beta", beta), List.of());
        assertEquals(List.of("beta"), forgotten.disconnected());
    }

    private static PortalSnapshot snapshot() {
        return new PortalSnapshot(PORTAL, "Hub", "PORTAL", "minecraft:overworld", 1.0D, 2.0D, 3.0D, "N",
            true, null, null, TRAVELER, true);
    }
}
