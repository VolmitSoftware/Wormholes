package art.arcane.wormholes.portal.rtp;

import art.arcane.optics.math.Vec3;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftRtpPlateIdentityTest {
    private static final UUID PORTAL = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Test
    public void oneRouteYieldsOneIdentityForEveryViewer() {
        MinecraftPortal source = source();
        RtpProjectionView.ReadyData ready = ready(new RtpDestination("minecraft:overworld", 300, 64, 300, 1L, 0), 2L);
        long identity = MinecraftRtpRuntime.plateIdentity(source, ready);
        assertNotEquals(0L, identity);
        assertEquals(identity, MinecraftRtpRuntime.plateIdentity(source, ready(new RtpDestination("minecraft:overworld", 300, 64, 300, 1L, 0), 2L)));
    }

    @Test
    public void routeRotationAndDestinationChangesRetireTheIdentity() {
        MinecraftPortal source = source();
        long base = MinecraftRtpRuntime.plateIdentity(source, ready(new RtpDestination("minecraft:overworld", 300, 64, 300, 1L, 0), 2L));
        assertNotEquals(base, MinecraftRtpRuntime.plateIdentity(source, ready(new RtpDestination("minecraft:overworld", 300, 64, 300, 1L, 0), 3L)));
        assertNotEquals(base, MinecraftRtpRuntime.plateIdentity(source, ready(new RtpDestination("minecraft:overworld", 301, 64, 300, 1L, 0), 2L)));
        assertNotEquals(base, MinecraftRtpRuntime.plateIdentity(source, ready(new RtpDestination("minecraft:the_nether", 300, 64, 300, 1L, 0), 2L)));
    }

    private static MinecraftPortal source() {
        MinecraftPortal portal = mock(MinecraftPortal.class);
        when(portal.getFrame()).thenReturn(Frame.canonical(Face.S));
        return portal;
    }

    private static RtpProjectionView.ReadyData ready(RtpDestination destination, long routeRevision) {
        return RtpProjectionGeometry.create(new RtpProjectionGeometry.Source(PORTAL, "minecraft:overworld",
            new Vec3(1.5D, 65.0D, 0.5D), Frame.canonical(Face.S), new Box(0, 3, 64, 67, 0, 1), 1L),
            destination, routeRevision);
    }
}
