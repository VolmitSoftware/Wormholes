package art.arcane.wormholes.portal;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPortalRuntimeTest {
    @Test
    void warmArrivalChecksEveryChunkOverlappedByTheEntity() {
        Set<String> checked = new HashSet<>();
        World world = world(checked, Set.of("0,0", "0,1", "1,0", "1,1"));
        Entity entity = entity(new Location(world, 40.0D, 80.0D, 40.0D));

        assertTrue(LocalPortalRuntime.destinationChunksLoaded(entity, new Location(world, 16.0D, 80.0D, 16.0D)));
        assertEquals(Set.of("0,0", "0,1", "1,0", "1,1"), checked);
    }

    @Test
    void missingAdjacentChunkKeepsTheAsynchronousArrival() {
        Set<String> checked = new HashSet<>();
        World world = world(checked, Set.of("1,1"));
        Entity entity = entity(new Location(world, 40.0D, 80.0D, 40.0D));

        assertFalse(LocalPortalRuntime.destinationChunksLoaded(entity, new Location(world, 16.0D, 80.0D, 16.0D)));
    }

    @Test
    void collisionPaddingRequiresChunksBeyondTheEntityFootprint() {
        Set<String> checked = new HashSet<>();
        World world = world(checked, Set.of("0,0"));
        Entity entity = entity(new Location(world, 40.0D, 80.0D, 40.0D));

        assertFalse(LocalPortalRuntime.destinationChunksLoaded(entity, new Location(world, 13.0D, 80.0D, 8.0D)));
        assertEquals(Set.of("0,0", "1,0"), checked);
    }

    @Test
    void negativeCoordinatesUseFloorChunkBoundaries() {
        Set<String> checked = new HashSet<>();
        World world = world(checked, Set.of("-2,-2", "-2,-1", "-1,-2", "-1,-1"));
        Entity entity = entity(new Location(world, 40.0D, 80.0D, 40.0D));

        assertTrue(LocalPortalRuntime.destinationChunksLoaded(entity, new Location(world, -16.0D, 80.0D, -16.0D)));
        assertEquals(Set.of("-2,-2", "-2,-1", "-1,-2", "-1,-1"), checked);
    }

    private static World world(Set<String> checked, Set<String> loaded) {
        return (World) Proxy.newProxyInstance(LocalPortalRuntimeTest.class.getClassLoader(), new Class<?>[] {World.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("isChunkLoaded")) {
                    String key = arguments[0] + "," + arguments[1];
                    checked.add(key);
                    return loaded.contains(key);
                }
                return LocalPortalTestSupport.defaultValue(method.getReturnType());
            });
    }

    private static Entity entity(Location location) {
        return (Entity) Proxy.newProxyInstance(LocalPortalRuntimeTest.class.getClassLoader(), new Class<?>[] {Entity.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getLocation" -> location.clone();
                case "getBoundingBox" -> new BoundingBox(location.getX() - 0.3D, location.getY(), location.getZ() - 0.3D,
                    location.getX() + 0.3D, location.getY() + 1.8D, location.getZ() + 0.3D);
                default -> LocalPortalTestSupport.defaultValue(method.getReturnType());
            });
    }
}
