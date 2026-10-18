package art.arcane.wormholes.network.client;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ArrivalChunkBuffer {
    private final ArrivalMessage.Prepare prepare;
    private final long budget;
    private final Map<Long, Assembly> pending = new HashMap<>();
    private final Set<Long> received = new HashSet<>();
    private long bytes;

    public ArrivalChunkBuffer(ArrivalMessage.Prepare prepare, long budget) {
        this.prepare = Objects.requireNonNull(prepare, "prepare");
        if (budget < 1) {
            throw new IllegalArgumentException("Arrival memory budget must be positive");
        }
        this.budget = budget;
    }

    public Optional<byte[]> accept(ArrivalMessage.Chunk chunk) {
        if (!prepare.token().equals(chunk.token()) || prepare.revision() != chunk.revision()) {
            return Optional.empty();
        }
        if (!prepare.contains(chunk.x(), chunk.z())) {
            throw new IllegalArgumentException("Arrival chunk is outside the prepared area");
        }
        long key = ((long) chunk.x() << 32) | (chunk.z() & 0xffffffffL);
        if (received.contains(key)) {
            return Optional.empty();
        }
        Assembly assembly = pending.get(key);
        if (assembly == null) {
            if (bytes + chunk.totalBytes() > budget) {
                throw new IllegalArgumentException("Arrival chunk memory budget exceeded");
            }
            assembly = new Assembly(new byte[chunk.totalBytes()], new BitSet(chunk.fragmentCount()));
            pending.put(key, assembly);
            bytes += chunk.totalBytes();
        }
        if (assembly.payload().length != chunk.totalBytes()) {
            throw new IllegalArgumentException("Arrival chunk changed within a preparation");
        }
        if (assembly.fragments().get(chunk.fragmentIndex())) {
            return Optional.empty();
        }
        byte[] fragment = chunk.payload();
        System.arraycopy(fragment, 0, assembly.payload(), chunk.fragmentIndex() * ArrivalMessage.FRAGMENT_BYTES, fragment.length);
        assembly.fragments().set(chunk.fragmentIndex());
        if (assembly.fragments().cardinality() != chunk.fragmentCount()) {
            return Optional.empty();
        }
        pending.remove(key);
        received.add(key);
        return Optional.of(assembly.payload());
    }

    public int receivedChunks() {
        return received.size();
    }

    public long bytes() {
        return bytes;
    }

    private record Assembly(byte[] payload, BitSet fragments) {
    }
}
