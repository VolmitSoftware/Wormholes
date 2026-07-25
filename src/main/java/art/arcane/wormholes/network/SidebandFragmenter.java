package art.arcane.wormholes.network;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

final class SidebandFragmenter {
    static final int MAX_FRAME_BYTES = WireCodec.MAX_FRAME_BYTES + Integer.BYTES;
    static final int CHUNK_BYTES = 4 * 1024;
    static final int MAX_COUNT = (MAX_FRAME_BYTES / CHUNK_BYTES) + 2;

    private static final long ASSEMBLY_TTL_MS = 15L * 60_000L;
    private static final int ASSEMBLY_CAPACITY = 128;
    private static final long EXPIRY_GATE_MS = 1_000L;

    private final NetworkManager network;
    private final Logger logger;
    private final Map<String, Assembly> assemblies = new ConcurrentHashMap<>();
    private final Set<String> oversizeWarnings = ConcurrentHashMap.newKeySet();
    private final AtomicLong messageIds = new AtomicLong();

    private volatile long nextExpiry;

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
        if (now >= nextExpiry) {
            nextExpiry = now + EXPIRY_GATE_MS;
            expire(now);
        }
        if (!isValid(fragment)) {
            return Result.acceptedIncomplete();
        }
        String key = key(peerName, fragment.messageId());
        if (!assemblies.containsKey(key) && assemblies.size() >= ASSEMBLY_CAPACITY) {
            return Result.rejected();
        }
        Assembly[] completedAssembly = new Assembly[1];
        assemblies.compute(key, (ignored, previous) -> {
            Assembly assembly = previous;
            if (assembly == null || !assembly.accepts(fragment)) {
                assembly = new Assembly(fragment, now);
            }
            assembly.add(fragment);
            if (assembly.isComplete()) {
                completedAssembly[0] = assembly;
            }
            return assembly;
        });
        Assembly assembly = completedAssembly[0];
        if (assembly == null) {
            return Result.acceptedIncomplete();
        }
        try {
            byte[] plainFrame = network.compression().decode(assembly.assemble()).payload();
            WireMessage message = WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(plainFrame)));
            return Result.completed(new Reassembled(message, key, assembly));
        } catch (IOException e) {
            assemblies.remove(key, assembly);
            logger.warning("net: dropped corrupt status sideband jumbo frame from " + peerName + ": " + e.getMessage());
            return Result.acceptedIncomplete();
        }
    }

    void discard(Reassembled reassembled) {
        assemblies.remove(reassembled.key(), reassembled.assembly());
    }

    void expire(long now) {
        assemblies.entrySet().removeIf(entry -> now - entry.getValue().createdAtMillis() > ASSEMBLY_TTL_MS);
    }

    void forget(String peerName) {
        String prefix = peerName + ":";
        assemblies.keySet().removeIf(key -> key.startsWith(prefix));
    }

    void clear() {
        assemblies.clear();
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
        return fragment.chunk() != null && fragment.chunk().length > 0 && fragment.chunk().length <= CHUNK_BYTES;
    }

    private static String key(String peerName, long messageId) {
        return peerName + ":" + messageId;
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

        private boolean isComplete() {
            return received == total && receivedBytes == frameLength;
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
    }
}
