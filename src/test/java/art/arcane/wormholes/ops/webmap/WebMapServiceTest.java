package art.arcane.wormholes.ops.webmap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebMapServiceTest {
    private static final UUID ONE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TWO = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void theFirstTickPublishesEverythingAndAnIdenticalTickPublishesNothing() {
        AtomicReference<List<MarkerSnapshot>> markers = new AtomicReference<>(List.of(marker(ONE, "one", true),
            marker(TWO, "two", true)));
        RecordingPublisher publisher = new RecordingPublisher(true);
        WebMapService service = new WebMapService(markers::get, List.of(publisher));

        assertEquals(2, service.tick(true, true));
        assertEquals(2, publisher.changed.size());
        assertEquals(0, publisher.removed.size());
        assertEquals(1, publisher.publishes);

        assertEquals(0, service.tick(true, true));
        assertEquals(1, publisher.publishes, "an unchanged tick must not reach the publisher");
    }

    @Test
    void movedAndRenamedPortalsRepublishAndVanishedOnesAreRemoved() {
        AtomicReference<List<MarkerSnapshot>> markers = new AtomicReference<>(List.of(marker(ONE, "one", true),
            marker(TWO, "two", true)));
        RecordingPublisher publisher = new RecordingPublisher(true);
        WebMapService service = new WebMapService(markers::get, List.of(publisher));
        service.tick(true, true);

        markers.set(List.of(new MarkerSnapshot(ONE, "renamed", "PORTAL", "world", 1.0D, 2.0D, 3.0D,
            true, "elsewhere", true, 0, false)));
        assertEquals(1, service.tick(true, true));

        assertEquals(1, publisher.changed.size());
        assertEquals("renamed", publisher.changed.get(0).name());
        assertEquals(List.of(TWO), publisher.removed);
    }

    @Test
    void unlistedPortalsAreFilteredWhenRespectListedIsOnAndRemovedAfterBeingPublished() {
        AtomicReference<List<MarkerSnapshot>> markers = new AtomicReference<>(List.of(marker(ONE, "one", true)));
        RecordingPublisher publisher = new RecordingPublisher(true);
        WebMapService service = new WebMapService(markers::get, List.of(publisher));
        service.tick(true, true);

        markers.set(List.of(marker(ONE, "one", false)));
        assertEquals(0, service.tick(true, true));
        assertEquals(List.of(ONE), publisher.removed);

        publisher.reset();
        markers.set(List.of(marker(ONE, "one", false)));
        service.clear();
        assertEquals(1, service.tick(false, true), "respect-listed off publishes unlisted portals");
    }

    @Test
    void publishersThatAreNotInstalledAreSkipped() {
        RecordingPublisher absent = new RecordingPublisher(false);
        RecordingPublisher present = new RecordingPublisher(true);
        WebMapService service = new WebMapService(() -> List.of(marker(ONE, "one", true)),
            List.of(absent, present));

        assertEquals(1, service.tick(true, false));
        assertEquals(0, absent.publishes);
        assertEquals(1, present.publishes);
        assertTrue(present.linkLines.isEmpty() || !present.linkLines.get(0).booleanValue());
    }

    @Test
    void withNoMapPluginInstalledThePortalScanDoesNotRun() {
        AtomicInteger scans = new AtomicInteger();
        WebMapService service = new WebMapService(() -> {
            scans.incrementAndGet();
            return List.of(marker(ONE, "one", true));
        }, List.of(new RecordingPublisher(false)));

        assertEquals(0, service.tick(true, true));
        assertEquals(0, scans.get(), "nothing reads the markers, so the per-second walk is skipped");
    }

    private static MarkerSnapshot marker(UUID id, String name, boolean listed) {
        return new MarkerSnapshot(id, name, "PORTAL", "world", 1.0D, 2.0D, 3.0D, true, "elsewhere", listed, 0, false);
    }

    private static final class RecordingPublisher implements WebMapPublisher {
        private final boolean available;
        private final List<MarkerSnapshot> changed = new ArrayList<>();
        private final List<UUID> removed = new ArrayList<>();
        private final List<Boolean> linkLines = new ArrayList<>();
        private int publishes;

        private RecordingPublisher(boolean available) {
            this.available = available;
        }

        private void reset() {
            changed.clear();
            removed.clear();
            linkLines.clear();
            publishes = 0;
        }

        @Override
        public String id() {
            return "recording";
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public void publish(Collection<MarkerSnapshot> changedMarkers, Collection<UUID> removedMarkers,
                            boolean withLinkLines) {
            changed.clear();
            removed.clear();
            changed.addAll(changedMarkers);
            removed.addAll(removedMarkers);
            linkLines.add(Boolean.valueOf(withLinkLines));
            publishes++;
        }
    }
}
