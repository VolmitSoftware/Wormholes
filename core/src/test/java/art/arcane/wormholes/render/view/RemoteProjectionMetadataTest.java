package art.arcane.wormholes.render.view;

import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.network.view.ViewBox;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import art.arcane.optics.view.ContentView;

final class RemoteProjectionMetadataTest {
    @Test
    void worldHeightHaloUsesRealBoundaryBiomesAndExteriorLight() {
        RemoteViewCache.RemoteView<String, Object, Object> remote = mock(RemoteViewCache.RemoteView.class);
        RemoteViewCache.DecodedSlice<String> slice = mock(RemoteViewCache.DecodedSlice.class);
        when(remote.getBox()).thenReturn(new ViewBox(-16, -64, -16, 31, 319, 31));
        when(remote.sliceAt(anyInt(), anyInt())).thenReturn(slice);
        when(slice.biomeAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> (int) call.getArgument(1) == -64 ? "test:bottom" : "test:top");
        when(slice.lightAt(anyInt(), anyInt(), anyInt())).thenReturn(ContentView.packLight(13, 9));
        RemoteProjectionView<String, String, Object, Object> view = new RemoteProjectionView<>(remote,
            new RemoteProjectionView.Options<>("minecraft:air", state -> state));
        assertEquals("test:bottom", view.sampleBiome(0, -72, 0));
        assertEquals("test:top", view.sampleBiome(0, 327, 0));
        assertEquals(ContentView.packLight(0, 0), view.getLight(0, -65, 0));
        assertEquals(ContentView.packLight(13, 0), view.getLight(0, 320, 0));
        assertEquals(ContentView.packLight(13, 9), view.getLight(0, 319, 0));
        assertNull(view.sampleBiome(32, 320, 0));
        assertEquals(ContentView.LIGHT_UNAVAILABLE, view.getLight(32, 320, 0));
    }
}
