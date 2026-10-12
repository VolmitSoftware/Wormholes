package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.ClientViewSession;
import art.arcane.wormholes.modded.client.ResidentLevels;
import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public final class NativeMirrorHistoryScene implements AutoCloseable {
    public final ClientLevel level = mock(ClientLevel.class);
    public final PortalViews views = new PortalViews(mock(ResidentLevels.class));
    public final ClientViewSession session = mock(ClientViewSession.class);
    public final Map<Integer, EnvironmentState> environments = new HashMap<>();
    public final List<ClientPortal> roots = new ArrayList<>(3);
    public final List<ClientPortal> nested = new ArrayList<>(4);

    private final MockedStatic<PortalBackends> backends = mockStatic(PortalBackends.class);
    private final MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class);

    public NativeMirrorHistoryScene() {
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        WormholesClient client = mock(WormholesClient.class);
        when(client.session()).thenReturn(session);
        when(session.matchesSource(anyInt(), same(level))).thenReturn(true);
        when(session.environment(anyInt())).thenAnswer(invocation -> environments.get(invocation.getArgument(0, Integer.class)));
        clients.when(WormholesClient::instance).thenReturn(client);
        backends.when(PortalBackends::pipeline).thenReturn(PipelineBackend.NONE);
        slots(0, 1);
    }

    public void slots(int offset, int revision) {
        roots.clear();
        nested.clear();
        environments.clear();
        roots.add(new ClientPortal(offset + 1, geometry(40, 20, Face.N, 3, 0, 0L), revision, 0.0D));
        roots.add(new ClientPortal(offset + 2, geometry(46, 23, Face.N, 3, 0, 0L), revision, 0.0D));
        roots.add(new ClientPortal(offset + 3, geometry(40, 44, Face.S, 33, 0, 0L), revision, 0.0D));
        nested.add(new ClientPortal(offset + 4, geometry(40, 44, Face.S, 33, offset + 1, 101L), revision, 0.0D));
        nested.add(new ClientPortal(offset + 5, geometry(40, 44, Face.S, 33, offset + 2, 101L), revision, 0.0D));
        nested.add(new ClientPortal(offset + 6, geometry(40, 20, Face.N, 3, offset + 4, 102L), revision, 0.0D));
        nested.add(new ClientPortal(offset + 7, geometry(46, 23, Face.N, 3, offset + 4, 103L), revision, 0.0D));
    }

    public void frame(int announced) {
        List<ClientPortal> portals = new ArrayList<>(roots);
        for (int index = 0; index < announced; index++) {
            ClientPortal portal = nested.get(index);
            environments.put(portal.portalKey(), environment(portal.geometry(), "minecraft:overworld"));
            portals.add(portal);
        }
        views.frame(level, new Vec3d(41.5D, 71.5D, 26.5D), List.of(), true, true, portals, null);
    }

    public List<List<PortalView>> traversals() {
        PortalView a = views.current().get(0);
        PortalView b = views.current().get(1);
        PortalView n = views.current().get(2);
        PortalView an = views.children(a).isEmpty() ? n : views.children(a).getFirst();
        PortalView bn = views.children(b).isEmpty() ? n : views.children(b).getFirst();
        List<PortalView> grandchildren = views.children(an);
        PortalView ana = grandchildren.isEmpty() ? a : grandchildren.getFirst();
        PortalView anb = grandchildren.size() < 2 ? b : grandchildren.get(1);
        return List.of(List.of(a), List.of(b), List.of(a, an), List.of(b, bn), List.of(a, an, ana), List.of(a, an, anb));
    }

    public static EnvironmentState environment(ApertureDescriptor geometry, String dimension) {
        EnvironmentState environment = mock(EnvironmentState.class);
        EnvironmentState.World world = mock(EnvironmentState.World.class);
        when(world.dimensionKey()).thenReturn(dimension);
        when(environment.world()).thenReturn(world);
        when(environment.transform()).thenReturn(PortalSurface.mirrorTransform(geometry).inverse());
        when(environment.scale()).thenReturn(1.0F);
        return environment;
    }

    public static ApertureDescriptor geometry(int x, int z, Face face, int width, int parent, long target) {
        long[] mask = width == 33 ? new long[]{-1L, (1L << 35) - 1L} : new long[]{511L};
        return new ApertureDescriptor(x, 70, z, face.ordinal(), false, 0, true, width, 3, mask, ShapeDescriptor.FULL,
            0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0, 0.0D, parent, target, List.of());
    }

    @Override
    public void close() {
        views.clear();
        clients.close();
        backends.close();
    }
}
