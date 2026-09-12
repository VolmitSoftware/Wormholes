package art.arcane.wormholes.network.convoy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.transit.ConvoyGraph;
import art.arcane.wormholes.transit.TransitTestSupport;
import art.arcane.wormholes.transit.TransitTestSupport.Rig;

final class ConvoyTransferServiceTest {
    private static final long TIMEOUT_MILLIS = 20_000L;

    @Test
    void aPeerWithoutTheConvoyBitFailsBeforeAnythingIsFrozenOrSent() {
        Fixture fixture = new Fixture();
        fixture.transport.supportsConvoy = false;

        boolean begun = fixture.service.begin(fixture.player(), fixture.graph, fixture.tunnel, fixture.traversive, fixture.source, TIMEOUT_MILLIS);

        assertFalse(begun);
        assertTrue(fixture.transport.sent.isEmpty());
        assertTrue(fixture.rig.frozen.isEmpty());
        assertEquals(List.of(fixture.player()), fixture.rig.rejected);
        assertEquals(1, fixture.rig.notices.size());
        assertTrue(fixture.rig.notices.getFirst().contains("convoy"));
        assertTrue(fixture.service.ledger().inFlight().isEmpty());
    }

    @Test
    void anAcceptedAckDispatchesThePlayerAndTheArrivalResultRemovesTheSourceRig() {
        Fixture fixture = new Fixture();

        assertTrue(fixture.service.begin(fixture.player(), fixture.graph, fixture.tunnel, fixture.traversive, fixture.source, TIMEOUT_MILLIS));

        WireMessage.ConvoyTransfer transfer = assertInstanceOf(WireMessage.ConvoyTransfer.class, fixture.transport.sent.getFirst());
        ConvoyManifest manifest = transfer.manifest();
        assertEquals(fixture.tunnel.getDestinationPortalId(), manifest.destPortalId());
        assertEquals(fixture.driver.id(), manifest.playerId());
        assertEquals(3, manifest.members().size());
        assertEquals(fixture.boat.id(), manifest.members().get(0).entityId());
        assertEquals("boat", new String(manifest.members().get(0).snapshot(), StandardCharsets.UTF_8));
        assertEquals(0, manifest.rider().snapshot().length, "the player carries no snapshot");
        assertEquals(fixture.boat.id(), manifest.rider().vehicleId());
        assertEquals(fixture.driver.id(), manifest.members().get(2).leashHolderId());
        assertEquals(List.of(fixture.boat.entity(), fixture.horse.entity()), fixture.rig.frozen, "only non-player members freeze");
        assertTrue(fixture.rig.stillPending.getFirst().getAsBoolean());
        assertEquals(1, fixture.journal.size());
        ConvoyLedger.Group group = fixture.service.ledger().find(manifest.groupId());
        assertEquals(ConvoyLedger.Phase.OPEN, group.phase());

        fixture.service.onAck("beta", new WireMessage.ConvoyAck(manifest.groupId(), true, "admitted"));

        assertEquals(List.of(fixture.player()), fixture.rig.dispatched);
        assertEquals(ConvoyLedger.Phase.DISPATCHED, fixture.service.ledger().find(manifest.groupId()).phase());
        assertTrue(fixture.rig.removed.isEmpty(), "members stay frozen until the destination confirms the player arrived");

        fixture.service.onHandoffResult("beta", new WireMessage.HandoffResult(UUID.randomUUID(), fixture.driver.id(), true, "portal arrival completed"));

        assertEquals(List.of(fixture.boat.entity(), fixture.horse.entity()), fixture.rig.removed);
        assertTrue(fixture.rig.restored.isEmpty());
        assertNull(fixture.service.ledger().find(manifest.groupId()));
        assertFalse(fixture.rig.stillPending.getFirst().getAsBoolean());
        assertTrue(fixture.journal.getLast().isEmpty(), "the journal is rewritten without the finished group");
    }

    @Test
    void aDeniedAckRestoresTheRigAndBouncesThePlayerWithTheReason() {
        Fixture fixture = new Fixture();
        assertTrue(fixture.service.begin(fixture.player(), fixture.graph, fixture.tunnel, fixture.traversive, fixture.source, TIMEOUT_MILLIS));
        UUID groupId = ((WireMessage.ConvoyTransfer) fixture.transport.sent.getFirst()).manifest().groupId();

        fixture.service.onAck("beta", new WireMessage.ConvoyAck(groupId, false, "portal closed"));

        assertEquals(List.of(fixture.boat.entity(), fixture.horse.entity()), fixture.rig.restored);
        assertEquals(List.of(fixture.player()), fixture.rig.rejected);
        assertEquals(List.of("portal closed"), fixture.rig.notices);
        assertTrue(fixture.rig.dispatched.isEmpty());
        assertNull(fixture.service.ledger().find(groupId));
        fixture.service.onAck("beta", new WireMessage.ConvoyAck(groupId, false, "portal closed"));
        assertEquals(2, fixture.rig.restored.size(), "a repeated denial is ignored");
    }

