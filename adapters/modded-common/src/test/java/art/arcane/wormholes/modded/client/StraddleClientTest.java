package art.arcane.wormholes.modded.client;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.mixin.client.StraddleOverlayMixin;
import art.arcane.wormholes.modded.seamless.StraddleHolder;
import art.arcane.wormholes.modded.seamless.StraddleTracker;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.core.BlockPos;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class StraddleClientTest extends MinecraftTestBase {
    private static final TravelMessage.TravelBegin BEGIN = SeamlessTravelFixtures.begin(true, true);
    private static final ApertureDescriptor GEOMETRY = BEGIN.sourceGeometry();

    @Test
    public void aStretchedBoxReachingTheApertureRegistersTheSharedStraddle() {
        ClientLevel destination = mock(ClientLevel.class);
        double plane = GEOMETRY.planeCoordinate();
        double front = front();
        StraddleTracker.Straddle straddle = ClientPreparedTravel.straddle(BEGIN, destination, box(1.0, plane + front * 0.2), eye(1.0, plane + front * 0.2));
        assertNotNull(straddle);
        assertSame(destination, straddle.destination());
        assertEquals(GEOMETRY.frontSide(), straddle.frontSide());
        assertEquals(BEGIN.destinationToSource().inverse().point(new Vec3d(0.5, 1.5, plane)), straddle.toward().point(new Vec3d(0.5, 1.5, plane)));
        assertNull(ClientPreparedTravel.straddle(BEGIN, destination, box(6.0, plane + front * 0.2), eye(6.0, plane + front * 0.2)));
        assertNull(ClientPreparedTravel.straddle(BEGIN, destination, box(1.0, plane + front * 3.0), eye(1.0, plane + front * 3.0)));
        Box approaching = StraddleTracker.stretched(box(1.0, plane + front * 1.2), new Vec3d(0, 0, -front * 0.8), new Vec3d(0, 0, 0));
        assertNotNull(ClientPreparedTravel.straddle(BEGIN, destination, approaching, eye(1.0, plane + front * 1.2)));
    }

    @Test
    public void theSlabExcludesOnlyShapesBehindTheSourcePlaneInsideTheApertureFootprint() {
        double plane = GEOMETRY.planeCoordinate();
        double front = front();
        StraddleTracker.Straddle straddle = ClientPreparedTravel.straddle(BEGIN, mock(ClientLevel.class), box(1.0, plane + front * 0.2),
            eye(1.0, plane + front * 0.2));
        double behindNear = Math.min(plane, plane - front);
        double behindFar = Math.max(plane, plane - front);
        assertTrue(straddle.excludes(0.0, 0.0, behindNear, 1.0, 1.0, behindFar));
        double frontNear = Math.min(plane, plane + front);
        double frontFar = Math.max(plane, plane + front);
        assertFalse(straddle.excludes(0.0, 0.0, frontNear, 1.0, 1.0, frontFar));
        assertFalse(straddle.excludes(8.0, 0.0, behindNear, 9.0, 1.0, behindFar));
    }

    @Test
    public void theReturnEndpointIsTheMappedSourceOpening() {
        StraddleTracker.Endpoint back = ClientPreparedTravel.destinationEndpoint(BEGIN);
        StraddleTracker.Endpoint source = ClientPreparedTravel.sourceEndpoint(BEGIN, GEOMETRY.aperture());
        assertEquals(BEGIN.destinationToSource().inverse().point(source.origin()), back.origin());
        assertEquals(GEOMETRY.apertureArea().center().add(new Vec3d(100, 64, 100)), back.aperture().getArea().center());
        assertEquals(GEOMETRY.frame(), back.frame());
    }

    @Test
    public void blocksBehindThePlaneStopSuffocatingAndHidingTheViewWhileStraddling() throws ReflectiveOperationException {
        double plane = GEOMETRY.planeCoordinate();
        double front = front();
        StraddleTracker.Straddle straddle = ClientPreparedTravel.straddle(BEGIN, mock(ClientLevel.class), box(1.0, plane + front * 0.2),
            eye(1.0, plane + front * 0.2));
        LocalPlayer player = mock(LocalPlayer.class, withSettings().extraInterfaces(StraddleHolder.class));
        when(((StraddleHolder) player).wormholesStraddle()).thenReturn(straddle);
        BlockPos behind = BlockPos.containing(1.0, 1.0, plane - front * 1.5);
        BlockPos inFront = BlockPos.containing(1.0, 1.0, plane + front * 1.5);
        assertFalse(suffocates(player, true, behind));
        assertTrue(suffocates(player, true, inFront));
        assertFalse(suffocates(player, false, inFront));
        Minecraft minecraft = mock(Minecraft.class);
        minecraft.player = player;
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            assertNull(overlay().blockOverlay);
            when(((StraddleHolder) player).wormholesStraddle()).thenReturn(null);
            assertNotNull(overlay().blockOverlay);
            assertTrue(suffocates(player, true, behind));
        }
    }

    private static double front() {
        double plane = GEOMETRY.planeCoordinate();
        return GEOMETRY.signedDistance(0.5, 1.0, plane + 1.0) * (GEOMETRY.frontSide() ? 1 : -1) > 0 ? 1.0 : -1.0;
    }

    private static Box box(double x, double z) {
        return new Box(x - 0.3, x + 0.3, 0.0, 1.8, z - 0.3, z + 0.3);
    }

    private static Vec3d eye(double x, double z) {
        return new Vec3d(x, 1.62, z);
    }

    private static boolean suffocates(LocalPlayer player, boolean vanilla, BlockPos position) {
        return ClientStraddles.suffocates(player, vanilla, position);
    }

    private static PlayerRenderState overlay() throws ReflectiveOperationException {
        PlayerRenderState rendered = new PlayerRenderState();
        rendered.blockOverlay = new PlayerRenderState.BlockOverlay(null, 0, 0, 1, 1);
        StraddleOverlayMixin mixin = mock(StraddleOverlayMixin.class, CALLS_REAL_METHODS);
        Method method = StraddleOverlayMixin.class.getDeclaredMethod("wormholes$straddleOverlay", Camera.class, DeltaTracker.class, float.class,
            PlayerRenderState.class, CallbackInfo.class);
        method.setAccessible(true);
        method.invoke(mixin, null, null, 0.0F, rendered, mock(CallbackInfo.class));
        return rendered;
    }
}
