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

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.WireTraversive;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.transit.TransitTestSupport;
import art.arcane.wormholes.transit.TransitTestSupport.Rig;

final class ConvoyArrivalPlacerTest {
    private static final long TIMEOUT_MILLIS = 20_000L;

    @Test
    void admissionSpawnsEveryMemberHeldThenThePlayersArrivalRevealsAndReattachesTheRig() {
        Fixture fixture = new Fixture();

        fixture.placer.admit("alpha", fixture.manifest, TIMEOUT_MILLIS);

        assertEquals(List.of("boat", "horse"), fixture.spawner.spawnedNames());
        assertEquals(fixture.spawner.spawned, fixture.spawner.held);
        WireMessage.ConvoyAck ack = assertInstanceOf(WireMessage.ConvoyAck.class, fixture.sent.getFirst());
        assertTrue(ack.accepted());
        assertEquals(fixture.manifest.groupId(), ack.groupId());
        assertEquals(ConvoyLedger.Phase.ADMITTED, fixture.ledger.find(fixture.manifest.groupId()).phase());
        assertTrue(fixture.spawner.revealed.isEmpty());

        fixture.placer.onPlayerPlaced(fixture.player(), fixture.exit, fixture.traversive);

        assertEquals(List.of("boat", "horse"), fixture.spawner.settledNames());
        assertEquals(fixture.spawner.spawned, fixture.spawner.revealed);
        assertEquals(List.of("boat<-driver"), fixture.spawner.mounted);
        assertEquals(List.of("horse<-driver"), fixture.spawner.leashed);
        assertNull(fixture.ledger.find(fixture.manifest.groupId()));
        assertTrue(fixture.spawner.removed.isEmpty());

        fixture.placer.onPlayerPlaced(fixture.player(), fixture.exit, fixture.traversive);
        assertEquals(1, fixture.spawner.mounted.size(), "a second arrival has nothing to attach");
    }

    @Test
    void aRefusedMemberRemovesEverythingSpawnedAndDeniesTheGroup() {
        Fixture fixture = new Fixture();
        fixture.spawner.refuse = "horse";

        fixture.placer.admit("alpha", fixture.manifest, TIMEOUT_MILLIS);

        assertEquals(List.of("boat"), fixture.spawner.spawnedNames());
        assertEquals(fixture.spawner.spawned, fixture.spawner.removed);
        WireMessage.ConvoyAck ack = assertInstanceOf(WireMessage.ConvoyAck.class, fixture.sent.getFirst());
        assertFalse(ack.accepted());
        assertNull(fixture.ledger.find(fixture.manifest.groupId()));
        assertEquals(1, fixture.sent.size());
    }

    @Test
    void aPlayerWhoNeverArrivesExpiresTheHeldRigAndReportsBack() {
        Fixture fixture = new Fixture();
        fixture.placer.admit("alpha", fixture.manifest, TIMEOUT_MILLIS);
        assertEquals(1, fixture.spawner.scheduled.size());

        fixture.clock = fixture.clock + TIMEOUT_MILLIS + 1L;
        fixture.spawner.scheduled.getFirst().run();

        assertEquals(fixture.spawner.spawned, fixture.spawner.removed);
        WireMessage.ConvoyAck timeout = assertInstanceOf(WireMessage.ConvoyAck.class, fixture.sent.getLast());
        assertFalse(timeout.accepted());
        assertEquals(2, fixture.sent.size());
        assertNull(fixture.ledger.find(fixture.manifest.groupId()));

        fixture.placer.onPlayerPlaced(fixture.player(), fixture.exit, fixture.traversive);
        assertTrue(fixture.spawner.mounted.isEmpty(), "a late arrival finds nothing to attach");
    }

    @Test
    void anUnknownOrClosedExitIsDeniedWithoutSpawning() {
        Fixture fixture = new Fixture();
        fixture.spawner.accepting = false;

        fixture.placer.admit("alpha", fixture.manifest, TIMEOUT_MILLIS);

        assertTrue(fixture.spawner.spawned.isEmpty());
        WireMessage.ConvoyAck ack = assertInstanceOf(WireMessage.ConvoyAck.class, fixture.sent.getFirst());
        assertFalse(ack.accepted());
        assertNull(fixture.ledger.find(fixture.manifest.groupId()));
    }

