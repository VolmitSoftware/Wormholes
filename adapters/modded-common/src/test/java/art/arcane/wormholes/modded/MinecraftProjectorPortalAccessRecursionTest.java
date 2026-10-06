package art.arcane.wormholes.modded;

import art.arcane.optics.math.Vec3;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.rtp.MinecraftRtpRuntime;
import art.arcane.optics.recursion.RecursiveEndpoints;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftProjectorPortalAccessRecursionTest extends MinecraftTestBase {
    private static final double EYE_X = 1.0D;
    private static final double EYE_Y = 65.0D;
    private static final double EYE_Z = -5.0D;

    private MinecraftRtpRuntime rtp;
    private ServerPlayer observer;
    private ServerLevel world;
    private MinecraftPortal front;
    private MinecraftPortal random;
    private MinecraftPortal route;
    private MinecraftProjectorPortalAccess access;

    @Before
    public void fixture() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        MinecraftPortalRegistry registry = mock(MinecraftPortalRegistry.class);
        rtp = mock(MinecraftRtpRuntime.class);
        observer = mock(ServerPlayer.class);
        world = mock(ServerLevel.class);
        front = portal(PortalType.PORTAL, Frame.canonical(Face.S), 0.0D);
        MinecraftPortal back = portal(PortalType.PORTAL, Frame.canonical(Face.N), 4.0D);
        random = portal(PortalType.RTP, Frame.canonical(Face.S), -2.0D);
        route = portal(PortalType.PORTAL, Frame.canonical(Face.N), 40.0D);
        UUID frontId = front.getId();
        UUID backId = back.getId();
        UUID randomId = random.getId();
        when(front.getTunnelType()).thenReturn("LOCAL");
        when(front.getDestinationId()).thenReturn(backId);
        when(back.getTunnelType()).thenReturn("LOCAL");
        when(back.getDestinationId()).thenReturn(frontId);
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(MinecraftTestSettings.defaults());
        when(runtime.portals()).thenReturn(registry);
        when(runtime.rtp()).thenReturn(rtp);
        when(registry.snapshot()).thenReturn(List.of(front, back, random));
        when(registry.get(frontId)).thenReturn(front);
        when(registry.get(backId)).thenReturn(back);
        when(registry.get(randomId)).thenReturn(random);
        when(registry.resolveLevel(any())).thenReturn(world);
        when(rtp.knownDestination(observer, random)).thenReturn(route);
        when(rtp.projectionDestination(observer, random)).thenReturn(route);
        access = new MinecraftProjectorPortalAccess(runtime);
        access.observer(observer);
    }

    @Test
    public void revalidateReadsKnownRandomDestinationsWithoutAttendingThem() {
        RecursiveEndpoints<ServerLevel, MinecraftPortal> recursive = access.createRecursiveIndex();

        recursive.revalidate();
        recursive.indexFor(world, EYE_X, EYE_Y, EYE_Z, front);
        recursive.revalidate();
        recursive.indexFor(world, EYE_X, EYE_Y, EYE_Z, front);

        verify(rtp, never()).projectionDestination(any(), any());
        verify(rtp, atLeastOnce()).knownDestination(observer, random);
    }

    @Test
    public void revalidateKeepsTheIndexWhileNoPortalChanged() {
        RecursiveEndpoints<ServerLevel, MinecraftPortal> recursive = access.createRecursiveIndex();
        recursive.revalidate();
        RecursiveEndpoints<ServerLevel, MinecraftPortal>.Index first = recursive.indexFor(world, EYE_X, EYE_Y, EYE_Z, front);

        recursive.revalidate();

        assertSame(first, recursive.indexFor(world, EYE_X, EYE_Y, EYE_Z, front));
    }

    @Test
    public void revalidateRebuildsTheIndexWhenTheKnownRouteChanges() {
        RecursiveEndpoints<ServerLevel, MinecraftPortal> recursive = access.createRecursiveIndex();
        recursive.revalidate();
        RecursiveEndpoints<ServerLevel, MinecraftPortal>.Index first = recursive.indexFor(world, EYE_X, EYE_Y, EYE_Z, front);

        when(rtp.knownDestination(observer, random)).thenReturn(null);
        recursive.revalidate();

        assertNotSame(first, recursive.indexFor(world, EYE_X, EYE_Y, EYE_Z, front));
    }

    @Test
    public void hasDestinationAttendsTheRandomPortalOnce() {
        assertTrue(access.hasDestination(random));

        verify(rtp, times(1)).projectionDestination(observer, random);
        verify(rtp, never()).knownDestination(any(), any());
    }

    @Test
    public void randomDestinationsNeedAnObserver() {
        MinecraftProjectorPortalAccess unobserved = new MinecraftProjectorPortalAccess(mock(WormholesModRuntime.class));

        assertNull(unobserved.destination(random));
        assertNull(unobserved.projectionDestination(random));
    }

    private static MinecraftPortal portal(PortalType type, Frame frame, double planeZ) {
        MinecraftPortal portal = mock(MinecraftPortal.class);
        ApertureCells geometry = new ApertureCells();
        geometry.setArea(new Box(0.0D, 1.999D, 64.0D, 65.999D, planeZ, planeZ + 0.999D));
        when(portal.getId()).thenReturn(UUID.randomUUID());
        when(portal.getType()).thenReturn(type);
        when(portal.getGeometry()).thenReturn(geometry);
        when(portal.getFrame()).thenReturn(frame);
        when(portal.getOrigin()).thenReturn(new Vec3(1.0D, 65.0D, planeZ + 0.5D));
        when(portal.isOpen()).thenReturn(true);
        when(portal.getProjectionMode()).thenReturn(ProjectionMode.ON);
        when(portal.getTunnelType()).thenReturn("");
        return portal;
    }
}