    @Test
    void aSilentDestinationTimesOutAndRestoresTheRig() {
        Fixture fixture = new Fixture();
        assertTrue(fixture.service.begin(fixture.player(), fixture.graph, fixture.tunnel, fixture.traversive, fixture.source, TIMEOUT_MILLIS));
        assertEquals(1, fixture.rig.scheduled.size());
        assertEquals(TIMEOUT_MILLIS / 50L, fixture.rig.scheduledDelays.getFirst());

        fixture.clock = fixture.clock + TIMEOUT_MILLIS + 1L;
        fixture.rig.scheduled.getFirst().run();

        assertEquals(List.of(fixture.boat.entity(), fixture.horse.entity()), fixture.rig.restored);
        assertEquals(List.of(fixture.player()), fixture.rig.rejected);
        assertEquals(1, fixture.rig.notices.size());
        assertTrue(fixture.service.ledger().inFlight().isEmpty());
    }

    @Test
    void aFailedArrivalAfterDispatchRestoresTheSourceRig() {
        Fixture fixture = new Fixture();
        assertTrue(fixture.service.begin(fixture.player(), fixture.graph, fixture.tunnel, fixture.traversive, fixture.source, TIMEOUT_MILLIS));
        UUID groupId = ((WireMessage.ConvoyTransfer) fixture.transport.sent.getFirst()).manifest().groupId();
        fixture.service.onAck("beta", new WireMessage.ConvoyAck(groupId, true, "admitted"));

        fixture.service.onHandoffResult("beta", new WireMessage.HandoffResult(UUID.randomUUID(), fixture.driver.id(), false, "arrival exhausted"));

        assertEquals(List.of(fixture.boat.entity(), fixture.horse.entity()), fixture.rig.restored);
        assertTrue(fixture.rig.removed.isEmpty());
        assertNull(fixture.service.ledger().find(groupId));
    }

    /**
     * The duplication case from the headline review: a dispatched player may already stand on the peer
     * with their rig around them, so a lost handoff result must not put the rig back here as well.
     */
    @Test
    void aDispatchedGroupThatNeverAnswersRemovesTheSourceRigRatherThanDuplicatingIt() {
        Fixture fixture = new Fixture();
        assertTrue(fixture.service.begin(fixture.player(), fixture.graph, fixture.tunnel, fixture.traversive, fixture.source, TIMEOUT_MILLIS));
        UUID groupId = ((WireMessage.ConvoyTransfer) fixture.transport.sent.getFirst()).manifest().groupId();
        fixture.service.onAck("beta", new WireMessage.ConvoyAck(groupId, true, "admitted"));

        fixture.rig.scheduled.getFirst().run();
        assertEquals(2, fixture.rig.scheduled.size(), "a dispatched group gets one more window");
        assertTrue(fixture.rig.removed.isEmpty());

        fixture.rig.scheduled.get(1).run();

        assertEquals(List.of(fixture.boat.entity(), fixture.horse.entity()), fixture.rig.removed);
        assertTrue(fixture.rig.restored.isEmpty(), "a dispatched rig is never restored on the source");
        assertNull(fixture.service.ledger().find(groupId));
    }

    /**
     * pushConvoy marks the whole rig in flight before the offer, so a refusal that sends nothing has to
     * release the whole rig; clearing only the player stranded the boat and the mobs for the mark's TTL.
     */
    @Test
    void aRefusalBeforeTheOfferReleasesEveryMember() {
        Fixture fixture = new Fixture();
        fixture.transport.supportsConvoy = false;

        assertFalse(fixture.service.begin(fixture.player(), fixture.graph, fixture.tunnel, fixture.traversive, fixture.source, TIMEOUT_MILLIS));

        assertEquals(fixture.graph.size(), fixture.rig.cleared.size());
        assertTrue(fixture.rig.cleared.contains(fixture.boat.entity()));
        assertTrue(fixture.rig.cleared.contains(fixture.horse.entity()));
        assertTrue(fixture.rig.cleared.contains(fixture.driver.entity()));
    }

