package art.arcane.wormholes.network;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

final class SidebandFragmenter {
    static final int MAX_FRAME_BYTES = WireCodec.MAX_FRAME_BYTES + Integer.BYTES;
    static final int CHUNK_BYTES = 4 * 1024;
    static final int MAX_COUNT = (MAX_FRAME_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES;
    static final long MAX_RETAINED_BYTES = 8L * MAX_FRAME_BYTES;

    private static final long ASSEMBLY_TTL_MS = 15L * 60_000L;
    private static final int ASSEMBLY_CAPACITY = 128;
    private static final long EXPIRY_GATE_MS = 1_000L;

    private final NetworkManager network;
    private final Logger logger;
    private final Object assemblyLock = new Object();
    private final Map<String, Assembly> assemblies = new HashMap<>();
    private final Set<String> oversizeWarnings = ConcurrentHashMap.newKeySet();
    private final AtomicLong messageIds = new AtomicLong();

    private long nextExpiry;
    private long retainedBytes;

    SidebandFragmenter(NetworkManager network, Logger logger) {
        this.network = network;
        this.logger = logger;
    }

    List<MinecraftStatusBridge.EncodedMessage> fragment(String peerName, WireMessage message, byte[] plainFrame) throws IOException {
        byte[] frame;
        try {
            frame = network.compression().encode(plainFrame, false);
        } catch (IOException e) {
            logger.warning("net: could not compress " + message.type() + " for status sideband to " + peerName + ": " + e.getMessage());
            return List.of();
        }
        if (frame.length > MAX_FRAME_BYTES) {
            warnOversized(peerName, message, frame.length);
            return List.of();
        }
        int total = (frame.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
        if (total > MAX_COUNT) {
            warnOversized(peerName, message, frame.length);
            return List.of();
        }
        warnFragmented(peerName, message, frame.length, total);
        long messageId = messageIds.incrementAndGet();
        int sidebandTier = SidebandOutbox.tierOf(message);
        List<MinecraftStatusBridge.EncodedMessage> fragments = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            int offset = index * CHUNK_BYTES;
            int length = Math.min(CHUNK_BYTES, frame.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(frame, offset, chunk, 0, length);
            WireMessage.SidebandFragment fragment = new WireMessage.SidebandFragment(messageId, index, total, frame.length, chunk);
            fragments.add(new MinecraftStatusBridge.EncodedMessage(fragment, WireCodec.encodeFrame(fragment), sidebandTier));
        }
        return fragments;
    }

    Result receive(String peerName, WireMessage.SidebandFragment fragment) {
        long now = System.currentTimeMillis();
        if (!isValid(fragment)) {
            return Result.acceptedIncomplete();
        }
        String key = key(peerName, fragment.messageId());
        Assembly assembly;
        synchronized (assemblyLock) {
            if (now >= nextExpiry) {
                nextExpiry = now + EXPIRY_GATE_MS;
                expireLocked(now);
            }
            assembly = assemblies.get(key);
            if (assembly != null && !assembly.accepts(fragment)) {
                removeLocked(key, assembly);
                assembly = null;
            }
            if (assembly == null) {
                if (assemblies.size() >= ASSEMBLY_CAPACITY
                    || retainedBytes + fragment.frameLength() > MAX_RETAINED_BYTES) {
                    return Result.rejected();
                }
                assembly = new Assembly(fragment, now);
                assemblies.put(key, assembly);
                retainedBytes += assembly.retainedBytes();
            }
            assembly.add(fragment);
            if (!assembly.beginDecode()) {
                return Result.acceptedIncomplete();
            }
        }
        try {
            byte[] plainFrame = network.compression().decode(assembly.assemble()).payload();
            WireMessage message = WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(plainFrame)));
            synchronized (assemblyLock) {
                assembly.endDecode();
            }
            return Result.completed(new Reassembled(message, key, assembly));
        } catch (IOException e) {
            synchronized (assemblyLock) {
                removeLocked(key, assembly);
            }
            logger.warning("net: dropped corrupt status sideband jumbo frame from " + peerName + ": " + e.getMessage());
            return Result.acceptedIncomplete();
        } catch (RuntimeException e) {
            synchronized (assemblyLock) {
                removeLocked(key, assembly);
            }
            logger.log(Level.WARNING, "net: failed to decode status sideband jumbo frame from " + peerName, e);
            return Result.acceptedIncomplete();
        } catch (Error e) {
            synchronized (assemblyLock) {
                removeLocked(key, assembly);
            }
            throw e;
        }
    }

    void discard(Reassembled reassembled) {
        synchronized (assemblyLock) {
            removeLocked(reassembled.key(), reassembled.assembly());
        }
    }

    void expire(long now) {
        synchronized (assemblyLock) {
            expireLocked(now);
        }
    }