    @Test
    void aRepeatedManifestForAHeldGroupIsAcknowledgedAgainWithoutSpawningTwice() {
        Fixture fixture = new Fixture();
        fixture.placer.admit("alpha", fixture.manifest, TIMEOUT_MILLIS);
        fixture.placer.admit("alpha", fixture.manifest, TIMEOUT_MILLIS);

        assertEquals(2, fixture.spawner.spawned.size());
        assertEquals(2, fixture.sent.size());
        assertTrue(((WireMessage.ConvoyAck) fixture.sent.getLast()).accepted());
    }

    private static final class Fixture {
        private final World world = TransitTestSupport.world("convoy-arrival");
        private final LocalPortal exit = TransitTestSupport.portal(world);
        private final Rig driver = Rig.player("driver", new Location(world, 1.0D, 65.0D, 1.0D));
        private final UUID boatId = UUID.randomUUID();
        private final UUID horseId = UUID.randomUUID();
        private final ConvoyManifest manifest = new ConvoyManifest(UUID.randomUUID(), exit.getId(), List.of(
            new ConvoyManifest.Member(boatId, bytes("boat"), null, null, wire(1.0D), false),
            new ConvoyManifest.Member(driver.id(), new byte[0], boatId, null, wire(1.0D), true),
            new ConvoyManifest.Member(horseId, bytes("horse"), null, driver.id(), wire(2.0D), false)));
        private final Traversive traversive = wire(1.0D).toTraversive(driver.entity());
        private final FakeSpawner spawner = new FakeSpawner(world);
        private final List<WireMessage> sent = new ArrayList<WireMessage>();
        private final ConvoyLedger ledger = new ConvoyLedger();
        private long clock = 500_000L;
        private final ConvoyArrivalPlacer placer;

        private Fixture() {
            spawner.exit = exit;
            placer = new ConvoyArrivalPlacer(ledger, spawner, (peer, message) -> sent.add(message), () -> clock);
        }

        private Player player() {
            return (Player) driver.entity();
        }
    }

    private static WireTraversive wire(double pointZ) {
        return new WireTraversive("N", "E", "U", 0.0D, 64.0D, 0.0D, 1.0D, 65.0D, pointZ, -0.4D, 0.0D, 0.0D, -1.0D, 0.0D, 0.0D, true);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static final class FakeSpawner implements ConvoyArrivalPlacer.Spawner {
        private final World world;
        private final List<Entity> spawned = new ArrayList<Entity>();
        private final List<Entity> held = new ArrayList<Entity>();
        private final List<Entity> revealed = new ArrayList<Entity>();
        private final List<Entity> removed = new ArrayList<Entity>();
        private final List<Entity> settled = new ArrayList<Entity>();
        private final List<String> mounted = new ArrayList<String>();
        private final List<String> leashed = new ArrayList<String>();
        private final List<Runnable> scheduled = new ArrayList<Runnable>();
        private ILocalPortal exit;
        private boolean accepting = true;
        private String refuse;

        private FakeSpawner(World world) {
            this.world = world;
        }

        private List<String> spawnedNames() {
            return spawned.stream().map(Entity::getName).toList();
        }

        private List<String> settledNames() {
            return settled.stream().map(Entity::getName).toList();
        }

        @Override
        public ILocalPortal exit(UUID portalId) {
            return exit != null && exit.getId().equals(portalId) ? exit : null;
        }

        @Override
        public boolean accepts(ILocalPortal portal) {
            return accepting;
        }

        @Override
        public Entity spawn(ILocalPortal portal, byte[] snapshot, Location target) {
            String name = new String(snapshot, StandardCharsets.UTF_8);
            if (name.equals(refuse)) {
                return null;
            }
            Entity entity = Rig.mob(name, target, 1.0D, 1.0D).entity();
            spawned.add(entity);
            return entity;
        }

        @Override
        public void hold(Entity entity) {
            held.add(entity);
        }

        @Override
        public void reveal(Entity entity) {
            revealed.add(entity);
        }

        @Override
        public void remove(Entity entity) {
            removed.add(entity);
        }

        @Override
        public void mount(Entity vehicle, Entity passenger) {
            mounted.add(vehicle.getName() + "<-" + passenger.getName());
        }

        @Override
        public void leash(Entity leashedEntity, Entity holder) {
            leashed.add(leashedEntity.getName() + "<-" + holder.getName());
        }

        @Override
        public void settle(ILocalPortal portal, Entity member, Traversive memberTraversive) {
            settled.add(member);
        }

        @Override
        public boolean runRegion(Location location, Runnable task) {
            task.run();
            return true;
        }

        @Override
        public boolean schedule(Runnable task, long delayTicks) {
            scheduled.add(task);
            return true;
        }
    }
}
