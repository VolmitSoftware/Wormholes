package art.arcane.wormholes.ops.webmap;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MapAdapterAvailabilityTest {
    @Test
    void everyAdapterIsUnavailableAndInertWithoutItsMapPlugin() {
        List<WebMapPublisher> adapters = List.of(new DynmapMarkers(), new BlueMapMarkers(),
            new Pl3xMapMarkers(), new SquaremapMarkers());

        for (WebMapPublisher adapter : adapters) {
            assertFalse(adapter.available(), adapter.id());
            adapter.publish(List.of(marker()), List.of(UUID.randomUUID()), true);
        }
        assertEquals(List.of("dynmap", "bluemap", "pl3xmap", "squaremap"),
            adapters.stream().map(WebMapPublisher::id).toList());
    }

    @Test
    void theMarkerLabelCarriesTypeStateDestinationAndPocketFlag() {
        String label = DynmapMarkers.label(new MarkerSnapshot(UUID.randomUUID(), "Gate", "RTP", "world",
            1.0D, 2.0D, 3.0D, true, "Arena", true, 2000, true));

        assertEquals("Gate (rtp, open -> Arena, radius 2000, pocket entrance)", label);
    }

    private static MarkerSnapshot marker() {
        return new MarkerSnapshot(UUID.randomUUID(), "Gate", "PORTAL", "world", 1.0D, 2.0D, 3.0D,
            true, "", true, 0, false);
    }
}
