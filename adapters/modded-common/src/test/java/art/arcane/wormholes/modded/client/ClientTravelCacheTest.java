package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.ClientTravelHash;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import art.arcane.wormholes.network.client.TravelMessage;

public class ClientTravelCacheTest {
    @Test
    public void digestIsComputedOnlyWhenRequestedAndRetainedForLaterProofs() throws ReflectiveOperationException {
        ClientTravelCache cache = new ClientTravelCache();
        byte[] original = {1, 2, 3};
        cache.put("world", 0, 0, original);
        Object retained = entry(cache);
        byte[] owned = cache.peek("world", 0, 0);
        assertNotSame(original, owned);
        assertNull(hash(retained));
        original[0] = 7;
        assertNull(cache.get("world", 0, 0, ClientTravelHash.of(original)));
        byte[] digest = hash(retained);
        assertArrayEquals(ClientTravelHash.of(new byte[]{1, 2, 3}), digest);
        assertSame(owned, cache.get("world", 0, 0, digest));
        assertSame(digest, hash(retained));
        assertNull(cache.get("world", 0, 0, ClientTravelHash.of(new byte[]{9})));
        assertSame(digest, hash(retained));
    }

    @Test
    public void identicalContentRetainsOwnedPayloadAndDigestButChangedContentReplacesBoth() throws ReflectiveOperationException {
        ClientTravelCache cache = new ClientTravelCache();
        byte[] original = {1, 2, 3};
        cache.put("world", 0, 0, original);
        Object retained = entry(cache);
        byte[] owned = cache.peek("world", 0, 0);
        cache.put("world", 0, 0, original.clone());
        assertSame(retained, entry(cache));
        assertSame(owned, cache.peek("world", 0, 0));
        assertNull(hash(retained));
        byte[] proof = ClientTravelHash.of(original);
        assertSame(owned, cache.get("world", 0, 0, proof));
        byte[] digest = hash(retained);
        cache.put("world", 0, 0, original.clone());
        assertSame(retained, entry(cache));
        assertSame(digest, hash(retained));
        byte[] changed = {4, 5, 6};
        cache.put("world", 0, 0, changed);
        Object replacement = entry(cache);
        assertNotSame(retained, replacement);
        assertNotSame(changed, cache.peek("world", 0, 0));
        assertNull(hash(replacement));
        changed[0] = 9;
        assertNull(cache.get("world", 0, 0, proof));
        assertArrayEquals(new byte[]{4, 5, 6}, cache.get("world", 0, 0, ClientTravelHash.of(new byte[]{4, 5, 6})));
    }

    @Test
    public void identicalContentTouchesLruWithoutEvictingOrReplacingPayload() {
        ClientTravelCache cache = new ClientTravelCache();
        byte[] data = new byte[TravelMessage.MAX_TRAVEL_CHUNK_BYTES];
        int capacity = TravelMessage.MAX_TRAVEL_BYTES / data.length;
        cache.put("world", 0, 0, data);
        byte[] owned = cache.peek("world", 0, 0);
        for (int x = 1; x < capacity; x++) {
            cache.put("world", x, 0, data);
        }
        cache.put("world", 0, 0, data);
        cache.put("world", capacity, 0, data);
        assertNull(cache.peek("world", 1, 0));
        assertSame(owned, cache.peek("world", 0, 0));
        assertArrayEquals(data, cache.peek("world", capacity, 0));
    }

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
    public void changedColumnInvalidatesOnlyItsWorldAndPosition() {
        ClientTravelCache cache = new ClientTravelCache();
        byte[] data = {1, 2};
        cache.put("source", 0, 0, data);
        cache.put("source", 1, 0, data);
        cache.put("destination", 0, 0, data);
        cache.invalidate("source", 0, 0);
        assertNull(cache.peek("source", 0, 0));
        assertArrayEquals(data, cache.peek("source", 1, 0));
        assertArrayEquals(data, cache.peek("destination", 0, 0));
    }

    @Test
    public void inactiveColumnsEvictWithinExistingByteBudget() {
        ClientTravelCache cache = new ClientTravelCache();
        cache.bind(new Object(), new Object());
        byte[] data = new byte[TravelMessage.MAX_TRAVEL_CHUNK_BYTES];
        byte[] hash = ClientTravelHash.of(data);
        int capacity = TravelMessage.MAX_TRAVEL_BYTES / data.length;
        for (int x = 0; x <= capacity; x++) {
            cache.put("world", x, 0, data);
        }
        assertNull(cache.get("world", 0, 0, hash));
        assertArrayEquals(data, cache.get("world", capacity, 0, hash));
    }

    private Object entry(ClientTravelCache cache) throws ReflectiveOperationException {
        Field columns = ClientTravelCache.class.getDeclaredField("columns");
        columns.setAccessible(true);
        return ((Map<?, ?>) columns.get(cache)).values().iterator().next();
    }

    private byte[] hash(Object entry) throws ReflectiveOperationException {
        Field hash = entry.getClass().getDeclaredField("hash");
        hash.setAccessible(true);
        return (byte[]) hash.get(entry);
    }
}
