package art.arcane.wormholes.modded.client;

import art.arcane.optics.stream.ViewStreamCodec;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.network.client.ClientViewExtensions;

public final class ClientViewReceiver {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private final ClientViewSession session;
    private Consumer<TravelMessage> travel = ignored -> { };
    private final ConcurrentLinkedQueue<Queued> queue;
    private final AtomicInteger queued;
    private final AtomicLong decodeFailures;
    private final AtomicLong replyFailures;
    private final AtomicLong received;
    private final AtomicLong receivedBytes;

    public ClientViewReceiver(ClientViewSession session) {
        this.session = Objects.requireNonNull(session, "session");
        this.queue = new ConcurrentLinkedQueue<>();
        this.queued = new AtomicInteger();
        this.decodeFailures = new AtomicLong();
        this.replyFailures = new AtomicLong();
        this.received = new AtomicLong();
        this.receivedBytes = new AtomicLong();
    }

    public void travel(Consumer<TravelMessage> receiver) {
        travel = Objects.requireNonNull(receiver);
    }

    public void receive(byte[] payload, Consumer<byte[]> reply) {
        Objects.requireNonNull(payload, "payload");
        received.incrementAndGet();
        receivedBytes.addAndGet(payload.length);
        ViewStreamCodec.S2CFrame frame;
        try {
            frame = ClientViewExtensions.CODEC.decodeS2C(payload, session.caps());
        } catch (ViewStreamProtocolException | RuntimeException failure) {
            decodeFailures.incrementAndGet();
            return;
        }
        switch (frame.message()) {
            case ViewStreamMessage.Offer offer -> {
                ViewStreamMessage.Hello hello = session.offer(offer);
                enqueue(new Queued(new ViewStreamCodec.S2CFrame(frame.seq(), 0, offer), payload.length, System.nanoTime()));
                if (hello != null && reply != null && !send(reply, hello)) {
                    session.unanswered();
                }
            }
            case ViewStreamMessage.Accept accept -> session.accept(accept);
            case ViewStreamMessage.Decline decline -> session.decline(decline);
            case ViewStreamMessage.Extension extension when extension.payload() instanceof TravelMessage message ->
                prepared(frame, message, payload.length);
            default -> enqueue(new Queued(frame, payload.length, System.nanoTime()));
        }
    }

    public int drain(List<Queued> out, int max) {
        int drained = 0;
        while (drained < max) {
            Queued next = queue.poll();
            if (next == null) {
                break;
            }
            queued.decrementAndGet();
            out.add(next);
            drained++;
        }
        return drained;
    }

    public void clear() {
        queue.clear();
        queued.set(0);
    }

    public int pending() {
        return queued.get();
    }

    public long decodeFailures() {
        return decodeFailures.get();
    }

    public long received() {
        return received.get();
    }

    public long receivedBytes() {
        return receivedBytes.get();
    }

    public long replyFailures() {
        return replyFailures.get();
    }

    private void prepared(ViewStreamCodec.S2CFrame frame, TravelMessage message, int bytes) {
        if (session.active() && session.has(ViewStreamCapability.PREPARED_TRAVEL)) {
            travel.accept(message);
            enqueue(new Queued(frame, bytes, System.nanoTime()));
        }
    }

    private void enqueue(Queued next) {
        queue.add(next);
        queued.incrementAndGet();
    }

    private boolean send(Consumer<byte[]> reply, ViewStreamMessage message) {
        try {
            reply.accept(ClientViewExtensions.CODEC.encodeC2S(message));
            return true;
        } catch (ViewStreamProtocolException | RuntimeException failure) {
            replyFailures.incrementAndGet();
            LOGGER.warn("Wormholes ClientView could not answer the server offer with {}; awaiting connection recovery", ClientViewExtensions.CODEC.name(message), failure);
            return false;
        }
    }

    public record Queued(ViewStreamCodec.S2CFrame frame, int bytes, long receivedNanos) {
    }
}