    void forget(String peerName) {
        String prefix = peerName + ":";
        synchronized (assemblyLock) {
            Iterator<Map.Entry<String, Assembly>> iterator = assemblies.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Assembly> entry = iterator.next();
                if (entry.getKey().startsWith(prefix)) {
                    retainedBytes -= entry.getValue().retainedBytes();
                    iterator.remove();
                }
            }
        }
    }

    void clear() {
        synchronized (assemblyLock) {
            assemblies.clear();
            retainedBytes = 0L;
        }
    }

    long retainedBytes() {
        synchronized (assemblyLock) {
            return retainedBytes;
        }
    }

    int assemblyCount() {
        synchronized (assemblyLock) {
            return assemblies.size();
        }
    }

    private void warnFragmented(String peerName, WireMessage message, int frameLength, int fragments) {
        String key = peerName + ":" + message.type() + ":fragmented";
        if (oversizeWarnings.add(key)) {
            logger.warning("net: " + message.type() + " for " + peerName + " is " + frameLength + " bytes and will use " + fragments + " signed game-port sideband fragments; open the raw Wormholes port for high-throughput projection traffic");
        }
    }

    private void warnOversized(String peerName, WireMessage message, int frameLength) {
        String key = peerName + ":" + message.type();
        if (oversizeWarnings.add(key)) {
            logger.warning("net: " + message.type() + " for " + peerName + " is " + frameLength + " bytes and exceeds the Wormholes sideband jumbo frame limit; open the raw Wormholes port for high-throughput projection traffic");
        }
    }

    private static boolean isValid(WireMessage.SidebandFragment fragment) {
        if (fragment.total() <= 0 || fragment.total() > MAX_COUNT) {
            return false;
        }
        if (fragment.index() < 0 || fragment.index() >= fragment.total()) {
            return false;
        }
        if (fragment.frameLength() <= 0 || fragment.frameLength() > MAX_FRAME_BYTES) {
            return false;
        }
        int expectedTotal = (fragment.frameLength() + CHUNK_BYTES - 1) / CHUNK_BYTES;
        if (fragment.total() != expectedTotal || fragment.chunk() == null) {
            return false;
        }
        int expectedLength = fragment.index() == fragment.total() - 1
            ? fragment.frameLength() - (fragment.index() * CHUNK_BYTES)
            : CHUNK_BYTES;
        return fragment.chunk().length == expectedLength;
    }

    private static String key(String peerName, long messageId) {
        return peerName + ":" + messageId;
    }

    private void expireLocked(long now) {
        Iterator<Map.Entry<String, Assembly>> iterator = assemblies.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Assembly> entry = iterator.next();
            Assembly assembly = entry.getValue();
            if (!assembly.decoding() && now - assembly.createdAtMillis() > ASSEMBLY_TTL_MS) {
                retainedBytes -= assembly.retainedBytes();
                iterator.remove();
            }
        }
    }

    private void removeLocked(String key, Assembly assembly) {
        if (assembly != null && assemblies.remove(key, assembly)) {
            retainedBytes -= assembly.retainedBytes();
        }
    }

    record Reassembled(WireMessage message, String key, Assembly assembly) {
    }

    record Result(boolean accepted, Reassembled completed) {
        private static Result rejected() {
            return new Result(false, null);
        }

        private static Result acceptedIncomplete() {
            return new Result(true, null);
        }

        private static Result completed(Reassembled message) {
            return new Result(true, message);
        }
    }

    static final class Assembly {
        private final long messageId;
        private final int total;
        private final int frameLength;
        private final byte[][] chunks;
        private final long createdAtMillis;
        private int received;
        private int receivedBytes;
        private boolean decoding;

        private Assembly(WireMessage.SidebandFragment first, long createdAtMillis) {
            this.messageId = first.messageId();
            this.total = first.total();
            this.frameLength = first.frameLength();
            this.chunks = new byte[first.total()][];
            this.createdAtMillis = createdAtMillis;
        }

        private boolean accepts(WireMessage.SidebandFragment fragment) {
            return messageId == fragment.messageId()
                && total == fragment.total()
                && frameLength == fragment.frameLength();
        }

        private boolean add(WireMessage.SidebandFragment fragment) {
            if (!accepts(fragment)) {
                return false;
            }
            int index = fragment.index();
            if (index < 0 || index >= chunks.length || chunks[index] != null) {
                return false;
            }
            byte[] chunk = fragment.chunk();
            if (receivedBytes + chunk.length > frameLength) {
                return false;
            }
            chunks[index] = chunk;
            received++;
            receivedBytes += chunk.length;
            return true;
        }

        private boolean beginDecode() {
            if (decoding || received != total || receivedBytes != frameLength) {
                return false;
            }
            decoding = true;
            return true;
        }

        private void endDecode() {
            decoding = false;
        }

        private byte[] assemble() {
            byte[] frame = new byte[frameLength];
            int offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, frame, offset, chunk.length);
                offset += chunk.length;
            }
            return frame;
        }

        private long createdAtMillis() {
            return createdAtMillis;
        }

        private long retainedBytes() {
            return frameLength;
        }

        private boolean decoding() {
            return decoding;
        }
    }
}
