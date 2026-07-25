package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.ILocalPortal;

public final class ProjectedEntityCandidateCacheTest {
    @Test
    public void observersAtDifferentDepthsDoNotEvictEachOthersCandidateSnapshots() {
        AtomicInteger worldQueries = new AtomicInteger();
        Location center = new Location(world(worldQueries), 100.0D, 64.0D, -40.0D);
        ILocalPortal portal = portal();

        EntityRenderCaches.nearbyRemoteEntities(portal, center, 32.0D);
        EntityRenderCaches.nearbyRemoteEntities(portal, center, 24.0D);
        EntityRenderCaches.nearbyRemoteEntities(portal, center, 32.0D);
        EntityRenderCaches.nearbyRemoteEntities(portal, center, 24.0D);

        assertEquals(2, worldQueries.get());
    }

    @Test
    public void candidateQueryRangeRoundsUpAndStaysPositive() {
        assertEquals(48, EntityRenderCaches.candidateQueryRange(47.3D));
        assertEquals(48, EntityRenderCaches.candidateQueryRange(48.0D));
        assertEquals(1, EntityRenderCaches.candidateQueryRange(0.25D));
        assertEquals(1, EntityRenderCaches.candidateQueryRange(Double.NaN));
    }

    private static ILocalPortal portal() {
        UUID id = UUID.randomUUID();
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("getId".equals(name)) {
                return id;
            }
            if ("toString".equals(name)) {
                return "portal";
            }
            if ("hashCode".equals(name)) {
                return Integer.valueOf(id.hashCode());
            }
            if ("equals".equals(name)) {
                return Boolean.valueOf(proxy == args[0]);
            }
            return null;
        };
        return (ILocalPortal) Proxy.newProxyInstance(ILocalPortal.class.getClassLoader(),
            new Class<?>[] { ILocalPortal.class }, handler);
    }

    private static World world(AtomicInteger queries) {
        NamespacedKey key = NamespacedKey.fromString("wormholes:candidate_cache_test");
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("getKey".equals(name)) {
                return key;
            }
            if ("getName".equals(name)) {
                return "candidate_cache_test";
            }
            if ("getNearbyEntities".equals(name)) {
                queries.incrementAndGet();
                return List.of();
            }
            if ("toString".equals(name)) {
                return "candidate_cache_test";
            }
            if ("hashCode".equals(name)) {
                return Integer.valueOf(key.hashCode());
            }
            if ("equals".equals(name)) {
                return Boolean.valueOf(proxy == args[0]);
            }
            return null;
        };
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class }, handler);
    }
}
