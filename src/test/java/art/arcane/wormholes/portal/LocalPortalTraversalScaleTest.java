package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.optics.aperture.SizeRatio;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.access.AccessTestPortals;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.transit.TransitPortalExtension;
import art.arcane.wormholes.util.BukkitGeometry;
import art.arcane.wormholes.util.Cuboid;

final class LocalPortalTraversalScaleTest {
    private PortalManager previous;
    private LocalPortal small;
    private LocalPortal large;

    @BeforeEach
    void portals() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(TransitPortalExtension.class, extensionPortal -> new TransitPortalExtension()));
        World world = AccessTestPortals.world("scale");
        small = wall(world, 0, 2);
        large = wall(world, 100, 108);
        PortalManager manager = mock(PortalManager.class);
        when(manager.getLocalPortal(small.getId())).thenReturn(small);
        when(manager.getLocalPortal(large.getId())).thenReturn(large);
        previous = Wormholes.portalManager;
        Wormholes.portalManager = manager;
    }

    @AfterEach
    void restore() {
        Wormholes.portalManager = previous;
        WormholesHooks.clear();
    }

    @Test
    void offKeepsTheRigidArrivalBitForBit() {
        Traversive crossing = crossing(small, new Vector(1.0D, 0.5D, 0.0D), new Vector(0.1D, 0.0D, -0.4D));

        LocalPortalTraversal.ExitPlacement placement = large.traversal().exitPlacement(crossing);
        Vector rigid = crossing.getOutPoint(large.getFrame(), BukkitGeometry.bukkit(large.getOrigin()));

        assertEquals(rigid.getX(), placement.exit().getX(), 0.0D);
        assertEquals(rigid.getY(), placement.exit().getY(), 0.0D);
        assertEquals(rigid.getZ(), placement.exit().getZ(), 0.0D);
        assertEquals(crossing.getOutVelocity(large.getFrame()), placement.outVelocity());
        assertEquals(ScaleRule.OFF.mode(), placement.scale().mode());
    }

    @Test
    void motionMapsTheOffsetAndVelocityByTheSizeRatio() {
        small.extension(TransitPortalExtension.class).setScaleRule(ScaleRule.motion());
        Vector offset = new Vector(1.0D, 0.5D, 0.0D);
        Traversive crossing = crossing(small, offset, new Vector(0.1D, 0.0D, -0.4D));

        LocalPortalTraversal.ExitPlacement placement = large.traversal().exitPlacement(crossing);
        Vector rigid = crossing.getOutPoint(large.getFrame(), BukkitGeometry.bukkit(large.getOrigin()));
        Vec3d center = large.getOrigin();

        assertEquals(new SizeRatio(3.0D, true), placement.ratio());
        assertEquals(center.x() + 3.0D * (rigid.getX() - center.x()), placement.exit().getX(), 1.0E-9D);
        assertEquals(center.y() + 3.0D * (rigid.getY() - center.y()), placement.exit().getY(), 1.0E-9D);
        assertEquals(center.z() + 3.0D * (rigid.getZ() - center.z()), placement.exit().getZ(), 1.0E-9D);
        Vector velocity = crossing.getOutVelocity(large.getFrame()).multiply(3.0D);
        assertEquals(velocity.getX(), placement.outVelocity().getX(), 1.0E-9D);
        assertEquals(velocity.getY(), placement.outVelocity().getY(), 1.0E-9D);
        assertEquals(velocity.getZ(), placement.outVelocity().getZ(), 1.0E-9D);
    }

    @Test
    void ratioCarriesTheRuleAndTheReturnTripUsesTheInverseRatio() {
        ScaleRule rule = ScaleRule.ratio(0.25D, 4.0D);
        small.extension(TransitPortalExtension.class).setScaleRule(rule);
        large.extension(TransitPortalExtension.class).setScaleRule(rule);

        LocalPortalTraversal.ExitPlacement out = large.traversal().exitPlacement(crossing(small, new Vector(0.0D, 0.0D, 0.0D), new Vector(0.0D, 0.0D, -0.4D)));
        LocalPortalTraversal.ExitPlacement back = small.traversal().exitPlacement(crossing(large, new Vector(3.0D, 0.0D, 0.0D), new Vector(0.0D, 0.0D, -1.2D)));

        assertEquals(rule, out.scale());
        assertEquals(3.0D, out.ratio().ratio(), 1.0E-12D);
        assertEquals(1.0D / 3.0D, back.ratio().ratio(), 1.0E-12D);
        assertEquals(0.4D, back.outVelocity().length(), 1.0E-9D);
        Location returned = back.exit();
        assertEquals(1.0D, Math.abs(returned.getX() - small.getOrigin().x()), 1.0E-9D);
    }

    private static LocalPortal wall(World world, int fromX, int toX) {
        PortalStructure structure = new PortalStructure();
        structure.setWorld(world);
        int height = toX - fromX;
        structure.setArea(new Cuboid(new Location(world, fromX, 64.0D, 0.0D), new Location(world, toX, 64.0D + height, 0.0D)));
        LocalPortal portal = new LocalPortal(UUID.randomUUID(), PortalType.PORTAL, structure);
        portal.setAmbientAttended(false);
        portal.setFrame(Frame.canonical(Face.N));
        return portal;
    }

    private static Traversive crossing(LocalPortal portal, Vector offset, Vector velocity) {
        Vector origin = BukkitGeometry.bukkit(portal.getOrigin());
        Vector point = origin.clone().add(offset);
        Vec3d look = Angles.direction(180.0F, 0.0F);
        return new Traversive(null, TraversableType.PLAYER, portal.getFrame().view(true), origin, point, velocity,
            new Vector(look.x(), look.y(), look.z()), true, portal.getId(), new Angles.Look(180.0F, 0.0F));
    }
}
