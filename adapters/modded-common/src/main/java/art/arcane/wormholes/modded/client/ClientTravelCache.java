package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.ClientTravelHash;

import java.security.MessageDigest;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import art.arcane.wormholes.network.client.TravelMessage;

final class ClientTravelCache {
    private final LinkedHashMap<Key, Entry> columns = new LinkedHashMap<>(64, 0.75f, true);
    private WeakReference<Object> connection = new WeakReference<>(null);
    private WeakReference<Object> registry = new WeakReference<>(null);
    private long bytes;

    void bind(Object connection, Object registry) {
        if (this.connection.get() != connection || this.registry.get() != registry) {
            columns.clear();
            bytes = 0;
            this.connection = new WeakReference<>(connection);
            this.registry = new WeakReference<>(registry);
        }
    }

    void put(String world, int x, int z, byte[] data) {
        if (data.length > TravelMessage.MAX_TRAVEL_CHUNK_BYTES) {
            return;
        }
        Key key = new Key(world, x, z);
        Entry existing = columns.get(key);
        if (existing != null && Arrays.equals(existing.data, data)) {
            return;
        }
        Entry previous = columns.remove(key);
        if (previous != null) {
            bytes -= previous.data.length;
        }
        Iterator<Map.Entry<Key, Entry>> iterator = columns.entrySet().iterator();
        while (bytes + data.length > TravelMessage.MAX_TRAVEL_BYTES && iterator.hasNext()) {
            bytes -= iterator.next().getValue().data.length;
            iterator.remove();
        }
        byte[] retained = data.clone();
        columns.put(key, new Entry(retained));
        bytes += retained.length;
    }

    void invalidate(String world, int x, int z) {
        Entry previous = columns.remove(new Key(world, x, z));
        if (previous != null) {
            bytes -= previous.data.length;
        }
    }

    void seed(String world, int x, int z, byte[] data) {
        if (!columns.containsKey(new Key(world, x, z))) {
            put(world, x, z, data);
        }
    }

    byte[] peek(String world, int x, int z) {
        Entry entry = columns.get(new Key(world, x, z));
        return entry == null ? null : entry.data;
    }

    byte[] get(String world, int x, int z, byte[] hash) {
        Entry entry = columns.get(new Key(world, x, z));
        return entry != null && MessageDigest.isEqual(entry.hash(), hash) ? entry.data : null;
    }

    private record Key(String world, int x, int z) { }
    private static final class Entry {
        private final byte[] data;
        private byte[] hash;

        private Entry(byte[] data) {
            this.data = data;
        }

        private byte[] hash() {
            if (hash == null) {
                hash = ClientTravelHash.of(data);
            }
            return hash;
        }
    }
}
