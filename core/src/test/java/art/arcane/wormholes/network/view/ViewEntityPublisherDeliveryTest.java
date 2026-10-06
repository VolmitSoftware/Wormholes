package art.arcane.wormholes.network.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import art.arcane.optics.entity.EntityDeltaCodec;
import art.arcane.optics.entity.EntitySnapshot;

final class ViewEntityPublisherDeliveryTest {
    @Test
    void mapChangesRequireARecoverableFullSnapshot() {
        assertTrue(ViewEntityPublisher.requiresFullSnapshot(EntitySnapshot.FIELD_MAP_DATA));
        assertTrue(ViewEntityPublisher.requiresFullSnapshot(
            EntitySnapshot.FIELD_POSITION | EntitySnapshot.FIELD_MAP_DATA));
        assertFalse(ViewEntityPublisher.requiresFullSnapshot(EntitySnapshot.FIELD_POSITION));
    }

    @Test
    void fullAndDeltaVisualsUseSeparateDeliveryBatches() {
        EntitySnapshot full = visual(new UUID(0L, 1L));
        EntitySnapshot baseline = visual(new UUID(0L, 2L));
        EntitySnapshot delta = EntityDeltaCodec.buildDelta(
            baseline, baseline, 1, EntitySnapshot.FIELD_POSITION);

        List<List<EntitySnapshot>> batches = ViewEntityPublisher.deliveryBatches(List.of(delta, full));

        assertEquals(2, batches.size());
        assertEquals(1, batches.get(0).size());
        assertSame(full, batches.get(0).get(0));
        assertEquals(1, batches.get(1).size());
        assertSame(delta, batches.get(1).get(0));
    }

    @Test
    void rejectedProfileBatchIsEligibleForRecapture() {
        UUID texturedId = new UUID(0L, 3L);
        UUID plainId = new UUID(0L, 4L);
        Set<UUID> sentProfiles = ConcurrentHashMap.newKeySet();
        sentProfiles.add(texturedId);
        sentProfiles.add(plainId);

        ViewEntityPublisher.retryProfilesAfterRejectedBatch(
            sentProfiles,
            List.of(visual(texturedId, "texture"), visual(plainId)));

        assertFalse(sentProfiles.contains(texturedId));
        assertTrue(sentProfiles.contains(plainId));
    }

    private static EntitySnapshot visual(UUID id) {
        return visual(id, "");
    }

    private static EntitySnapshot visual(UUID id, String textureValue) {
        return EntitySnapshot.full(
            id,
            "minecraft:item_frame",
            0.0D, 64.0D, 0.0D,
            0.75D,
            0.0D, 0.0D, 1.0D,
            0.0F, 0.0F,
            0.0D, 0.0D, 0.0D,
            false,
            "", textureValue, "",
            null, null,
            EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY,
            0);
    }
}
