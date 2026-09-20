package art.arcane.wormholes.portal;

import art.arcane.wormholes.TraversableManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LocalPortalCrossingTest {
    private TraversableManager previousManager;

    @BeforeEach
    void setup() {
        previousManager = Wormholes.traversableManager;
        Wormholes.traversableManager = new TraversableManager();
    }

    @AfterEach
    void cleanup() {
        Wormholes.traversableManager = previousManager;
    }

    @Test
    void crossingPreservesTheFullDiagonalEndpointAndVelocityAcrossEveryFrame() {
        World world = LocalPortalTestSupport.world("crossing-frames");
        for (Direction direction : List.of(Direction.N, Direction.S, Direction.E, Direction.W, Direction.U, Direction.D)) {
            LocalPortal source = portal(world, direction);
            for (boolean frontSide : List.of(Boolean.TRUE, Boolean.FALSE)) {
                double sign = frontSide ? -1.0D : 1.0D;
                Vector normal = source.getFrame().getNormal().toVector();
                Vector velocity = normal.clone().multiply(sign * 0.8D)
                    .add(source.getFrame().getRight().toVector().multiply(0.31D))
                    .add(source.getFrame().getUp().toVector().multiply(-0.27D));
                Vector start = source.getOrigin().clone().subtract(normal.clone().multiply(sign * 0.1D));
                Vector end = start.clone().add(velocity);
                LocalPortalTestSupport.FakeEntity entity = LocalPortalTestSupport.FakeEntity.entity("crossing", end.toLocation(world));
                entity.entity().setVelocity(velocity);

                Traversive crossing = source.traversal().rayTeleport(entity.entity(), start.toLocation(world));

                assertNotNull(crossing);
                assertEquals(frontSide, crossing.isFrontSide());
                assertVector(end, crossing.getInPoint());
                assertVector(velocity, crossing.getInVelocity());
                LocalPortal destination = portal(world, direction.reverse());
                Vector expected = source.getFrame().transformPoint(end, source.getOrigin(), destination.getOrigin(), destination.getFrame());
                assertVector(expected, destination.computeExitTarget(crossing).toVector());
                assertVector(source.getFrame().transformVector(velocity, destination.getFrame()), crossing.getOutVelocity(destination.getFrame()));
            }
        }
    }

    @Test
    void slowCrossingKeepsItsVelocityInsteadOfUsingTheLookDirection() {
        World world = LocalPortalTestSupport.world("slow-crossing");
        LocalPortal source = portal(world, Direction.E);
        Vector velocity = new Vector(-0.004D, 0.002D, 0.001D);
        Vector end = source.getOrigin().clone().add(new Vector(-0.001D, 0.0D, 0.0D));
        LocalPortalTestSupport.FakeEntity entity = LocalPortalTestSupport.FakeEntity.entity("slow", end.toLocation(world));
        entity.entity().setVelocity(velocity);

        Traversive crossing = source.traversal().rayTeleport(entity.entity(), null);

        assertNotNull(crossing);
        assertVector(velocity, crossing.getInVelocity());
        assertVector(end, crossing.getInPoint());
    }

    @Test
    void occupyingTheApertureWithoutCrossingItsPlaneDoesNotTeleport() {
        World world = LocalPortalTestSupport.world("uncrossed-aperture");
        LocalPortal source = portal(world, Direction.E);
        Vector end = source.getOrigin().clone().add(new Vector(0.1D, 0.0D, 0.0D));
        LocalPortalTestSupport.FakeEntity entity = LocalPortalTestSupport.FakeEntity.entity("uncrossed", end.toLocation(world));
        for (Vector velocity : List.of(new Vector(), new Vector(-0.1D, 0.0D, 0.0D), new Vector(0.0D, 0.1D, 0.0D))) {
            entity.entity().setVelocity(velocity);
            assertNull(source.traversal().rayTeleport(entity.entity(), null));
        }
    }

    @Test
    void crossingOutsideTheApertureDoesNotTeleport() {
        World world = LocalPortalTestSupport.world("outside-aperture");
        LocalPortal source = portal(world, Direction.E);
        Vector end = source.getOrigin().clone().add(new Vector(-0.1D, 8.0D, 0.0D));
        LocalPortalTestSupport.FakeEntity entity = LocalPortalTestSupport.FakeEntity.entity("outside", end.toLocation(world));
        entity.entity().setVelocity(new Vector(-0.4D, 0.0D, 0.0D));
        assertNull(source.traversal().rayTeleport(entity.entity(), null));
    }

    @Test
    void startingOnThePlanePreservesTheDepartureSide() {
        World world = LocalPortalTestSupport.world("plane-start");
        LocalPortal source = portal(world, Direction.E);
        Vector start = source.getOrigin().clone();
        for (double speed : List.of(-0.1D, 0.1D)) {
            Vector velocity = new Vector(speed, 0.0D, 0.0D);
            Vector end = start.clone().add(velocity);
            LocalPortalTestSupport.FakeEntity entity = LocalPortalTestSupport.FakeEntity.entity("plane", end.toLocation(world));
            entity.entity().setVelocity(velocity);
            Traversive crossing = source.traversal().rayTeleport(entity.entity(), start.toLocation(world));
            assertNotNull(crossing);
            assertEquals(speed < 0.0D, crossing.isFrontSide());
        }
    }

    @Test
    void previousEntityPositionClosesTheGapLeftByVelocityDrag() {
        World world = LocalPortalTestSupport.world("entity-drag");
        LocalPortal source = portal(world, Direction.E);
        Vector start = source.getOrigin().clone().add(new Vector(0.001D, 0.0D, 0.0D));
        Vector end = start.clone().add(new Vector(-0.1D, 0.0D, 0.0D));
        LocalPortalTestSupport.FakeEntity entity = LocalPortalTestSupport.FakeEntity.entity("drag", start.toLocation(world));
        PortalCaptureHistory history = new PortalCaptureHistory();
        history.beginPass();
        history.capture(Wormholes.traversableManager.entityContinuity(entity.entity(), start.toLocation(world), 1_000L),
            start.toLocation(world), 1_000L);
        entity.entity().teleport(end.toLocation(world));
        entity.entity().setVelocity(new Vector(-0.098D, 0.0D, 0.0D));
        history.beginPass();
        Location sweepStart = history.capture(Wormholes.traversableManager.entityContinuity(entity.entity(), end.toLocation(world), 1_050L),
            end.toLocation(world), 1_050L);

        Traversive crossing = source.traversal().rayTeleport(entity.entity(), sweepStart);

        assertNotNull(crossing);
        assertVector(end, crossing.getInPoint());
        assertVector(new Vector(-0.098D, 0.0D, 0.0D), crossing.getInVelocity());
    }

    @Test
    void externalEntityTeleportInvalidatesThePreviousSweep() {
        World world = LocalPortalTestSupport.world("entity-teleport");
        LocalPortal source = portal(world, Direction.E);
        Location start = source.getOrigin().clone().add(new Vector(0.2D, 0.0D, 0.0D)).toLocation(world);
        Location end = start.clone().subtract(0.4D, 0.0D, 0.0D);
        LocalPortalTestSupport.FakeEntity entity = LocalPortalTestSupport.FakeEntity.entity("teleported", start);
        PortalCaptureHistory history = new PortalCaptureHistory();
        history.beginPass();
        history.capture(Wormholes.traversableManager.entityContinuity(entity.entity(), start, 1_000L), start, 1_000L);
        Wormholes.traversableManager.on(new EntityTeleportEvent(entity.entity(), start, end));
        history.beginPass();

        Location sweepStart = history.capture(Wormholes.traversableManager.entityContinuity(entity.entity(), end, 1_050L), end, 1_050L);
        assertEquals(end, sweepStart);
        entity.entity().teleport(end);
        entity.entity().setVelocity(new Vector(-0.4D, 0.0D, 0.0D));
        assertNull(source.traversal().rayTeleport(entity.entity(), sweepStart));
    }

    @Test
    void entityCaptureExpiresAfterLeavingTheCaptureZone() {
        World world = LocalPortalTestSupport.world("entity-capture-expiry");
        LocalPortal source = portal(world, Direction.E);
        Location location = source.getOrigin().toLocation(world);
        LocalPortalTestSupport.FakeEntity entity = LocalPortalTestSupport.FakeEntity.entity("expired", location);
        PortalCaptureHistory history = new PortalCaptureHistory();
        history.beginPass();
        history.capture(Wormholes.traversableManager.entityContinuity(entity.entity(), location, 1_000L), location, 1_000L);
        history.beginPass();
        history.beginPass();

        assertEquals(location, history.capture(Wormholes.traversableManager.entityContinuity(entity.entity(), location, 1_100L), location, 1_100L));
    }

    private static LocalPortal portal(World world, Direction normal) {
        PortalStructure structure = new PortalStructure();
        structure.setWorld(world);
        structure.setArea(new Cuboid(new Location(world, -2.0D, 64.0D, -2.0D), new Location(world, 2.0D, 68.0D, 2.0D)));
        LocalPortal portal = new LocalPortal(UUID.randomUUID(), PortalType.PORTAL, structure);
        portal.setFrame(PortalFrame.canonical(normal));
        portal.setAmbientAttended(false);
        return portal;
    }

    private static void assertVector(Vector expected, Vector actual) {
        assertEquals(expected.getX(), actual.getX(), 1.0E-9D);
        assertEquals(expected.getY(), actual.getY(), 1.0E-9D);
        assertEquals(expected.getZ(), actual.getZ(), 1.0E-9D);
    }
}
