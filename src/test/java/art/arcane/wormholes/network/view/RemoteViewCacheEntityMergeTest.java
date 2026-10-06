package art.arcane.wormholes.network.view;

import com.github.retrooper.packetevents.protocol.player.Equipment;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;

import org.bukkit.block.data.BlockData;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import art.arcane.optics.entity.EntitySnapshot;

class RemoteViewCacheEntityMergeTest {
    private static final String PEER = "peer-a";

    private static EntitySnapshot fullEntity(UUID id, double x) {
        return fullEntity(id, x, new byte[0]);
    }

    private static EntitySnapshot fullEntity(UUID id, double x, byte[] mapData) {
        return EntitySnapshot.full(
            id, "minecraft:zombie",
            x, 64.0D, 0.0D, 1.95D,
            0.0D, 0.0D, 1.0D,
            0.0F, 0.0F,
            0.0D, 0.0D, 0.0D,
            true,
            "", "", "",
            null,
            null,
            new byte[0], new byte[0], mapData,
            1);
    }

    private static Set<UUID> idsOf(RemoteViewCache.RemoteView<BlockData, EntityData<?>, Equipment> view) {
        return view.getEntities().stream().map(EntitySnapshot::id).collect(Collectors.toSet());
    }

    @Test
    void rateLimitedSubsetDoesNotPruneEntitiesStillPresent() {
        RemoteViewCache<BlockData, EntityData<?>, Equipment> cache = new RemoteViewCache<>(BukkitRemoteViewCodec.INSTANCE, RemoteViewCache.Options.defaults());
        UUID portalId = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        RemoteViewCache.RemoteView<BlockData, EntityData<?>, Equipment> view = cache.getOrCreate(PEER, portalId);

        cache.applyEntities(PEER, portalId, List.of(fullEntity(a, 1.0D), fullEntity(b, 2.0D)), List.of(a, b));
        assertEquals(Set.of(a, b), idsOf(view));

        // Next capture sends only A (rate-limited), but B is still present in the authoritative set.
        cache.applyEntities(PEER, portalId, List.of(fullEntity(a, 1.5D)), List.of(a, b));
        assertEquals(Set.of(a, b), idsOf(view), "entity B must survive a batch that omits it while still present");
    }

    @Test
    void entityAbsentFromPresentSetIsPruned() {
        RemoteViewCache<BlockData, EntityData<?>, Equipment> cache = new RemoteViewCache<>(BukkitRemoteViewCodec.INSTANCE, RemoteViewCache.Options.defaults());
        UUID portalId = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        RemoteViewCache.RemoteView<BlockData, EntityData<?>, Equipment> view = cache.getOrCreate(PEER, portalId);

        cache.applyEntities(PEER, portalId, List.of(fullEntity(a, 1.0D), fullEntity(b, 2.0D)), List.of(a, b));
        assertEquals(Set.of(a, b), idsOf(view));

        // B left the view box: authoritative present set no longer contains it, even though no entity payload is sent.
        cache.applyEntities(PEER, portalId, List.of(), List.of(a));
        assertEquals(Set.of(a), idsOf(view));
    }

    @Test
    void emptyPresentSetClearsAllEntities() {
        RemoteViewCache<BlockData, EntityData<?>, Equipment> cache = new RemoteViewCache<>(BukkitRemoteViewCodec.INSTANCE, RemoteViewCache.Options.defaults());
        UUID portalId = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        RemoteViewCache.RemoteView<BlockData, EntityData<?>, Equipment> view = cache.getOrCreate(PEER, portalId);

        cache.applyEntities(PEER, portalId, List.of(fullEntity(a, 1.0D)), List.of(a));
        assertTrue(idsOf(view).contains(a));

        cache.applyEntities(PEER, portalId, List.of(), List.of());
        assertTrue(view.getEntities().isEmpty());
    }

    @Test
    void blobChangedIsTrueForFirstSight() {
        assertTrue(RemoteViewCache.blobChanged(null, new byte[]{1, 2}));
    }

    @Test
    void blobChangedIsFalseForIdenticalBytes() {
        assertTrue(!RemoteViewCache.blobChanged(new byte[]{1, 2}, new byte[]{1, 2}));
    }

    @Test
    void blobChangedIsTrueForDifferentBytes() {
        assertTrue(RemoteViewCache.blobChanged(new byte[]{1, 2}, new byte[]{1, 3}));
        assertTrue(RemoteViewCache.blobChanged(new byte[]{1, 2}, new byte[]{1, 2, 3}));
    }

    @Test
    void emptyBlobsNeverBumpStateVersion() {
        RemoteViewCache<BlockData, EntityData<?>, Equipment> cache = new RemoteViewCache<>(BukkitRemoteViewCodec.INSTANCE, RemoteViewCache.Options.defaults());
        UUID portalId = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        RemoteViewCache.RemoteView<BlockData, EntityData<?>, Equipment> view = cache.getOrCreate(PEER, portalId);

        cache.applyEntities(PEER, portalId, List.of(fullEntity(a, 1.0D)), List.of(a));
        cache.applyEntities(PEER, portalId, List.of(fullEntity(a, 1.5D)), List.of(a));
        assertEquals(0, view.getStateVersion(a));
    }

    @Test
    void mapPixelChangesAndRemovalBumpStateVersion() {
        RemoteViewCache<BlockData, EntityData<?>, Equipment> cache = new RemoteViewCache<>(BukkitRemoteViewCodec.INSTANCE, RemoteViewCache.Options.defaults());
        UUID portalId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        RemoteViewCache.RemoteView<BlockData, EntityData<?>, Equipment> view = cache.getOrCreate(PEER, portalId);

        cache.applyEntities(PEER, portalId,
            List.of(fullEntity(entityId, 1.0D, new byte[]{1})), List.of(entityId));
        assertEquals(1, view.getStateVersion(entityId));
        cache.applyEntities(PEER, portalId,
            List.of(fullEntity(entityId, 1.0D, new byte[]{2})), List.of(entityId));
        assertEquals(2, view.getStateVersion(entityId));
        cache.applyEntities(PEER, portalId,
            List.of(fullEntity(entityId, 1.0D)), List.of(entityId));
        assertEquals(3, view.getStateVersion(entityId));
    }
}
