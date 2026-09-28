package art.arcane.wormholes.modded;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.RemotePortalRegistry;
import art.arcane.wormholes.portal.Portal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.util.Direction;
import net.minecraft.server.MinecraftServer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftWormholesApiTest {
    @Test
    public void snapshotsLifecycleResolversMutationsAndCloseShareNativeState() throws Exception {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftPortalRegistry registry = mock(MinecraftPortalRegistry.class);
        MinecraftNetworkService service = mock(MinecraftNetworkService.class);
        NetworkManager network = mock(NetworkManager.class);
        MinecraftServer server = mock(MinecraftServer.class);
        MinecraftPortal source = portal("source");
        MinecraftPortal target = portal("target");
        List<MinecraftPortal> current = new ArrayList<>(List.of(source, target));
        when(runtime.server()).thenReturn(server);
        when(runtime.portals()).thenReturn(registry);
        when(runtime.network()).thenReturn(service);
        when(service.manager()).thenReturn(network);
        when(service.remotePortals()).thenReturn(new RemotePortalRegistry());
        when(network.getLocalName()).thenReturn("local");
        when(network.peerSnapshots()).thenReturn(List.of());
        when(registry.snapshot()).thenAnswer(invocation -> List.copyOf(current));
        when(registry.get(source.getId())).thenReturn(source);
        when(registry.get(target.getId())).thenReturn(target);
        List<Runnable> scheduled = new ArrayList<>();
        when(runtime.schedule(any(Runnable.class), anyLong())).thenAnswer(invocation -> scheduled.add(invocation.getArgument(0)));
        MinecraftWormholesApi api = new MinecraftWormholesApi(runtime);
        List<MinecraftWormholesApi.Event> events = new ArrayList<>();
        api.listen(events::add);
        api.start();
        assertEquals(2, api.portals().all().size());
        assertFalse(api.portals().byId(source.getId()).orElseThrow().listed());
        assertEquals(2L, events.stream().filter(event -> event.kind() == MinecraftWormholesApi.Kind.PORTAL_CREATED).count());
        assertEquals(api, MinecraftWormholesApi.forServer(server).orElseThrow());
        AutoCloseable resolver = api.registerDestinationResolver((portal, traveler) -> Optional.of(target.getId()));
        assertEquals(target.getId(), api.resolve(source, UUID.randomUUID()).portalId());
        resolver.close();
        assertFalse(api.hasResolvers());
        CompletableFuture<Boolean> linked = api.link(source.getId(), target.getId());
        assertFalse(linked.isDone());
        scheduled.removeFirst().run();
        assertTrue(linked.join());
        for (int tick = 0; tick < 20; tick++) { api.tick(); }
        assertEquals(target.getId(), api.portals().byId(source.getId()).orElseThrow().destinationId());
        assertTrue(events.stream().anyMatch(event -> event.kind() == MinecraftWormholesApi.Kind.PORTAL_LINKED));
        current.remove(target);
        for (int tick = 0; tick < 20; tick++) { api.tick(); }
        assertTrue(events.stream().anyMatch(event -> event.kind() == MinecraftWormholesApi.Kind.PORTAL_DESTROYED));
        CompletableFuture<Boolean> unfinished = api.rename(source.getId(), "later");
        api.close();
        assertTrue(unfinished.isCompletedExceptionally());
        assertTrue(MinecraftWormholesApi.forServer(server).isEmpty());
        assertTrue(api.portals().all().isEmpty());
    }

    private static MinecraftPortal portal(String name) {
        PortalGeometry geometry = new PortalGeometry();
        geometry.setBlocks(List.of(new GeometryVector(0, 64, 0), new GeometryVector(0, 65, 0)));
        return new MinecraftPortal(new MinecraftPortal.Definition(new Portal.State(UUID.randomUUID(), geometry.getApertureCenter(), name,
            PortalFrame.canonical(Direction.N), true), geometry, "minecraft:overworld", Map.of("type", "PORTAL", "owner", UUID.randomUUID().toString())));
    }
}
