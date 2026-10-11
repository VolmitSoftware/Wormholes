package art.arcane.wormholes.render.clientview;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.stream.SessionPalette;
import art.arcane.optics.stream.ViewStreamEndpoints.GeometryMetadataContext;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.wormholes.network.client.ClientViewExtensions;
import art.arcane.wormholes.network.client.RootSource;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BukkitClientViewRootSourceTest {
    @Test
    void rootMetadataUsesTheActualWorldKeyAndRequiresNegotiation() {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            when(fixture.world.getKey()).thenReturn(new NamespacedKey("test", "custom_nether"));
            ClientViewObserver observer = observer(fixture);
            BukkitClientViewPortalAccess access = access(fixture);
            ApertureDescriptor geometry = access.geometry(observer, fixture.portal.getId(), new SessionPalette());
            assertNotNull(geometry);
            List<ViewStreamMessage> messages = access.geometryMetadata(observer, fixture.portal.getId(),
                new GeometryMetadataContext(7, geometry, ClientViewExtensions.ROOT_SOURCE)).messages(11);
            assertEquals(1, messages.size());
            ViewStreamMessage.Extension extension = (ViewStreamMessage.Extension) messages.getFirst();
            assertEquals(new RootSource(7, 11, "test:custom_nether"), extension.payload());
            assertTrue(access.geometryMetadata(observer, fixture.portal.getId(),
                new GeometryMetadataContext(7, geometry, 0L)).messages(11).isEmpty());
        }
    }

    @Test
    void departedRootCannotBeRenewedAtAnyMeshDepth() {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            ClientViewObserver observer = observer(fixture);
            BukkitClientViewPortalAccess access = access(fixture);
            ApertureDescriptor geometry = access.geometry(observer, fixture.portal.getId(), new SessionPalette());
            assertNotNull(geometry);
            when(fixture.player.getWorld()).thenReturn(mock(World.class));
            for (int depth : List.of(0, 8, 128)) {
                observer.meshDepth(depth);
                assertNull(access.geometry(observer, fixture.portal.getId(), new SessionPalette()));
                assertTrue(access.geometryMetadata(observer, fixture.portal.getId(),
                    new GeometryMetadataContext(7, geometry, ClientViewExtensions.ROOT_SOURCE)).messages(11).isEmpty());
            }
        }
    }

    private static ClientViewObserver observer(ClientViewFixture fixture) {
        ClientViewObserver observer = new ClientViewObserver(fixture.playerId, fixture.user);
        observer.meshDepth(128);
        observer.beginFrame(fixture.player, fixture.eye, List.of(fixture.portal), List.of(fixture.portal), Map.of(), 1L);
        return observer;
    }

    private static BukkitClientViewPortalAccess access(ClientViewFixture fixture) {
        return new BukkitClientViewPortalAccess(fixture.views, fixture.plates, ignored -> null, (player, portal) -> {}, () -> 1L);
    }
}
