package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.TraversableType;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.util.Direction;

/**
 * Expected vectors are computed by hand from the canonical triads:
 * N(normal N, up U, right E), S(S, U, W), E(E, U, S), W(W, U, N), U(U, up S, right E), D(D, up N, right E).
 * The entry look (0, 0.6, -0.8) through canonical(N) has components right 0, up 0.6, normal 0.8.
 */
final class OrientationTransformTest {
    private static final double EPSILON = 1e-9D;
    private static final Vector ENTRY_LOOK = new Vector(0.0D, 0.6D, -0.8D);

    private static final Map<Direction, Vector> FRAME = Map.of(
        Direction.N, new Vector(0.0D, 0.6D, -0.8D),
        Direction.S, new Vector(0.0D, 0.6D, 0.8D),
        Direction.E, new Vector(0.8D, 0.6D, 0.0D),
        Direction.W, new Vector(-0.8D, 0.6D, 0.0D),
        Direction.U, new Vector(0.0D, 0.8D, 0.6D),
        Direction.D, new Vector(0.0D, -0.8D, -0.6D));
    private static final Map<Direction, Vector> SNAP = Map.of(
        Direction.N, new Vector(0.0D, 0.0D, 1.0D),
        Direction.S, new Vector(0.0D, 0.0D, -1.0D),
        Direction.E, new Vector(-1.0D, 0.0D, 0.0D),
        Direction.W, new Vector(1.0D, 0.0D, 0.0D),
        Direction.U, new Vector(0.0D, -1.0D, 0.0D),
        Direction.D, new Vector(0.0D, 1.0D, 0.0D));
    private static final Map<Direction, Vector> MIRROR = Map.of(
        Direction.N, new Vector(0.0D, 0.6D, 0.8D),
        Direction.S, new Vector(0.0D, 0.6D, -0.8D),
        Direction.E, new Vector(-0.8D, 0.6D, 0.0D),
        Direction.W, new Vector(0.8D, 0.6D, 0.0D),
        Direction.U, new Vector(0.0D, -0.8D, 0.6D),
        Direction.D, new Vector(0.0D, 0.8D, -0.6D));

