package art.arcane.wormholes.network.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import art.arcane.optics.stream.ViewStreamCapability;
import org.junit.jupiter.api.Test;

final class RemoteViewOptionsTest {
    @Test
    void remoteViewBudgetsAreClamped() {
        RemoteViewOptions low = new RemoteViewOptions(true, 0, 0, 1);
        RemoteViewOptions high = new RemoteViewOptions(true, 99, 900, Integer.MAX_VALUE);

        assertEquals(1, low.routes());
        assertEquals(1, low.chunksPerTick());
        assertEquals(RemoteViewOptions.MIN_BYTES_PER_TICK, low.bytesPerTick());
        assertEquals(RemoteViewOptions.MAX_ROUTES, high.routes());
        assertEquals(RemoteViewOptions.MAX_CHUNKS_PER_TICK, high.chunksPerTick());
        assertEquals(RemoteViewOptions.MAX_BYTES_PER_TICK, high.bytesPerTick());
        assertEquals(new RemoteViewOptions(true, 2, 8, 192 * 1024), RemoteViewOptions.DEFAULT);
    }

    @Test
    void disabledRemoteViewWithholdsTheResidentCapabilities() {
        assertEquals(0L, RemoteViewOptions.DEFAULT.withheldCaps());
        assertEquals(ClientViewExtensions.REMOTE_VIEW | ClientViewExtensions.SEAMLESS_TRAVEL,
            new RemoteViewOptions(false, 2, 8, 192 * 1024).withheldCaps());
    }

    @Test
    void travelPrerequisitesAreDeclaredByTheExtensions() {
        long mesh = ViewStreamCapability.MESH_RENDER.mask();
        assertEquals(ClientViewExtensions.PREPARED_TRAVEL | mesh, TravelExtension.PREPARED.requires(ClientViewExtensions.PREPARED_TRAVEL_CACHE));
        TravelExtension seamless = new TravelExtension(SeamlessTravelCodec.INSTANCE);
        assertEquals(ClientViewExtensions.REMOTE_VIEW | mesh, seamless.requires(ClientViewExtensions.SEAMLESS_TRAVEL));
        assertEquals(0L, seamless.requires(ClientViewExtensions.REMOTE_VIEW));
        assertEquals(0L, TravelExtension.PREPARED.requires(ClientViewExtensions.SEAMLESS_TRAVEL));
    }
}
