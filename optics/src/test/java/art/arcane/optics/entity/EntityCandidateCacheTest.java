package art.arcane.optics.entity;

import art.arcane.optics.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class EntityCandidateCacheTest {
    @Test
    public void depthCachesRemainIndependentAndExpireAtTheConfiguredBoundary() {
        AtomicInteger queries = new AtomicInteger();
        CandidateCache<String, Integer> cache = new CandidateCache<>((world, center, range) -> List.of(queries.incrementAndGet()));
        UUID id = UUID.randomUUID();
        Vec3d center = new Vec3d(0, 64, 0);
        CandidateCache.Query<String> near = new CandidateCache.Query<>(id, "world", center, 24, 4);
        CandidateCache.Query<String> far = new CandidateCache.Query<>(id, "world", center, 32, 4);
        assertEquals(List.of(1), cache.nearby(near, 1_000));
        assertEquals(List.of(2), cache.nearby(far, 1_000));
        assertEquals(List.of(1), cache.nearby(near, 1_200));
        assertEquals(List.of(2), cache.nearby(far, 1_200));
        assertEquals(List.of(3), cache.nearby(near, 1_201));
        assertEquals(List.of(4), cache.nearby(new CandidateCache.Query<>(id, "other", center, 24, 4), 1_201));
        assertEquals(List.of(5), cache.nearby(new CandidateCache.Query<>(id, "other", center.add(new Vec3d(1, 0, 0)), 24, 4), 1_201));
    }
}
