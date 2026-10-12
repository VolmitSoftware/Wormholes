package art.arcane.wormholes.modded;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.RemotePortalRegistry;
import art.arcane.wormholes.portal.Portal;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.math.Face;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

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
        CompletableFuture<Boolean> projection = api.projection(source.getId(), false);
        assertFalse(projection.isDone());
        scheduled.removeFirst().run();
        assertTrue(projection.join());
        for (int tick = 0; tick < 20; tick++) { api.tick(); }
        assertFalse(api.portals().byId(source.getId()).orElseThrow().projectionEnabled());
        assertTrue(events.stream().anyMatch(event -> event.kind() == MinecraftWormholesApi.Kind.PORTAL_PROJECTION_CHANGED));
        verify(registry, times(2)).save(source);
        CompletableFuture<Boolean> gravity = api.gravityRotation(source.getId(), true);
        assertFalse(gravity.isDone());
        scheduled.removeFirst().run();
        assertTrue(gravity.join());
        assertTrue(source.isGravityRotationEnabled());
        CompletableFuture<Optional<Boolean>> gravityState = api.gravityRotation(source.getId());
        scheduled.removeFirst().run();
        assertEquals(Optional.of(true), gravityState.join());
        CompletableFuture<Optional<Boolean>> missingGravity = api.gravityRotation(UUID.randomUUID());
        scheduled.removeFirst().run();
        assertTrue(missingGravity.join().isEmpty());
        verify(registry, times(3)).save(source);
        current.remove(target);
        for (int tick = 0; tick < 20; tick++) { api.tick(); }
        assertTrue(events.stream().anyMatch(event -> event.kind() == MinecraftWormholesApi.Kind.PORTAL_DESTROYED));
        CompletableFuture<Boolean> unfinished = api.rename(source.getId(), "later");
        api.close();
        assertTrue(unfinished.isCompletedExceptionally());
        assertTrue(MinecraftWormholesApi.forServer(server).isEmpty());
        assertTrue(api.portals().all().isEmpty());
    }

    @Test
    public void traversalSubscriptionsUnsubscribeOnceAndCloseWithTheApi() throws Exception {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftPortalRegistry registry = mock(MinecraftPortalRegistry.class);
        MinecraftNetworkService service = mock(MinecraftNetworkService.class);
        NetworkManager network = mock(NetworkManager.class);
        MinecraftTravelCosts costs = mock(MinecraftTravelCosts.class);
        when(runtime.server()).thenReturn(mock(MinecraftServer.class));
        when(runtime.portals()).thenReturn(registry);
        when(runtime.network()).thenReturn(service);
        when(runtime.costs()).thenReturn(costs);
        when(service.manager()).thenReturn(network);
        when(service.remotePortals()).thenReturn(new RemotePortalRegistry());
        when(network.peerSnapshots()).thenReturn(List.of());
        when(registry.snapshot()).thenReturn(List.of());
        MinecraftTravelCosts.Listener first = mock(MinecraftTravelCosts.Listener.class);
        MinecraftTravelCosts.Listener second = mock(MinecraftTravelCosts.Listener.class);
        AutoCloseable firstRegistration = mock(AutoCloseable.class);
        AutoCloseable secondRegistration = mock(AutoCloseable.class);
        when(costs.listen(first)).thenReturn(firstRegistration);
        when(costs.listen(second)).thenReturn(secondRegistration);
        MinecraftWormholesApi api = new MinecraftWormholesApi(runtime);
        api.start();
        AutoCloseable unsubscribe = api.listenTraversal(first);
        api.listenTraversal(second);
        unsubscribe.close();
        unsubscribe.close();
        verify(firstRegistration).close();
        api.close();
        verify(firstRegistration).close();
        verify(secondRegistration).close();
    }

    private static MinecraftPortal portal(String name) {
        ApertureCells geometry = new ApertureCells();
        geometry.setBlocks(List.of(new Vec3d(0, 64, 0), new Vec3d(0, 65, 0)));
        return new MinecraftPortal(new MinecraftPortal.Definition(new Portal.State(UUID.randomUUID(), geometry.getApertureCenter(), name,
            Frame.canonical(Face.N), true), geometry, "minecraft:overworld", Map.of("type", "PORTAL", "owner", UUID.randomUUID().toString())));
    }
}
