package art.arcane.wormholes.render;

import art.arcane.wormholes.geometry.GeometryVector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class EntityCandidateCacheTest {
    @Test
    public void depthCachesRemainIndependentAndExpireAtTheConfiguredBoundary() {
        AtomicInteger queries = new AtomicInteger();
        EntityCandidateCache<String, Integer> cache = new EntityCandidateCache<>((world, center, range) -> List.of(queries.incrementAndGet()));
        UUID id = UUID.randomUUID();
        GeometryVector center = new GeometryVector(0, 64, 0);
        EntityCandidateCache.Query<String> near = new EntityCandidateCache.Query<>(id, "world", center, 24, 4);
        EntityCandidateCache.Query<String> far = new EntityCandidateCache.Query<>(id, "world", center, 32, 4);
        assertEquals(List.of(1), cache.nearby(near, 1_000));
        assertEquals(List.of(2), cache.nearby(far, 1_000));
        assertEquals(List.of(1), cache.nearby(near, 1_200));
        assertEquals(List.of(2), cache.nearby(far, 1_200));
        assertEquals(List.of(3), cache.nearby(near, 1_201));
        assertEquals(List.of(4), cache.nearby(new EntityCandidateCache.Query<>(id, "other", center, 24, 4), 1_201));
        assertEquals(List.of(5), cache.nearby(new EntityCandidateCache.Query<>(id, "other", center.add(new GeometryVector(1, 0, 0)), 24, 4), 1_201));
    }
}
