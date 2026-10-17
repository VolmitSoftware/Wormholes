package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.function.Predicate;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.access.AccessTestPortals;
import art.arcane.wormholes.util.BukkitGeometry;
import art.arcane.wormholes.util.Cuboid;

final class LocalPortalTraversalArrivalClearanceTest {
    private static final double HEIGHT = 1.8D;
    private static final int CEILING_Y = 80;

    @Test
    void anArrivalUnderACeilingPortalWithStoneAboveItsOpeningMovesBelowTheOpening() {
        World world = AccessTestPortals.world("arrival-clearance");
        LocalPortal floor = horizontal(world, 0, 64, Face.U);
        LocalPortal ceiling = horizontal(world, 10, CEILING_Y, Face.U);
        Predicate<BoundingBox> stoneAboveCeiling = box -> box.getMaxY() > CEILING_Y + 1.0D;

        LocalPortalTraversal.ExitPlacement nominal = ceiling.traversal().exitPlacement(downward(floor));
        LocalPortalTraversal.ExitPlacement cleared = nominal.cleared(body(), stoneAboveCeiling);

        assertTrue(nominal.exit().getY() + HEIGHT > CEILING_Y + 1.0D, "nominal arrival must reach into the stone above the opening");
        assertEquals(CEILING_Y - 1.0E-3D, cleared.exit().getY() + HEIGHT, 1.0E-6D);
        assertEquals(cleared.exit().getY(), cleared.target().getY(), 0.0D);
        assertEquals(nominal.exit().getX(), cleared.exit().getX(), 0.0D);
        assertEquals(nominal.exit().getZ(), cleared.exit().getZ(), 0.0D);
        assertEquals(nominal.target().getPitch(), cleared.target().getPitch(), 0.0F);
    }

    @Test
    void anArrivalUnderAClearCeilingPortalKeepsItsPlacement() {
        World world = AccessTestPortals.world("arrival-clear");
        LocalPortal floor = horizontal(world, 0, 64, Face.U);
        LocalPortal ceiling = horizontal(world, 10, CEILING_Y, Face.U);

        LocalPortalTraversal.ExitPlacement nominal = ceiling.traversal().exitPlacement(downward(floor));

        assertSame(nominal, nominal.cleared(body(), box -> false));
    }

    @Test
    void anArrivalWhoseClearedSpaceIsAlsoBlockedKeepsItsPlacement() {
        World world = AccessTestPortals.world("arrival-blocked");
        LocalPortal floor = horizontal(world, 0, 64, Face.U);
        LocalPortal ceiling = horizontal(world, 10, CEILING_Y, Face.U);

        LocalPortalTraversal.ExitPlacement nominal = ceiling.traversal().exitPlacement(downward(floor));

        assertSame(nominal, nominal.cleared(body(), box -> true));
    }


    private static LocalPortal horizontal(World world, int corner, int y, Face normal) {
        PortalStructure structure = new PortalStructure();
        structure.setWorld(world);
        structure.setArea(new Cuboid(new Location(world, corner, y, corner), new Location(world, corner + 2.0D, y, corner + 2.0D)));
        LocalPortal portal = new LocalPortal(UUID.randomUUID(), PortalType.PORTAL, structure);
        portal.setAmbientAttended(false);
        portal.setFrame(Frame.canonical(normal));
        return portal;
    }

    private static Traversive downward(LocalPortal source) {
        Player player = mock(Player.class);
        when(player.getBoundingBox()).thenReturn(body());
        Vec3d start = new Vec3d(1.5D, 64.85D, 1.5D);
        Vec3d end = new Vec3d(1.5D, 64.45D, 1.5D);
        Vec3d velocity = new Vec3d(0.0D, -0.4D, 0.0D);
        PlaneCrossing crossing = PlaneCrossing.create(source.getFrame(), source.getOrigin(),
            new PlaneCrossing.Motion(start, end, velocity, new Vec3d(0.0D, 0.0D, 1.0D)));
        return new Traversive(player, TraversableType.PLAYER, crossing.frame(), BukkitGeometry.bukkit(crossing.origin()), BukkitGeometry.bukkit(end),
            BukkitGeometry.bukkit(velocity), new Vector(0.0D, 0.0D, 1.0D), crossing.frontSide(), source.getId(), new Angles.Look(0.0F, 0.0F));
    }

    private static BoundingBox body() {
        return new BoundingBox(1.2D, 64.45D, 1.2D, 1.8D, 64.45D + HEIGHT, 1.8D);
    }
}
