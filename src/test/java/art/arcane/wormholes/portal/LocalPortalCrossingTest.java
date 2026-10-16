package art.arcane.wormholes.portal;

import art.arcane.wormholes.util.BukkitGeometry;

import art.arcane.wormholes.TraversableManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.optics.math.Face;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.AxisPermutation;

class LocalPortalCrossingTest {
    private static final int SOURCE_FLOOR = 64;
    private static final int ARRIVAL_FLOOR = 90;

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
        for (Face direction : List.of(Face.N, Face.S, Face.E, Face.W, Face.U, Face.D)) {
            LocalPortal source = portal(world, direction);
            for (boolean frontSide : List.of(Boolean.TRUE, Boolean.FALSE)) {
                double sign = frontSide ? -1.0D : 1.0D;
                Vector normal = BukkitGeometry.bukkit(source.getFrame().getNormal());
                Vector velocity = normal.clone().multiply(sign * 0.8D)
                    .add(BukkitGeometry.bukkit(source.getFrame().getRight()).multiply(0.31D))
                    .add(BukkitGeometry.bukkit(source.getFrame().getUp()).multiply(-0.27D));
                Vector start = BukkitGeometry.bukkit(source.getOrigin()).subtract(normal.clone().multiply(sign * 0.1D));
                Vector end = start.clone().add(velocity);
                LocalPortalTestSupport.FakeEntity entity = LocalPortalTestSupport.FakeEntity.entity("crossing", end.toLocation(world));
                entity.entity().setVelocity(velocity);

                Traversive crossing = source.traversal().rayTeleport(entity.entity(), start.toLocation(world));

                assertNotNull(crossing);
                assertEquals(frontSide, crossing.isFrontSide());
                assertVector(end, crossing.getInPoint());
                assertVector(velocity, crossing.getInVelocity());
                LocalPortal destination = portal(world, direction.reverse());
                Vector expected = BukkitGeometry.bukkit(OpticTransform.between(source.getFrame(), source.getOrigin(), destination.getFrame(), destination.getOrigin()).point(BukkitGeometry.vector(end)));
                assertVector(expected, destination.computeExitTarget(crossing).toVector());
                assertVector(BukkitGeometry.bukkit(OpticTransform.of(AxisPermutation.between(source.getFrame(), destination.getFrame()), 0.0D, 0.0D, 0.0D).vector(BukkitGeometry.vector(velocity))), crossing.getOutVelocity(destination.getFrame()));
            }
        }
    }

    @Test
    void slowCrossingKeepsItsVelocityInsteadOfUsingTheLookDirection() {
        World world = LocalPortalTestSupport.world("slow-crossing");
        LocalPortal source = portal(world, Face.E);
        Vector velocity = new Vector(-0.004D, 0.002D, 0.001D);
        Vector end = BukkitGeometry.bukkit(source.getOrigin()).add(new Vector(-0.001D, 0.0D, 0.0D));
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
        LocalPortal source = portal(world, Face.E);
        Vector end = BukkitGeometry.bukkit(source.getOrigin()).add(new Vector(0.1D, 0.0D, 0.0D));
        LocalPortalTestSupport.FakeEntity entity = LocalPortalTestSupport.FakeEntity.entity("uncrossed", end.toLocation(world));
        for (Vector velocity : List.of(new Vector(), new Vector(-0.1D, 0.0D, 0.0D), new Vector(0.0D, 0.1D, 0.0D))) {
            entity.entity().setVelocity(velocity);
            assertNull(source.traversal().rayTeleport(entity.entity(), null));
        }
    }

    @Test
    void crossingOutsideTheApertureDoesNotTeleport() {
        World world = LocalPortalTestSupport.world("outside-aperture");
        LocalPortal source = portal(world, Face.E);
        Vector end = BukkitGeometry.bukkit(source.getOrigin()).add(new Vector(-0.1D, 8.0D, 0.0D));
        LocalPortalTestSupport.FakeEntity entity = LocalPortalTestSupport.FakeEntity.entity("outside", end.toLocation(world));
        entity.entity().setVelocity(new Vector(-0.4D, 0.0D, 0.0D));
        assertNull(source.traversal().rayTeleport(entity.entity(), null));
    }

    @Test
    void startingOnThePlanePreservesTheDepartureSide() {
        World world = LocalPortalTestSupport.world("plane-start");
        LocalPortal source = portal(world, Face.E);
        Vector start = BukkitGeometry.bukkit(source.getOrigin());
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
        LocalPortal source = portal(world, Face.E);
        Vector start = BukkitGeometry.bukkit(source.getOrigin()).add(new Vector(0.001D, 0.0D, 0.0D));
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
        LocalPortal source = portal(world, Face.E);
        Location start = BukkitGeometry.bukkit(source.getOrigin()).add(new Vector(0.2D, 0.0D, 0.0D)).toLocation(world);
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
        LocalPortal source = portal(world, Face.E);
        Location location = BukkitGeometry.location(source.getOrigin(), world);
        LocalPortalTestSupport.FakeEntity entity = LocalPortalTestSupport.FakeEntity.entity("expired", location);
        PortalCaptureHistory history = new PortalCaptureHistory();
        history.beginPass();
        history.capture(Wormholes.traversableManager.entityContinuity(entity.entity(), location, 1_000L), location, 1_000L);
        history.beginPass();
        history.beginPass();

        assertEquals(location, history.capture(Wormholes.traversableManager.entityContinuity(entity.entity(), location, 1_100L), location, 1_100L));
    }

    @Test
    void aPlayerStandingStillOnTheFloorOfAnEndSourceIsSent() {
        World world = LocalPortalTestSupport.world("end-contact-standing");
        LocalPortal source = window(world, 10, SOURCE_FLOOR, 10, Face.U, DimensionalPortalKind.END_SOURCE);
        LocalPortalTestSupport.FakeEntity player = LocalPortalTestSupport.FakeEntity.player("standing", new Location(world, 11.5D, SOURCE_FLOOR, 11.5D));

        Traversive crossing = source.traversal().rayTeleport(player.entity(), null);

        assertNotNull(crossing);
        assertTrue(crossing.isFrontSide());
        assertVector(new Vector(11.5D, SOURCE_FLOOR, 11.5D), crossing.getInPoint());
    }

    @Test
    void walkingAcrossAtFloorLevelIsSentWithoutCrossingThePlane() {
        World world = LocalPortalTestSupport.world("end-contact-walk");
        LocalPortal source = window(world, 10, SOURCE_FLOOR, 10, Face.U, DimensionalPortalKind.END_SOURCE);
        LocalPortalTestSupport.FakeEntity walker = LocalPortalTestSupport.FakeEntity.entity("walker", new Location(world, 11.5D, SOURCE_FLOOR, 11.2D));
        walker.entity().setVelocity(new Vector(0.0D, 0.0D, -0.2D));

        Traversive crossing = source.traversal().rayTeleport(walker.entity(), new Location(world, 11.5D, SOURCE_FLOOR, 11.4D));

        assertNotNull(crossing);
        assertVector(new Vector(0.0D, 0.0D, -0.2D), crossing.getInVelocity());
        assertVector(new Vector(11.5D, SOURCE_FLOOR, 11.2D), crossing.getInPoint());
    }

    @Test
    void contactAboveOrBelowTheSourcePlaneArrivesBelowTheArrivalPlaneInsideItsFootprint() {
        World world = LocalPortalTestSupport.world("end-contact-arrival");
        LocalPortal source = window(world, 10, SOURCE_FLOOR, 10, Face.U, DimensionalPortalKind.END_SOURCE);
        LocalPortal arrival = window(world, 100, ARRIVAL_FLOOR, 200, Face.U, DimensionalPortalKind.END_ARRIVAL);
        double arrivalPlane = ARRIVAL_FLOOR + 0.5D;
        for (double feet : List.of(SOURCE_FLOOR + 0.0D, SOURCE_FLOOR + 0.3D, SOURCE_FLOOR + 0.5D, SOURCE_FLOOR + 0.7D)) {
            LocalPortalTestSupport.FakeEntity faller = LocalPortalTestSupport.FakeEntity.entity("faller", new Location(world, 10.4D, feet, 12.6D));
            faller.entity().setVelocity(new Vector(0.0D, -0.08D, 0.0D));

            Traversive crossing = source.traversal().rayTeleport(faller.entity(), null);

            assertNotNull(crossing, "contact at feet " + feet);
            Location exit = arrival.computeExitTarget(crossing);
            assertTrue(exit.getY() < arrivalPlane, "arrival below the END_ARRIVAL plane for feet " + feet + " but was " + exit.getY());
            assertEquals(Math.min(feet, SOURCE_FLOOR + 0.5D) - SOURCE_FLOOR, exit.getY() - ARRIVAL_FLOOR, 1.0E-2D);
            assertTrue(exit.getX() >= 100.0D && exit.getX() < 103.0D, "arrival x inside the window: " + exit.getX());
            assertTrue(exit.getZ() >= 200.0D && exit.getZ() < 203.0D, "arrival z inside the window: " + exit.getZ());
            assertTrue(arrival.computeExitTarget(crossing).toVector().subtract(new Vector(100.4D, exit.getY(), 202.6D)).length() < 1.0E-6D,
                "arrival keeps the footprint offset: " + exit);
        }
    }

    @Test
    void feetOnTheFrameTopAreNotSent() {
        World world = LocalPortalTestSupport.world("end-contact-frame");
        LocalPortal source = window(world, 10, SOURCE_FLOOR, 10, Face.U, DimensionalPortalKind.END_SOURCE);
        for (double feet : List.of(SOURCE_FLOOR + 0.75D, SOURCE_FLOOR + 0.8125D)) {
            LocalPortalTestSupport.FakeEntity player = LocalPortalTestSupport.FakeEntity.player("frame", new Location(world, 11.5D, feet, 11.5D));
            assertNull(source.traversal().rayTeleport(player.entity(), null));
        }
        LocalPortalTestSupport.FakeEntity beside = LocalPortalTestSupport.FakeEntity.player("beside", new Location(world, 9.5D, SOURCE_FLOOR, 11.5D));
        assertNull(source.traversal().rayTeleport(beside.entity(), null));
    }

    @Test
    void aFastFallThatLeavesThePortalBoxStillUsesThePlaneCrossing() {
        World world = LocalPortalTestSupport.world("end-contact-tunnel");
        LocalPortal source = window(world, 10, SOURCE_FLOOR, 10, Face.U, DimensionalPortalKind.END_SOURCE);
        LocalPortalTestSupport.FakeEntity faller = LocalPortalTestSupport.FakeEntity.entity("tunnel", new Location(world, 11.5D, SOURCE_FLOOR - 4.0D, 11.5D));
        faller.entity().setVelocity(new Vector(0.0D, -5.0D, 0.0D));

        Traversive crossing = source.traversal().rayTeleport(faller.entity(), new Location(world, 11.5D, SOURCE_FLOOR + 1.0D, 11.5D));

        assertNotNull(crossing);
        assertTrue(crossing.isFrontSide());
        assertVector(new Vector(11.5D, SOURCE_FLOOR - 4.0D, 11.5D), crossing.getInPoint());
    }

    @Test
    void aTravellerAlreadyInAnotherWorldIsNeverAdmitted() {
        World world = LocalPortalTestSupport.world("end-contact-source-world");
        World end = LocalPortalTestSupport.world("end-contact-end-world");
        LocalPortal source = window(world, 10, SOURCE_FLOOR, 10, Face.U, DimensionalPortalKind.END_SOURCE);
        LocalPortalTestSupport.FakeEntity overlapping = LocalPortalTestSupport.FakeEntity.entity("overlapping", new Location(end, 11.5D, SOURCE_FLOOR, 11.5D));
        assertNull(source.traversal().rayTeleport(overlapping.entity(), null));

        LocalPortalTestSupport.FakeEntity arrived = LocalPortalTestSupport.FakeEntity.entity("arrived", new Location(end, 12.5D, ARRIVAL_FLOOR - 20.0D, 10.5D));
        arrived.entity().setVelocity(new Vector(0.0D, -0.08D, 0.0D));
        assertNull(source.traversal().rayTeleport(arrived.entity(), new Location(world, 11.5D, SOURCE_FLOOR + 0.7D, 11.5D)));
    }

    @Test
    void otherFloorPortalsAndWallEndSourcesKeepThePlaneRule() {
        World world = LocalPortalTestSupport.world("end-contact-other");
        for (DimensionalPortalKind kind : List.of(DimensionalPortalKind.NONE, DimensionalPortalKind.NETHER, DimensionalPortalKind.END_ARRIVAL,
            DimensionalPortalKind.END_EXIT)) {
            LocalPortal floor = window(world, 10, SOURCE_FLOOR, 10, Face.U, kind);
            LocalPortalTestSupport.FakeEntity player = LocalPortalTestSupport.FakeEntity.player("floor", new Location(world, 11.5D, SOURCE_FLOOR, 11.5D));
            assertNull(floor.traversal().rayTeleport(player.entity(), null), kind.name());
        }
        LocalPortal wall = wall(world, DimensionalPortalKind.END_SOURCE);
        LocalPortalTestSupport.FakeEntity standing = LocalPortalTestSupport.FakeEntity.player("wall", new Location(world, 11.5D, SOURCE_FLOOR, 10.5D));
        assertNull(wall.traversal().rayTeleport(standing.entity(), null));
    }

    private static LocalPortal portal(World world, Face normal) {
        PortalStructure structure = new PortalStructure();
        structure.setWorld(world);
        structure.setArea(new Cuboid(new Location(world, -2.0D, 64.0D, -2.0D), new Location(world, 2.0D, 68.0D, 2.0D)));
        LocalPortal portal = new LocalPortal(UUID.randomUUID(), PortalType.PORTAL, structure);
        portal.setFrame(Frame.canonical(normal));
        portal.setAmbientAttended(false);
        return portal;
    }

    private static LocalPortal window(World world, int x, int y, int z, Face normal, DimensionalPortalKind kind) {
        PortalStructure structure = new PortalStructure();
        structure.setWorld(world);
        structure.setArea(new Cuboid(new Location(world, x, y, z), new Location(world, x + 2.0D, y, z + 2.0D)));
        return managed(structure, normal, kind);
    }

    private static LocalPortal wall(World world, DimensionalPortalKind kind) {
        PortalStructure structure = new PortalStructure();
        structure.setWorld(world);
        structure.setArea(new Cuboid(new Location(world, 10.0D, SOURCE_FLOOR, 10.0D), new Location(world, 12.0D, SOURCE_FLOOR + 2.0D, 10.0D)));
        return managed(structure, Face.S, kind);
    }

    private static LocalPortal managed(PortalStructure structure, Face normal, DimensionalPortalKind kind) {
        LocalPortal portal = new LocalPortal(UUID.randomUUID(), PortalType.PORTAL, structure);
        portal.setFrame(Frame.canonical(normal));
        portal.setDimensionalPortalKind(kind);
        portal.setAmbientAttended(false);
        return portal;
    }

    private static void assertVector(Vector expected, Vector actual) {
        assertEquals(expected.getX(), actual.getX(), 1.0E-9D);
        assertEquals(expected.getY(), actual.getY(), 1.0E-9D);
        assertEquals(expected.getZ(), actual.getZ(), 1.0E-9D);
    }
}
