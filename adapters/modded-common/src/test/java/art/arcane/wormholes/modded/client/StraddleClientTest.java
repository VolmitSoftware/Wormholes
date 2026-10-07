package art.arcane.wormholes.modded.client;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.mixin.client.StraddleLocalPlayerMixin;
import art.arcane.wormholes.modded.mixin.client.StraddleOverlayMixin;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class StraddleClientTest extends MinecraftTestBase {
    private static final ApertureDescriptor GEOMETRY = ClientTravelTestFixtures.geometry();

    @Test
    public void aBoxReachingThePlaneInsideTheApertureWithTheEyeInFrontStraddles() {
        double plane = GEOMETRY.planeCoordinate();
        double front = frontOffset();
        Vec3d position = new Vec3d(1.0, 0.0, plane + front * 0.2);
        assertNotNull(straddle(position, position, new Vec3d(0, 0, 0), plane + front * 0.2));
        assertNull(straddle(position, position, new Vec3d(0, 0, 0), plane - front * 0.2));
        Vec3d lateral = new Vec3d(6.0, 0.0, plane + front * 0.2);
        assertNull(straddle(lateral, lateral, new Vec3d(0, 0, 0), plane + front * 0.2));
        Vec3d away = new Vec3d(1.0, 0.0, plane + front * 1.5);
        assertNull(straddle(away, away, new Vec3d(0, 0, 0), plane + front * 1.5));
    }

    @Test
    public void velocityAndThePreviousTickStretchTheRegisteredBox() {
        double plane = GEOMETRY.planeCoordinate();
        double front = frontOffset();
        Vec3d approaching = new Vec3d(1.0, 0.0, plane + front * 1.2);
        assertNull(straddle(approaching, approaching, new Vec3d(0, 0, 0), plane + front * 1.2));
        assertNotNull(straddle(approaching, approaching, new Vec3d(0, 0, -front * 0.8), plane + front * 1.2));
        Vec3d current = new Vec3d(1.0, 0.0, plane + front * 0.4);
        assertNull(straddle(current, current, new Vec3d(0, 0, 0), plane + front * 0.4));
        Vec3d before = new Vec3d(1.0, 0.0, plane - front * 0.1);
        assertNotNull(straddle(current, before, new Vec3d(0, 0, 0), plane + front * 0.4));
    }

    @Test
    public void blocksBehindThePlaneStopSuffocatingAndHidingTheViewWhileStraddling() throws ReflectiveOperationException {
        double plane = GEOMETRY.planeCoordinate();
        double front = frontOffset();
        Vec3d position = new Vec3d(1.0, 0.0, plane + front * 0.2);
        ClientStraddle straddle = straddle(position, position, new Vec3d(0, 0, 0), plane + front * 0.2);
        BlockPos behind = BlockPos.containing(1.0, 1.0, plane - front * 0.5);
        BlockPos inFront = BlockPos.containing(1.0, 1.0, plane + front * 0.5);
        assertTrue(straddle.behind(behind.getX() + 0.5, behind.getY() + 0.5, behind.getZ() + 0.5));
        assertFalse(straddle.behind(inFront.getX() + 0.5, inFront.getY() + 0.5, inFront.getZ() + 0.5));
        ClientPreparedTravel travel = mock(ClientPreparedTravel.class);
        WormholesClient client = mock(WormholesClient.class);
        when(client.preparedTravel()).thenReturn(travel);
        BlockState stone = Blocks.STONE.defaultBlockState();
        try (MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            clients.when(WormholesClient::instance).thenReturn(client);
            when(travel.straddle()).thenReturn(straddle);
            assertFalse(suffocates(true, behind));
            assertTrue(suffocates(true, inFront));
            assertFalse(suffocates(false, inFront));
            assertNull(overlay(stone));
            when(travel.straddle()).thenReturn(null);
            assertTrue(suffocates(true, behind));
            assertSame(stone, overlay(stone));
        }
    }

    private static ClientStraddle straddle(Vec3d position, Vec3d previous, Vec3d velocity, double eyeAlongNormal) {
        Box box = new Box(position.x() - 0.3, position.x() + 0.3, position.y(), position.y() + 1.8, position.z() - 0.3, position.z() + 0.3);
        return ClientStraddle.of(GEOMETRY, box, previous, position, velocity, new Vec3d(position.x(), position.y() + 1.62, eyeAlongNormal));
    }

    private static double frontOffset() {
        double plane = GEOMETRY.planeCoordinate();
        return GEOMETRY.signedDistance(0.5, 1.0, plane + 1.0) * (GEOMETRY.frontSide() ? 1 : -1) > 0 ? 1.0 : -1.0;
    }

    private static boolean suffocates(boolean vanilla, BlockPos position) throws ReflectiveOperationException {
        StraddleLocalPlayerMixin mixin = mock(StraddleLocalPlayerMixin.class, CALLS_REAL_METHODS);
        Method method = StraddleLocalPlayerMixin.class.getDeclaredMethod("wormholes$straddleSuffocation", boolean.class, BlockPos.class);
        method.setAccessible(true);
        return (boolean) method.invoke(mixin, vanilla, position);
    }

    private static BlockState overlay(BlockState state) throws ReflectiveOperationException {
        Method method = StraddleOverlayMixin.class.getDeclaredMethod("wormholes$straddleOverlay", BlockState.class);
        method.setAccessible(true);
        return (BlockState) method.invoke(null, state);
    }
}