    @Test
    void aSendRejectionRestoresImmediately() {
        Fixture fixture = new Fixture();
        fixture.transport.sendAccepted = false;

        assertFalse(fixture.service.begin(fixture.player(), fixture.graph, fixture.tunnel, fixture.traversive, fixture.source, TIMEOUT_MILLIS));

        assertEquals(List.of(fixture.boat.entity(), fixture.horse.entity()), fixture.rig.restored);
        assertEquals(List.of(fixture.player()), fixture.rig.rejected);
        assertTrue(fixture.service.ledger().inFlight().isEmpty());
    }

    private static final class Fixture {
        private final World world = TransitTestSupport.world("convoy-transfer");
        private final LocalPortal source = TransitTestSupport.portal(world);
        private final Rig boat = Rig.vehicle("boat", new Location(world, 1.0D, 65.0D, 1.0D), 1.375D, 0.5625D);
        private final Rig driver = Rig.player("driver", new Location(world, 1.0D, 65.0D, 1.0D)).ride(boat);
        private final Rig horse = Rig.mob("horse", new Location(world, 1.0D, 65.0D, 2.0D), 1.4D, 1.6D).leashTo(driver);
        private final ConvoyGraph graph = ConvoyGraph.closure(driver.entity(), List.of(boat.entity(), driver.entity(), horse.entity()), 16);
        private final UniversalTunnel tunnel = new UniversalTunnel("beta", UUID.randomUUID());
        private final Traversive traversive = new Traversive(driver.entity(), source.getFrame().view(true), source.getOrigin(),
            driver.entity().getLocation().toVector(), new Vector(-0.4D, 0.0D, 0.0D), new Vector(-1.0D, 0.0D, 0.0D), true, source.getId());
        private final FakeTransport transport = new FakeTransport();
        private final FakeRig rig = new FakeRig();
        private final List<List<ConvoyLedger.Group>> journal = new ArrayList<List<ConvoyLedger.Group>>();
        private long clock = 100_000L;
        private final ConvoyTransferService service;

        private Fixture() {
            service = new ConvoyTransferService(new ConvoyLedger(), transport, rig, () -> clock);
            service.journal(inFlight -> journal.add(List.copyOf(inFlight)));
        }

        private Player player() {
            return (Player) driver.entity();
        }
    }

    private static final class FakeTransport implements ConvoyTransferService.Transport {
        private final List<WireMessage> sent = new ArrayList<WireMessage>();
        private boolean supportsConvoy = true;
        private boolean sendAccepted = true;

        @Override
        public boolean peerReady(String peerName) {
            return true;
        }

        @Override
        public boolean peerSupportsConvoy(String peerName) {
            return supportsConvoy;
        }

        @Override
        public boolean send(String peerName, WireMessage message) {
            if (!sendAccepted) {
                return false;
            }
            sent.add(message);
            return true;
        }
    }

    private static final class FakeRig implements ConvoyTransferService.Rig {
        private final List<Entity> frozen = new ArrayList<Entity>();
        private final List<BooleanSupplier> stillPending = new ArrayList<BooleanSupplier>();
        private final List<Entity> restored = new ArrayList<Entity>();
        private final List<Entity> removed = new ArrayList<Entity>();
        private final List<Player> dispatched = new ArrayList<Player>();
        private final List<Player> rejected = new ArrayList<Player>();
        private final List<Entity> cleared = new ArrayList<Entity>();
        private final List<String> notices = new ArrayList<String>();
        private final List<Runnable> scheduled = new ArrayList<Runnable>();
        private final List<Long> scheduledDelays = new ArrayList<Long>();

        @Override
        public byte[] snapshot(Entity member) {
            return member.getName().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void freeze(Entity member, BooleanSupplier pending) {
            frozen.add(member);
            stillPending.add(pending);
        }

        @Override
        public void restore(Entity member) {
            restored.add(member);
        }

        @Override
        public void remove(Entity member) {
            removed.add(member);
        }

        @Override
        public void dispatchPlayer(Player player, UniversalTunnel tunnel, Traversive traversive, LocalPortal source) {
            dispatched.add(player);
        }

        @Override
        public void rejectSource(Player player, LocalPortal source, Traversive traversive) {
            rejected.add(player);
        }

        @Override
        public void clearInFlight(Entity member) {
            cleared.add(member);
        }

        @Override
        public void notice(Player player, String reason) {
            notices.add(reason);
        }

        @Override
        public boolean schedule(Runnable task, long delayTicks) {
            scheduled.add(task);
            scheduledDelays.add(Long.valueOf(delayTicks));
            return true;
        }
    }

}
