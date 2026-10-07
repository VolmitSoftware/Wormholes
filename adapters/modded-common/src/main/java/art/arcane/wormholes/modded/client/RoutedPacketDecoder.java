package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.seamless.RoutedPackets;
import art.arcane.wormholes.network.client.TravelMessage;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class RoutedPacketDecoder {
    static final long MAX_PENDING_BYTES = 16L << 20;

    private final Int2ObjectOpenHashMap<Handle> handles = new Int2ObjectOpenHashMap<>();
    private long pendingBytes;

    List<byte[]> accept(TravelMessage.RoutedPacket fragment) {
        Handle handle = handles.computeIfAbsent(fragment.levelHandle(), ignored -> new Handle());
        if (fragment.sequence() <= handle.lastSequence) {
            return List.of();
        }
        Assembly assembly = handle.assemblies.get(fragment.sequence());
        if (assembly == null) {
            if (pendingBytes + fragment.totalBytes() > MAX_PENDING_BYTES) {
                throw new IllegalArgumentException("Routed packets for level " + fragment.levelHandle() + " exceed "
                    + MAX_PENDING_BYTES + " pending bytes");
            }
            assembly = new Assembly(fragment);
            handle.assemblies.put(fragment.sequence(), assembly);
            pendingBytes += fragment.totalBytes();
        }
        assembly.add(fragment);
        if (assembly.payload == null && assembly.complete()) {
            assembly.payload = RoutedPackets.join(Arrays.asList(assembly.fragments));
        }
        return release(handle);
    }

    int lastSequence(int levelHandle) {
        Handle handle = handles.get(levelHandle);
        return handle == null ? -1 : handle.lastSequence;
    }

    void forget(int levelHandle) {
        Handle handle = handles.remove(levelHandle);
        if (handle != null) {
            for (Assembly assembly : handle.assemblies.values()) {
                pendingBytes -= assembly.totalBytes;
            }
        }
    }

    void clear() {
        handles.clear();
        pendingBytes = 0L;
    }

    long pendingBytes() {
        return pendingBytes;
    }

    private List<byte[]> release(Handle handle) {
        List<byte[]> released = List.of();
        while (!handle.assemblies.isEmpty()) {
            int sequence = handle.assemblies.firstIntKey();
            Assembly first = handle.assemblies.get(sequence);
            if (first.payload == null) {
                break;
            }
            handle.assemblies.remove(sequence);
            pendingBytes -= first.totalBytes;
            handle.lastSequence = sequence;
            if (released.isEmpty()) {
                released = new ArrayList<>(2);
            }
            released.add(first.payload);
        }
        return released;
    }

    private static final class Handle {
        private final Int2ObjectSortedMap<Assembly> assemblies = new Int2ObjectRBTreeMap<>();
        private int lastSequence = -1;
    }

    private static final class Assembly {
        private final TravelMessage.RoutedPacket[] fragments;
        private final int totalBytes;
        private int received;
        private byte[] payload;

        private Assembly(TravelMessage.RoutedPacket first) {
            fragments = new TravelMessage.RoutedPacket[first.fragmentCount()];
            totalBytes = first.totalBytes();
        }

        private void add(TravelMessage.RoutedPacket fragment) {
            if (fragment.fragmentCount() != fragments.length || fragment.totalBytes() != totalBytes) {
                throw new IllegalArgumentException("Routed packet " + fragment.sequence() + " changed its fragment layout");
            }
            if (fragments[fragment.fragmentIndex()] == null) {
                fragments[fragment.fragmentIndex()] = fragment;
                received++;
            }
        }

        private boolean complete() {
            return received == fragments.length;
        }
    }
}
