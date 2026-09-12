package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.network.PortalInfo;
import art.arcane.wormholes.network.RemotePortalRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectoryCacheTest {
    @TempDir
    Path tempDir;

    private static PortalInfo info(UUID id, String name, boolean open) {
        return new PortalInfo(id, name, "minecraft:overworld", "GATEWAY", open, "N", "E", "U",
            10.5D, 64.0D, 20.5D, 9.5D, 63.5D, 19.5D, 11.5D, 66.5D, 21.5D);
    }

    @Test
    void restartHydratesLastKnownPortalsAsStaleUntilAFreshDirectoryArrives() throws IOException {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        DirectoryCache cache = DirectoryCache.loadOrCreate(tempDir);
        cache.record("beta", List.of(info(first, "Hub", true), info(second, "Mine", false)));
        assertTrue(Files.isRegularFile(tempDir.resolve("mesh").resolve("directory.json")));
        assertTrue(Files.readString(tempDir.resolve("mesh").resolve("directory.json"), StandardCharsets.UTF_8).contains("Hub"));

        RemotePortalRegistry registry = new RemotePortalRegistry();
        DirectoryCache.loadOrCreate(tempDir).hydrate(registry);
        assertTrue(registry.isStale("beta"));
        assertNotNull(registry.get("beta", first));
        assertTrue(registry.get("beta", first).isOpen());
        assertFalse(registry.get("beta", second).isOpen());
        assertEquals("Hub", registry.get("beta", first).getName());

        registry.applyDirectory("beta", List.of(info(first, "Hub", true)));
        assertFalse(registry.isStale("beta"));
        assertNull(registry.get("beta", second));
        assertFalse(registry.isStale("never-seen"));
    }

    @Test
    void tombstonedPortalsDoNotResurrectOnHydrateUntilTheyAreRecreated() throws IOException {
        UUID kept = UUID.randomUUID();
        UUID removed = UUID.randomUUID();
        DirectoryCache cache = DirectoryCache.loadOrCreate(tempDir);
        cache.record("beta", List.of(info(kept, "Hub", true), info(removed, "Old", true)));
        cache.tombstone("beta", removed);

        RemotePortalRegistry registry = new RemotePortalRegistry();
        DirectoryCache.loadOrCreate(tempDir).hydrate(registry);
        assertNotNull(registry.get("beta", kept));
        assertNull(registry.get("beta", removed));

        cache.record("beta", List.of(info(kept, "Hub", true), info(removed, "Old", true)));
        RemotePortalRegistry again = new RemotePortalRegistry();
        DirectoryCache.loadOrCreate(tempDir).hydrate(again);
        assertNotNull(again.get("beta", removed));
    }

    @Test
    void registryChangesFlowIntoTheCacheThroughTheListener() throws IOException {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        DirectoryCache cache = DirectoryCache.loadOrCreate(tempDir);
        RemotePortalRegistry registry = new RemotePortalRegistry();
        registry.setListener(cache);

        registry.applyDirectory("beta", List.of(info(first, "Hub", true)));
        registry.applyUpsert("beta", info(second, "Mine", true));
        assertEquals(2, cache.portals("beta").size());
        registry.applyRemove("beta", first);
        assertEquals(1, cache.portals("beta").size());
        registry.removePeer("beta");
        assertTrue(cache.portals("beta").isEmpty());

        RemotePortalRegistry hydrated = new RemotePortalRegistry();
        DirectoryCache.loadOrCreate(tempDir).hydrate(hydrated);
        assertFalse(hydrated.hasPeer("beta"));
    }

    @Test
    void hydrateNeverOverwritesAPeerThatAlreadyReportedLive() throws IOException {
        UUID live = UUID.randomUUID();
        UUID cached = UUID.randomUUID();
        DirectoryCache cache = DirectoryCache.loadOrCreate(tempDir);
        cache.record("beta", List.of(info(cached, "Cached", true)));
        RemotePortalRegistry registry = new RemotePortalRegistry();
        registry.applyDirectory("beta", List.of(info(live, "Live", true)));

        cache.hydrate(registry);
        assertNull(registry.get("beta", cached));
        assertNotNull(registry.get("beta", live));
        assertFalse(registry.isStale("beta"));
    }
}
