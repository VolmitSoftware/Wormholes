package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.ClientViewCodec;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class ClientViewReceiver {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private final ClientViewSession session;
    private Consumer<ClientViewMessage> travel = ignored -> { };
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

    public void travel(Consumer<ClientViewMessage> receiver) {
        travel = Objects.requireNonNull(receiver);
    }

    public void receive(byte[] payload, Consumer<byte[]> reply) {
        Objects.requireNonNull(payload, "payload");
        received.incrementAndGet();
        receivedBytes.addAndGet(payload.length);
        ClientViewCodec.S2CFrame frame;
        try {
            frame = ClientViewCodec.decodeS2C(payload, session.caps());
        } catch (ClientViewProtocolException | RuntimeException failure) {
            decodeFailures.incrementAndGet();
            return;
        }
        switch (frame.message()) {
            case ClientViewMessage.Offer offer -> {
                ClientViewMessage.Hello hello = session.offer(offer);
                enqueue(new Queued(new ClientViewCodec.S2CFrame(frame.seq(), 0, offer), payload.length, System.nanoTime()));
                if (hello != null && reply != null && !send(reply, hello)) {
                    session.unanswered();
                }
            }
            case ClientViewMessage.Accept accept -> session.accept(accept);
            case ClientViewMessage.Decline decline -> session.decline(decline);
            case ClientViewMessage.TravelBegin ignored -> prepared(frame, payload.length);
            case ClientViewMessage.TravelChunk ignored -> prepared(frame, payload.length);
            case ClientViewMessage.TravelEnd ignored -> prepared(frame, payload.length);
            case ClientViewMessage.TravelCommit ignored -> prepared(frame, payload.length);
            case ClientViewMessage.TravelCancel ignored -> prepared(frame, payload.length);
            case ClientViewMessage.TravelReuse ignored -> prepared(frame, payload.length);
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

    private void prepared(ClientViewCodec.S2CFrame frame, int bytes) {
        if (session.active() && session.has(ClientViewCapability.PREPARED_TRAVEL)) {
            travel.accept(frame.message());
            enqueue(new Queued(frame, bytes, System.nanoTime()));
        }
    }

    private void enqueue(Queued next) {
        queue.add(next);
        queued.incrementAndGet();
    }

    private boolean send(Consumer<byte[]> reply, ClientViewMessage message) {
        try {
            reply.accept(ClientViewCodec.encodeC2S(message));
            return true;
        } catch (ClientViewProtocolException | RuntimeException failure) {
            replyFailures.incrementAndGet();
            LOGGER.warn("Wormholes ClientView could not answer the server offer with {}; awaiting connection recovery", message.type(), failure);
            return false;
        }
    }

    public record Queued(ClientViewCodec.S2CFrame frame, int bytes, long receivedNanos) {
    }
}
