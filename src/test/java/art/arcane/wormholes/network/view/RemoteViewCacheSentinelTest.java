package art.arcane.wormholes.network.view;

import art.arcane.wormholes.network.replication.ChunkBulk;
import art.arcane.wormholes.network.replication.ChunkBulkBuilder;
import art.arcane.wormholes.render.view.OccludedMarker;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class RemoteViewCacheSentinelTest {
    private static final String PEER = "peer-o";

    @Test
    void sentinelDecodesToTheOccludingStandInInsteadOfAir() throws Exception {
        RemoteViewCache cache = new RemoteViewCache();
        UUID portalId = UUID.randomUUID();
        cache.getOrCreate(PEER, portalId);

        List<String> palette = List.of(OccludedMarker.STATE_STRING);
        short[] indices = new short[]{0};
        int gridLength = ViewSlice.biomeGridSpan(0, 1) * ViewSlice.biomeGridSpan(0, 1) * ViewSlice.biomeGridSpan(0, 1);
        ViewSlice slice = new ViewSlice(0, 0, 0, 1, 1, 1, palette, indices, new byte[1], List.of("minecraft:plains"), new short[gridLength]);
        byte[] payload = ChunkBulkBuilder.encodeSliceBytes(slice);
        ChunkBulk bulk = new ChunkBulk(ViewSlice.columnKey(0, 0), 1L, payload);

        BlockData decoded = withBukkitServer(() -> {
            cache.applyChunkBulk(PEER, List.of(bulk));
            RemoteViewCache.RemoteView view = cache.get(PEER, portalId);
            assertNotNull(view, "the subscribed view must receive the published slice");
            RemoteViewCache.DecodedSlice decodedSlice = view.sliceAt(0, 0);
            assertNotNull(decodedSlice, "the chunk slice must be present after bulk apply");
            return decodedSlice.blockAt(0, 0, 0);
        });

        assertNotNull(decoded, "the sentinel cell must resolve to a concrete stand-in, never to a null/AIR fallback");
        assertSame(OccludedMarker.standIn(), decoded, "the sentinel must be intercepted to the shared occluding stand-in");
        assertNotEquals(Material.AIR, decoded.getMaterial(), "the sentinel must not fall back to AIR");
    }

    private interface ServerAction {
        BlockData run();
    }

    private static BlockData withBukkitServer(ServerAction action) throws Exception {
        synchronized (Bukkit.class) {
            Field serverField = Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true);
            Object previous = serverField.get(null);
            serverField.set(null, fakeServer());
            try {
                return action.run();
            } finally {
                serverField.set(null, previous);
            }
        }
    }

    private static Server fakeServer() {
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class}, (proxy, method, args) -> {
            if ("createBlockData".equals(method.getName()) && args != null && args.length == 1 && args[0] instanceof String state) {
                if (state.contains("occluded")) {
                    throw new IllegalArgumentException("sentinel must be intercepted before createBlockData: " + state);
                }
                return blockData(state);
            }
            return switch (method.getName()) {
                case "getName", "toString" -> "RemoteViewCacheSentinelTestServer";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
    }

    private static BlockData blockData(String state) {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[]{BlockData.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getAsString", "toString" -> state;
            case "getMaterial" -> Material.STONE;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "clone" -> proxy;
            default -> null;
        });
    }
}
