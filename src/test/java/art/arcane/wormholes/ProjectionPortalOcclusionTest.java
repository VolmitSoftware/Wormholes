package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.Axis;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

final class ProjectionPortalOcclusionTest {
    @Test
    void nearerApertureFullyCoversAlignedFartherPortal() {
        AxisAlignedBB nearer = new AxisAlignedBB(-3.0D, 3.0D, 61.0D, 69.0D, 5.0D, 5.0D);
        AxisAlignedBB farther = new AxisAlignedBB(-2.0D, 2.0D, 62.0D, 68.0D, 10.0D, 10.0D);

        assertTrue(ProjectionPortalOcclusion.fullyOccludes(
            0.0D, 65.0D, 0.0D, nearer, Axis.Z, farther));
    }

    @Test
    void uncoveredFartherCornerKeepsPortalVisible() {
        AxisAlignedBB nearer = new AxisAlignedBB(-1.0D, 1.0D, 63.0D, 67.0D, 5.0D, 5.0D);
        AxisAlignedBB farther = new AxisAlignedBB(-3.0D, 3.0D, 61.0D, 69.0D, 10.0D, 10.0D);

        assertFalse(ProjectionPortalOcclusion.fullyOccludes(
            0.0D, 65.0D, 0.0D, nearer, Axis.Z, farther));
    }

    @Test
    void offsetPortalWithVisibleEdgeRemainsProjected() {
        AxisAlignedBB nearer = new AxisAlignedBB(-3.0D, 3.0D, 61.0D, 69.0D, 5.0D, 5.0D);
        AxisAlignedBB farther = new AxisAlignedBB(4.0D, 8.0D, 62.0D, 68.0D, 10.0D, 10.0D);

        assertFalse(ProjectionPortalOcclusion.fullyOccludes(
            0.0D, 65.0D, 0.0D, nearer, Axis.Z, farther));
    }

    @Test
    void portalInFrontOfCandidateCannotOccludeIt() {
        AxisAlignedBB claimedNearer = new AxisAlignedBB(-3.0D, 3.0D, 61.0D, 69.0D, 10.0D, 10.0D);
        AxisAlignedBB actualNearer = new AxisAlignedBB(-2.0D, 2.0D, 62.0D, 68.0D, 5.0D, 5.0D);

        assertFalse(ProjectionPortalOcclusion.fullyOccludes(
            0.0D, 65.0D, 0.0D, claimedNearer, Axis.Z, actualNearer));
    }

    @Test
    void obliqueFullCoverageIsRecognized() {
        AxisAlignedBB nearer = new AxisAlignedBB(2.0D, 2.0D, 61.0D, 69.0D, -3.0D, 3.0D);
        AxisAlignedBB farther = new AxisAlignedBB(5.0D, 5.0D, 63.0D, 67.0D, -1.0D, 1.0D);

        assertTrue(ProjectionPortalOcclusion.fullyOccludes(
            0.0D, 65.0D, 0.0D, nearer, Axis.X, farther));
    }

    @Test
    void eyeInsideNearPortalPlaneFailsOpen() {
        AxisAlignedBB nearer = new AxisAlignedBB(-3.0D, 3.0D, 61.0D, 69.0D, 0.0D, 1.0D);
        AxisAlignedBB farther = new AxisAlignedBB(-2.0D, 2.0D, 62.0D, 68.0D, 10.0D, 10.0D);

        assertFalse(ProjectionPortalOcclusion.fullyOccludes(
            0.0D, 65.0D, 0.5D, nearer, Axis.Z, farther));
    }

    @Test
    void fullyCoveredPortalIsRejectedByNearerFrontToBackList() {
        World world = world();
        ILocalPortal nearer = portal(world,
            new AxisAlignedBB(-3.0D, 3.0D, 61.0D, 69.0D, 5.0D, 5.0D), true);
        ILocalPortal farther = portal(world,
            new AxisAlignedBB(-2.0D, 2.0D, 62.0D, 68.0D, 10.0D, 10.0D), true);
        assertTrue(ProjectionPortalOcclusion.isFullyOccluded(
            new Location(world, 0.0D, 65.0D, 0.0D), List.of(nearer), farther));
    }

    @Test
    void irregularNearPortalFailsOpen() {
        World world = world();
        ILocalPortal nearer = portal(world,
            new AxisAlignedBB(-3.0D, 3.0D, 61.0D, 69.0D, 5.0D, 5.0D), false);
        ILocalPortal farther = portal(world,
            new AxisAlignedBB(-2.0D, 2.0D, 62.0D, 68.0D, 10.0D, 10.0D), true);
        assertFalse(ProjectionPortalOcclusion.isFullyOccluded(
            new Location(world, 0.0D, 65.0D, 0.0D), List.of(nearer), farther));
    }

    private static World world() {
        InvocationHandler handler = (Object proxy, Method method, Object[] args) -> switch (method.getName()) {
            case "equals" -> Boolean.valueOf(proxy == args[0]);
            case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
            case "toString" -> "world";
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (World) Proxy.newProxyInstance(
            ProjectionPortalOcclusionTest.class.getClassLoader(), new Class<?>[] { World.class }, handler);
    }

    private static ILocalPortal portal(World world, AxisAlignedBB area, boolean fullCuboid) {
        PortalStructure structure = new TestPortalStructure(area, fullCuboid);
        UUID id = UUID.randomUUID();
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        InvocationHandler handler = (Object proxy, Method method, Object[] args) -> switch (method.getName()) {
            case "getWorld" -> world;
            case "getStructure" -> structure;
            case "getFrame" -> frame;
            case "getId" -> id;
            case "equals" -> Boolean.valueOf(proxy == args[0]);
            case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
            case "toString" -> id.toString();
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (ILocalPortal) Proxy.newProxyInstance(
            ProjectionPortalOcclusionTest.class.getClassLoader(), new Class<?>[] { ILocalPortal.class }, handler);
    }

    private static final class TestPortalStructure extends PortalStructure {
        private final AxisAlignedBB area;
        private final boolean fullCuboid;

        private TestPortalStructure(AxisAlignedBB area, boolean fullCuboid) {
            this.area = area;
            this.fullCuboid = fullCuboid;
        }

        @Override
        public AxisAlignedBB getArea() {
            return area;
        }

        @Override
        public boolean isFullCuboid() {
            return fullCuboid;
        }
    }
}
