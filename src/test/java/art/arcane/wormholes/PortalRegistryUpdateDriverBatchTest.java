package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.ILocalPortal;

final class PortalRegistryUpdateDriverBatchTest {
    @Test
    void thousandDuePortalsInOneOwnerChunkNeedOneRegionSubmissionEveryTick() {
        World world = world(new UUID(1L, 1L));
        List<ILocalPortal> portals = new ArrayList<ILocalPortal>(1_000);
        for (int index = 0; index < 1_000; index++) {
            Location center = new Location(world, (index % 16) + 0.5D, 64.0D, ((index / 16) % 16) + 0.5D);
            portals.add(portal(new UUID(0L, index + 1L), center, index < 500, index >= 500));
        }

        for (long tick = 1L; tick <= 20L; tick++) {
            List<PortalRegistryUpdateDriver.PortalChunkBatch> batches =
                PortalRegistryUpdateDriver.collectFoliaBatches(portals, tick);
            assertEquals(1, batches.size());
            assertEquals(portals, batches.get(0).portals(),
                "batching must retain every open or attended portal in snapshot order on every tick");
        }
    }

    @Test
    void thousandDifferentOwnerChunksRemainOneSubmissionEach() {
        World world = world(new UUID(2L, 2L));
        List<ILocalPortal> portals = new ArrayList<ILocalPortal>(1_000);
        for (int index = 0; index < 1_000; index++) {
            Location center = new Location(world, (index * 16.0D) + 0.5D, 64.0D, 0.5D);
            portals.add(portal(new UUID(0L, index + 1L), center, true, false));
        }

        List<PortalRegistryUpdateDriver.PortalChunkBatch> batches =
            PortalRegistryUpdateDriver.collectFoliaBatches(portals, 1L);
        int scheduledPortals = 0;
        for (PortalRegistryUpdateDriver.PortalChunkBatch batch : batches) {
            assertEquals(1, batch.portals().size());
            scheduledPortals += batch.portals().size();
        }

        assertEquals(1_000, batches.size(), "different owner chunks must never be merged into one Folia task");
        assertEquals(1_000, scheduledPortals);
    }

    @Test
    void equalChunkCoordinatesInDifferentWorldsRemainSeparate() {
        List<ILocalPortal> portals = List.of(
            portal(new UUID(0L, 1L), new Location(world(new UUID(3L, 1L)), 0.5D, 64.0D, 0.5D), true, false),
            portal(new UUID(0L, 2L), new Location(world(new UUID(3L, 2L)), 0.5D, 64.0D, 0.5D), true, false));

        assertEquals(2, PortalRegistryUpdateDriver.collectFoliaBatches(portals, 1L).size());
    }

    private static ILocalPortal portal(UUID portalId, Location center, boolean open, boolean attended) {
        return (ILocalPortal) Proxy.newProxyInstance(
            ILocalPortal.class.getClassLoader(),
            new Class<?>[] {ILocalPortal.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getId" -> portalId;
                case "getCenter" -> center;
                case "isOpen" -> open;
                case "isAmbientAttended" -> attended;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "portal(" + portalId + ")";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static World world(UUID worldId) {
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[] {World.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUID" -> worldId;
                case "getName" -> "world-" + worldId;
                case "hashCode" -> worldId.hashCode();
                case "equals" -> proxy == arguments[0];
                case "toString" -> "world(" + worldId + ")";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
