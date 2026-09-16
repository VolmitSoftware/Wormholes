package art.arcane.wormholes.atlas;

import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.ILocalPortal;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasProximityIndexTest {
    private static final UUID OVERWORLD = UUID.randomUUID();
    private static final World WORLD = world();

    @Test
    void aBuiltPortalIsDiscoverableFromItsAperture() {
        AtlasProximityIndex index = new AtlasProximityIndex();
        UUID market = UUID.randomUUID();

        index.rebuild(List.of(portal(market, DimensionalPortalKind.NONE, 10.0D, 64.0D, 10.0D)));

        assertEquals(List.of(market), index.near(OVERWORLD, 11.0D, 64.0D, 10.0D, 6.0D));
    }

    @Test
    void vanillaReplacedNetherPortalsNeverEnterDiscovery() {
        AtlasProximityIndex index = new AtlasProximityIndex();

        index.rebuild(List.of(portal(UUID.randomUUID(), DimensionalPortalKind.NETHER, 10.0D, 64.0D, 10.0D)));

        assertTrue(index.near(OVERWORLD, 10.0D, 64.0D, 10.0D, 6.0D).isEmpty());
    }

    @Test
    void vanillaReplacedEndPortalsNeverEnterDiscovery() {
        AtlasProximityIndex index = new AtlasProximityIndex();

        index.rebuild(List.of(
                portal(UUID.randomUUID(), DimensionalPortalKind.END_SOURCE, 10.0D, 64.0D, 10.0D),
                portal(UUID.randomUUID(), DimensionalPortalKind.END_ARRIVAL, 12.0D, 64.0D, 10.0D)));

        assertTrue(index.near(OVERWORLD, 10.0D, 64.0D, 10.0D, 6.0D).isEmpty());
    }

    private static ILocalPortal portal(UUID id, DimensionalPortalKind kind, double x, double y, double z) {
        Location center = new Location(WORLD, x, y, z);
        return (ILocalPortal) Proxy.newProxyInstance(ILocalPortal.class.getClassLoader(),
                new Class<?>[]{ILocalPortal.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "getDimensionalPortalKind" -> kind;
                    case "isDestroyed" -> Boolean.FALSE;
                    case "getCenter" -> center;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Portal[" + id + "]";
                    default -> throw new UnsupportedOperationException("the atlas index touched ILocalPortal." + method.getName());
                });
    }

    private static World world() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> OVERWORLD;
                    case "getName" -> "world";
                    case "hashCode" -> OVERWORLD.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "World[world]";
                    default -> throw new UnsupportedOperationException("the atlas index touched World." + method.getName());
                });
    }
}
