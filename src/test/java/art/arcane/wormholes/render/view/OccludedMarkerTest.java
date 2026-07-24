package art.arcane.wormholes.render.view;

import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OccludedMarkerTest {
    @Test
    void sentinelStateStringIsTheReservedWireToken() {
        assertEquals("wormholes:occluded", OccludedMarker.STATE_STRING);
        assertTrue(OccludedMarker.isSentinelState(OccludedMarker.STATE_STRING));
        assertFalse(OccludedMarker.isSentinelState("minecraft:stone"));
        assertFalse(OccludedMarker.isSentinelState(null));
    }

    @Test
    void arbitraryDataAndNullAreNeverTheStandIn() {
        assertFalse(OccludedMarker.isStandIn(null));
        assertFalse(OccludedMarker.isStandIn(fakeBlockData()));
    }

    @Test
    void nullBlockDataIsNotOccluding() {
        assertFalse(OccludedMarker.isOccluding(null));
    }

    private static BlockData fakeBlockData() {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[]{BlockData.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getAsString" -> "minecraft:stone";
            case "clone" -> proxy;
            case "equals" -> proxy == args[0];
            case "hashCode" -> 0;
            case "toString" -> "fake";
            default -> null;
        });
    }
}
