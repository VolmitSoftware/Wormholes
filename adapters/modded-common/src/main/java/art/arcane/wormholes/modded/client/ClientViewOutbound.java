package art.arcane.wormholes.modded.client;

import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamMessage;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class ClientViewOutbound {
    private static final int SEND_LIMIT = ViewStreamLimits.MAX_C2S_MESSAGES_PER_SECOND - 32;
    private static final int MESH_LIMIT = SEND_LIMIT - 32;
    private static final long WINDOW_NANOS = 1_000_000_000L;

    private final Consumer<ViewStreamMessage> sender;
    private final LongSupplier clock;
    private final ArrayDeque<ViewStreamMessage> control = new ArrayDeque<>();
    private final ArrayDeque<ViewStreamMessage> meshes = new ArrayDeque<>();
    private final HashSet<ViewStreamMessage.MeshAck> pendingAcks = new HashSet<>();
    private final long[] sent = new long[SEND_LIMIT];
    private final long[] meshSent = new long[MESH_LIMIT];
    private int sentCursor;
    private int meshCursor;

    public ClientViewOutbound(Consumer<ViewStreamMessage> sender, LongSupplier clock) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.clock = Objects.requireNonNull(clock, "clock");
        Arrays.fill(sent, Long.MIN_VALUE);
        Arrays.fill(meshSent, Long.MIN_VALUE);
    }

    public synchronized void send(ViewStreamMessage message) {
        if (message instanceof ViewStreamMessage.MeshAck ack && !pendingAcks.add(ack)) {
            return;
        }
        if (message instanceof ViewStreamMessage.MeshAck || message instanceof ViewStreamMessage.MeshLocal
            || message instanceof ViewStreamMessage.MeshCached) {
            meshes.addLast(message);
        } else {
            control.addLast(message);
        }
        flush();
    }

    public synchronized void flush() {
        long now = clock.getAsLong();
        while (available(sent[sentCursor], now)) {
            boolean mesh = control.isEmpty();
            if (mesh && (meshes.isEmpty() || !available(meshSent[meshCursor], now))) {
                return;
            }
            ViewStreamMessage message = mesh ? meshes.removeFirst() : control.removeFirst();
            if (message instanceof ViewStreamMessage.MeshAck ack) {
                pendingAcks.remove(ack);
            }
            sent[sentCursor] = now;
            sentCursor = (sentCursor + 1) % sent.length;
            if (mesh) {
                meshSent[meshCursor] = now;
                meshCursor = (meshCursor + 1) % meshSent.length;
            }
            sender.accept(message);
        }
    }

    public synchronized void clear() {
        control.clear();
        meshes.clear();
        pendingAcks.clear();
    }

    public synchronized int pending() {
        return control.size() + meshes.size();
    }

    private static boolean available(long timestamp, long now) {
        return timestamp == Long.MIN_VALUE || now - timestamp >= WINDOW_NANOS;
    }
}
