package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.ClientTravelHash;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

public class ClientTravelCacheTest {
    @Test
    public void sourceSeedNeverReplacesPreviouslyReceivedAuthoritativeBytes() {
        ClientTravelCache cache = new ClientTravelCache();
        byte[] authoritative = {1, 2, 3};
        byte[] source = {4, 5, 6};
        cache.put("world", 0, 0, authoritative);
        cache.seed("world", 0, 0, source);
        assertArrayEquals(authoritative, cache.get("world", 0, 0, ClientTravelHash.of(authoritative)));
        assertNull(cache.get("world", 0, 0, ClientTravelHash.of(source)));
        cache.seed("world", 1, 0, source);
        assertArrayEquals(source, cache.get("world", 1, 0, ClientTravelHash.of(source)));
        cache.put("world", 0, 0, source);
        assertArrayEquals(source, cache.get("world", 0, 0, ClientTravelHash.of(source)));
        assertNull(cache.get("world", 0, 0, ClientTravelHash.of(authoritative)));
    }

    @Test
    public void currentServerDigestAndExactWorldAreRequiredAndStoredBytesAreImmutable() {
        ClientTravelCache cache = new ClientTravelCache();
        cache.bind(new Object(), new Object());
        byte[] original = {1, 2, 3};
        byte[] hash = ClientTravelHash.of(original);
        cache.put("minecraft:overworld", 1, 2, original);
        original[0] = 7;
        assertArrayEquals(new byte[]{1, 2, 3}, cache.get("minecraft:overworld", 1, 2, hash));
        assertNull(cache.get("minecraft:the_nether", 1, 2, hash));
        assertNull(cache.get("minecraft:overworld", 2, 2, hash));
        assertNull(cache.get("minecraft:overworld", 1, 2, ClientTravelHash.of(original)));
    }

    @Test
    public void newConnectionOrRegistryClearsPastCollisionData() {
        ClientTravelCache cache = new ClientTravelCache();
        Object connection = new Object();
        Object registry = new Object();
        byte[] data = {1};
        byte[] hash = ClientTravelHash.of(data);
        cache.bind(connection, registry);
        cache.put("world", 0, 0, data);
        cache.bind(connection, registry);
        assertArrayEquals(data, cache.get("world", 0, 0, hash));
        cache.bind(connection, new Object());
        assertNull(cache.get("world", 0, 0, hash));
        cache.put("world", 0, 0, data);
        cache.bind(new Object(), registry);
        assertNull(cache.get("world", 0, 0, hash));
    }

    @Test
    public void inactiveColumnsEvictWithinExistingByteBudget() {
        ClientTravelCache cache = new ClientTravelCache();
        cache.bind(new Object(), new Object());
        byte[] data = new byte[ClientViewProtocol.MAX_TRAVEL_CHUNK_BYTES];
        byte[] hash = ClientTravelHash.of(data);
        int capacity = ClientViewProtocol.MAX_TRAVEL_BYTES / data.length;
        for (int x = 0; x <= capacity; x++) {
            cache.put("world", x, 0, data);
        }
        assertNull(cache.get("world", 0, 0, hash));
        assertArrayEquals(data, cache.get("world", capacity, 0, hash));
    }
}
