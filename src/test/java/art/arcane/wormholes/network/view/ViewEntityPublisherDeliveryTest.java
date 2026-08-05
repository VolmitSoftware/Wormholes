package art.arcane.wormholes.network.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

final class ViewEntityPublisherDeliveryTest {
    @Test
    void mapChangesRequireARecoverableFullSnapshot() {
        assertTrue(ViewEntityPublisher.requiresFullSnapshot(EntityVisual.FIELD_MAP_DATA));
        assertTrue(ViewEntityPublisher.requiresFullSnapshot(
            EntityVisual.FIELD_POSITION | EntityVisual.FIELD_MAP_DATA));
        assertFalse(ViewEntityPublisher.requiresFullSnapshot(EntityVisual.FIELD_POSITION));
    }

    @Test
    void fullAndDeltaVisualsUseSeparateDeliveryBatches() {
        EntityVisual full = visual(new UUID(0L, 1L));
        EntityVisual baseline = visual(new UUID(0L, 2L));
        EntityVisual delta = EntityDeltaCodec.buildDelta(
            baseline, baseline, 1, EntityVisual.FIELD_POSITION);

        List<List<EntityVisual>> batches = ViewEntityPublisher.deliveryBatches(List.of(delta, full));

        assertEquals(2, batches.size());
        assertEquals(1, batches.get(0).size());
        assertSame(full, batches.get(0).get(0));
        assertEquals(1, batches.get(1).size());
        assertSame(delta, batches.get(1).get(0));
    }

    private static EntityVisual visual(UUID id) {
        return EntityVisual.full(
            id,
            "minecraft:item_frame",
            0.0D, 64.0D, 0.0D,
            0.75D,
            0.0D, 0.0D, 1.0D,
            0.0F, 0.0F,
            0.0D, 0.0D, 0.0D,
            false,
            "", "", "",
            null, null,
            PacketBlobs.EMPTY, PacketBlobs.EMPTY, PacketBlobs.EMPTY,
            0);
    }
}
