package art.arcane.wormholes.modded.client;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import art.arcane.wormholes.network.client.TravelMessage;

final class ClientTravelChunks {
    private final UUID token;
    private final long generation;
    private final Set<TravelMessage.TravelCoordinate> manifest;
    private final Map<TravelMessage.TravelCoordinate, Assembly> pending = new HashMap<>();
    private final Map<TravelMessage.TravelCoordinate, Integer> completed = new HashMap<>();
    private final Map<TravelMessage.TravelCoordinate, Integer> sizes = new HashMap<>();
    private long bytes;
    private TravelMessage.TravelEnd end;

    ClientTravelChunks(TravelMessage.TravelBegin begin) {
        token = begin.token();
        generation = begin.generation();
        manifest = new HashSet<>(begin.chunks());
    }

    boolean matches(UUID candidate, long epoch) {
        return token.equals(candidate) && generation == epoch;
    }

    byte[] accept(TravelMessage.TravelChunk fragment) {
        TravelMessage.TravelCoordinate coordinate = new TravelMessage.TravelCoordinate(fragment.chunkX(), fragment.chunkZ());
        if (!matches(fragment.token(), fragment.generation()) || !manifest.contains(coordinate)
            || completed.getOrDefault(coordinate, 0) >= fragment.revision()) {
            return null;
        }
        Assembly assembly = pending.get(coordinate);
        if (assembly != null && assembly.revision > fragment.revision()) {
            return null;
        }
        if (assembly == null || assembly.revision != fragment.revision()) {
            if (assembly != null) {
                bytes -= assembly.data.length;
            }
            if (bytes + fragment.totalBytes() > TravelMessage.MAX_TRAVEL_BYTES) {
                throw new IllegalArgumentException("Prepared travel exceeds its chunk byte budget");
            }
            assembly = new Assembly(fragment);
            pending.put(coordinate, assembly);
            bytes += assembly.data.length;
        }
        if (assembly.data.length != fragment.totalBytes() || assembly.fragments != fragment.fragmentCount()) {
            throw new IllegalArgumentException("Prepared travel fragment changed its chunk dimensions");
        }
        if (assembly.received.get(fragment.fragmentIndex())) {
            return null;
        }
        byte[] payload = fragment.payload();
        System.arraycopy(payload, 0, assembly.data, fragment.fragmentIndex() * TravelMessage.TRAVEL_FRAGMENT_BYTES, payload.length);
        assembly.received.set(fragment.fragmentIndex());
        if (assembly.received.cardinality() != assembly.fragments) {
            return null;
        }
        pending.remove(coordinate);
        completed.put(coordinate, assembly.revision);
        bytes -= sizes.getOrDefault(coordinate, 0);
        sizes.put(coordinate, assembly.data.length);
        return assembly.data;
    }

    boolean reuse(TravelMessage.TravelReuse proof, byte[] data) {
        TravelMessage.TravelCoordinate coordinate = new TravelMessage.TravelCoordinate(proof.chunkX(), proof.chunkZ());
        if (!matches(proof.token(), proof.generation()) || !manifest.contains(coordinate)) {
            return false;
        }
        int revision = completed.getOrDefault(coordinate, 0);
        Assembly assembly = pending.get(coordinate);
        if (revision > proof.revision() || assembly != null && assembly.revision > proof.revision()) {
            return false;
        }
        if (revision == proof.revision()) {
            return true;
        }
        long replacementBytes = bytes - sizes.getOrDefault(coordinate, 0) - (assembly == null ? 0 : assembly.data.length) + data.length;
        if (replacementBytes > TravelMessage.MAX_TRAVEL_BYTES) {
            throw new IllegalArgumentException("Prepared travel exceeds its chunk byte budget");
        }
        pending.remove(coordinate);
        completed.put(coordinate, proof.revision());
        sizes.put(coordinate, data.length);
        bytes = replacementBytes;
        return true;
    }

    void end(TravelMessage.TravelEnd message) {
        if (!matches(message.token(), message.generation()) || end != null && end.contentRevision() >= message.contentRevision()) {
            return;
        }
        HashSet<TravelMessage.TravelCoordinate> coordinates = new HashSet<>();
        for (TravelMessage.TravelChunkRevision chunk : message.chunks()) {
            coordinates.add(new TravelMessage.TravelCoordinate(chunk.x(), chunk.z()));
        }
        if (!coordinates.equals(manifest)) {
            throw new IllegalArgumentException("Prepared travel completion changed its chunk manifest");
        }
        end = message;
    }

    long completeRevision() {
        if (end == null || !pending.isEmpty()) {
            return 0;
        }
        for (TravelMessage.TravelChunkRevision chunk : end.chunks()) {
            if (completed.getOrDefault(new TravelMessage.TravelCoordinate(chunk.x(), chunk.z()), 0) != chunk.revision()) {
                return 0;
            }
        }
        return end.contentRevision();
    }

    private static final class Assembly {
        private final int revision;
        private final int fragments;
        private final byte[] data;
        private final BitSet received;

        private Assembly(TravelMessage.TravelChunk fragment) {
            revision = fragment.revision();
            fragments = fragment.fragmentCount();
            data = new byte[fragment.totalBytes()];
            received = new BitSet(fragments);
        }
    }
}