    @Test
    void everyPolicyAgainstEveryExitNormalWithoutGravityFlip() {
        Traversive traversive = entering(Direction.N, ENTRY_LOOK, true);
        for (Direction exit : Direction.values()) {
            PortalFrame outFrame = PortalFrame.canonical(exit);
            assertVector(FRAME.get(exit), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.FRAME, false), "FRAME " + exit);
            assertVector(ENTRY_LOOK, OrientationTransform.direction(traversive, outFrame, OrientationPolicy.LOOK, false), "LOOK " + exit);
            assertVector(SNAP.get(exit), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.SNAP, false), "SNAP " + exit);
            assertVector(MIRROR.get(exit), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.MIRROR, false), "MIRROR " + exit);
        }
    }

    @Test
    void frameMatchesTodaysOutLookExactly() {
        Traversive traversive = entering(Direction.N, ENTRY_LOOK, true);
        for (Direction exit : Direction.values()) {
            PortalFrame outFrame = PortalFrame.canonical(exit);
            assertVector(traversive.getOutLook(outFrame), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.FRAME, false), "FRAME " + exit);
        }
    }

    @Test
    void gravityFlipOnlyTouchesVerticalExits() {
        Traversive traversive = entering(Direction.N, ENTRY_LOOK, true);
        for (Direction exit : new Direction[] {Direction.N, Direction.S, Direction.E, Direction.W}) {
            PortalFrame outFrame = PortalFrame.canonical(exit);
            assertVector(FRAME.get(exit), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.FRAME, true), "FRAME flip " + exit);
            assertVector(MIRROR.get(exit), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.MIRROR, true), "MIRROR flip " + exit);
        }
    }

    @Test
    void gravityFlipRestoresAnUprightLookWhenAWallLeadsIntoAFloorOrCeiling() {
        Traversive traversive = entering(Direction.N, ENTRY_LOOK, true);
        assertVector(new Vector(0.0D, 0.6D, -0.8D), OrientationTransform.direction(traversive, PortalFrame.canonical(Direction.U), OrientationPolicy.FRAME, true), "floor");
        assertVector(new Vector(0.0D, 0.6D, -0.8D), OrientationTransform.direction(traversive, PortalFrame.canonical(Direction.D), OrientationPolicy.FRAME, true), "ceiling");

        Traversive sideways = entering(Direction.N, new Vector(0.6D, 0.0D, -0.8D), true);
        assertVector(new Vector(0.6D, 0.0D, -0.8D), OrientationTransform.direction(sideways, PortalFrame.canonical(Direction.U), OrientationPolicy.FRAME, true), "floor sideways");
        assertVector(new Vector(0.6D, 0.0D, -0.8D), OrientationTransform.direction(sideways, PortalFrame.canonical(Direction.D), OrientationPolicy.FRAME, true), "ceiling sideways");
    }

    @Test
    void gravityFlipTurnsAFallIntoAHorizontalHeadingOutOfACeiling() {
        Traversive falling = entering(Direction.U, new Vector(0.0D, -1.0D, 0.0D), true);
        assertVector(new Vector(0.0D, 1.0D, 0.0D), OrientationTransform.direction(falling, PortalFrame.canonical(Direction.D), OrientationPolicy.FRAME, false), "no flip");
        assertVector(new Vector(0.0D, 0.0D, 1.0D), OrientationTransform.direction(falling, PortalFrame.canonical(Direction.D), OrientationPolicy.FRAME, true), "flip");
        assertVector(new Vector(0.0D, 0.0D, 1.0D), OrientationTransform.direction(falling, PortalFrame.canonical(Direction.N), OrientationPolicy.FRAME, true), "wall exit is untouched");
    }

    @Test
    void gravityFlipLeavesAbsoluteLookAloneAndRotatesSnap() {
        Traversive traversive = entering(Direction.N, ENTRY_LOOK, true);
        assertVector(ENTRY_LOOK, OrientationTransform.direction(traversive, PortalFrame.canonical(Direction.U), OrientationPolicy.LOOK, true), "LOOK flip");
        assertVector(new Vector(0.0D, 0.0D, 1.0D), OrientationTransform.direction(traversive, PortalFrame.canonical(Direction.U), OrientationPolicy.SNAP, true), "SNAP flip floor");
    }

    @Test
    void backSideEntryUsesTheFlippedFrames() {
        Traversive traversive = entering(Direction.N, ENTRY_LOOK, false);
        PortalFrame outFrame = PortalFrame.canonical(Direction.E);
        assertVector(new Vector(0.8D, 0.6D, 0.0D), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.FRAME, false), "FRAME back");
        assertVector(new Vector(1.0D, 0.0D, 0.0D), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.SNAP, false), "SNAP back");
    }

    @Test
    void lookConvertsDirectionsToBukkitYawAndPitch() {
        assertLook(0.0F, 0.0F, OrientationTransform.Look.of(new Vector(0.0D, 0.0D, 1.0D)));
        assertLook(90.0F, 0.0F, OrientationTransform.Look.of(new Vector(-1.0D, 0.0D, 0.0D)));
        assertLook(180.0F, 0.0F, OrientationTransform.Look.of(new Vector(0.0D, 0.0D, -1.0D)));
        assertLook(270.0F, 0.0F, OrientationTransform.Look.of(new Vector(1.0D, 0.0D, 0.0D)));
        assertLook(0.0F, -90.0F, OrientationTransform.Look.of(new Vector(0.0D, 1.0D, 0.0D)));
        assertLook(0.0F, 90.0F, OrientationTransform.Look.of(new Vector(0.0D, -1.0D, 0.0D)));
        assertLook(0.0F, -36.869896F, OrientationTransform.Look.of(new Vector(0.0D, 0.6D, 0.8D)));

        Traversive traversive = entering(Direction.N, ENTRY_LOOK, true);
        OrientationTransform.Look snapped = OrientationTransform.apply(traversive, PortalFrame.canonical(Direction.N), OrientationPolicy.SNAP, false);
        assertLook(0.0F, 0.0F, snapped);
    }

    private static Traversive entering(Direction normal, Vector look, boolean frontSide) {
        PortalFrame inFrame = PortalFrame.canonical(normal).view(frontSide);
        return new Traversive(new Object(), TraversableType.ENTITY, inFrame, new Vector(10.0D, 64.0D, 20.0D),
            new Vector(10.5D, 64.5D, 20.0D), new Vector(0.0D, 0.0D, -0.4D), look, frontSide);
    }

    private static void assertLook(float yaw, float pitch, OrientationTransform.Look look) {
        assertEquals(yaw, look.yaw(), 1e-4F, "yaw");
        assertEquals(pitch, look.pitch(), 1e-4F, "pitch");
    }

    private static void assertVector(Vector expected, Vector actual, String label) {
        assertEquals(expected.getX(), actual.getX(), EPSILON, label + " x");
        assertEquals(expected.getY(), actual.getY(), EPSILON, label + " y");
        assertEquals(expected.getZ(), actual.getZ(), EPSILON, label + " z");
    }
}
