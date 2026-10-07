package art.arcane.optics.entity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProjectedMapsOutputTest {
    private static final AtomicInteger ENTITY_IDS = new AtomicInteger(1);

    @Test
    void mapsAreSentAndRejectedPayloadsWarnOnceThroughTheOutput() {
        RecordingEntityOutput output = new RecordingEntityOutput();
        ProjectedMaps<Object> maps = new ProjectedMaps<>(output);
        SpoofedEntity state = SpoofedEntity.create(ENTITY_IDS::getAndIncrement, false, false, false);
        MapSnapshot map = new MapSnapshot(7, (byte) 0, false, false, new byte[MapSnapshot.WIDTH * MapSnapshot.HEIGHT]);

        ProjectedMaps.Projection sent = maps.send(output, state, map, false, true);
        assertEquals(List.of(sent.mapId()), output.maps);

        EntitySnapshot broken = EntitySnapshot.full(UUID.randomUUID(), "item_frame", 0.0D, 0.0D, 0.0D, 0.5D, 0.0D, 0.0D, 1.0D,
            0.0F, 0.0F, 0.0D, 0.0D, 0.0D, false, null, null, null, null, null, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY,
            new byte[] {1, 2, 3}, 0);
        assertTrue(maps.project(output, broken, state, new ProjectedMaps.Options(7, ItemFrameTransform.NONE, true)).stripMapId());
        maps.project(output, broken, state, new ProjectedMaps.Options(7, ItemFrameTransform.NONE, true));
        assertEquals(1, output.warnings.size());
        assertTrue(output.warnings.getFirst().contains(broken.id().toString()));
    }
}
