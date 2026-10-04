package art.arcane.wormholes.network.client;

import java.util.Locale;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

public final class ClientViewHandshake {
    private static final String VANILLA_BRAND = "vanilla";

    private final Policy policy;
    private final long zeroCopyNonce;
    private final IntSupplier sessionIds;
    private final LongSupplier salts;
    private State state;
    private Brand brand;
    private long offeredAt;
    private long deadline;
    private ClientViewMessage.Accept accepted;

    public ClientViewHandshake(Policy policy, long zeroCopyNonce, IntSupplier sessionIds, LongSupplier salts) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.zeroCopyNonce = zeroCopyNonce;
        this.sessionIds = Objects.requireNonNull(sessionIds, "sessionIds");
        this.salts = Objects.requireNonNull(salts, "salts");
        this.state = State.INIT;
        this.brand = Brand.UNKNOWN;
    }

    public static Brand classifyBrand(String brandTag) {
        if (brandTag == null || brandTag.isBlank()) {
            return Brand.UNKNOWN;
        }
        return brandTag.trim().toLowerCase(Locale.ROOT).equals(VANILLA_BRAND) ? Brand.VANILLA : Brand.MODDED;
    }

    public static ClientViewMessage.Hello clientHello(ClientViewMessage.Offer offer, int mcDataVersion, long clientCaps, int maxFrameBytes,
                                                      int plateMemoryMb, long nonceFound, String brandTag) {
        long echo = offer.zeroCopyNonce() != 0L && offer.zeroCopyNonce() == nonceFound ? nonceFound : 0L;
        return new ClientViewMessage.Hello(ClientViewProtocol.WIRE_VERSION, mcDataVersion, clientCaps & ClientViewCapability.ALL,
            ClientViewProtocol.clampMaxFrameBytes(maxFrameBytes), Math.max(0, Math.min(65535, plateMemoryMb)), echo,
            brandTag == null ? "" : brandTag);
    }

    public State state() {
        return state;
    }

    public Brand brand() {
        return brand;
    }

    public long deadlineMillis() {
        return deadline;
    }

    public ClientViewMessage.Accept accepted() {
        return accepted;
    }

    public ClientViewMessage.Offer offer(long nowMillis) {
        if (state != State.INIT) {
            throw new IllegalStateException("offer already sent in state " + state);
        }
        state = State.OFFERED;
        offeredAt = nowMillis;
        deadline = brand == Brand.VANILLA ? nowMillis : nowMillis + policy.helloGraceMillis();
        return new ClientViewMessage.Offer(ClientViewProtocol.WIRE_VERSION, policy.mcDataVersion(), policy.serverCaps() & ClientViewCapability.ALL,
            ClientViewProtocol.clampMaxFrameBytes(policy.maxFrameBytes()), zeroCopyNonce);
    }

    public void brand(String brandTag, long nowMillis) {
        Brand classified = classifyBrand(brandTag);
        if (classified == Brand.UNKNOWN) {
            return;
        }
        brand = classified;
        if (state == State.OFFERED && classified == Brand.VANILLA) {
            deadline = nowMillis;
        }
    }

    public boolean waiting(long nowMillis) {
        return state == State.OFFERED && nowMillis < deadline;
    }

    public Result onPong(long nowMillis) {
        if (state == State.OFFERED && brand == Brand.VANILLA) {
            state = State.VANILLA;
            return new Result(State.VANILLA, null, false);
        }
        return new Result(state, null, false);
    }

    public Result onDeadline(long nowMillis) {
        if (state == State.OFFERED && nowMillis >= deadline) {
            state = State.VANILLA;
        }
        return new Result(state, null, false);
    }

    public Result onHello(ClientViewMessage.Hello hello, long nowMillis, boolean capacityAvailable) {
        Objects.requireNonNull(hello, "hello");
        if (state != State.OFFERED && state != State.VANILLA) {
            return new Result(state, null, false);
        }
        boolean late = state == State.VANILLA || nowMillis > deadline;
        ClientViewMessage.DeclineReason reason = declineReason(hello, capacityAvailable);
        if (reason != null) {
            state = State.DECLINED;
            return new Result(state, new ClientViewMessage.Decline(reason), late);
        }
        long caps = ClientViewCapability.intersection(policy.serverCaps(), hello.clientCaps());
        if (!ClientViewCapability.PREPARED_TRAVEL.in(caps) || !ClientViewCapability.MESH_RENDER.in(caps)) {
            caps &= ~ClientViewCapability.PREPARED_TRAVEL_CACHE.mask();
        }
        if (!ClientViewCapability.ENTITY_FRAMES.in(caps)) {
            caps &= ~ClientViewCapability.ENTITY_SELF.mask();
        }
        boolean zeroCopy = policy.zeroCopy() && zeroCopyNonce != 0L && hello.zeroCopyNonceEcho() == zeroCopyNonce;
        if (!zeroCopy) {
            caps &= ~ClientViewCapability.ZERO_COPY.mask();
        }
        int maxFrameBytes = ClientViewProtocol.clampMaxFrameBytes(Math.min(policy.maxFrameBytes(), hello.maxFrameBytes()));
        accepted = new ClientViewMessage.Accept(sessionIds.getAsInt(), caps, policy.tickRate(), maxFrameBytes, salts.getAsLong(),
            Math.max(0, Math.min(255, policy.ackWindowFrames())));
        state = State.CLIENT_VIEW;
        return new Result(state, accepted, late);
    }

    public long offeredAtMillis() {
        return offeredAt;
    }

    private ClientViewMessage.DeclineReason declineReason(ClientViewMessage.Hello hello, boolean capacityAvailable) {
        if (hello.wire() != ClientViewProtocol.WIRE_VERSION) {
            return ClientViewMessage.DeclineReason.WIRE_MISMATCH;
        }
        if (hello.mcDataVersion() != policy.mcDataVersion()) {
            return ClientViewMessage.DeclineReason.DATA_VERSION_MISMATCH;
        }
        if (!policy.enabled()) {
            return ClientViewMessage.DeclineReason.DISABLED;
        }
        if (!capacityAvailable) {
            return ClientViewMessage.DeclineReason.CAPACITY;
        }
        return null;
    }

    public enum State {
        INIT,
        OFFERED,
        CLIENT_VIEW,
        VANILLA,
        DECLINED
    }

    public enum Brand {
        UNKNOWN,
        VANILLA,
        MODDED
    }

    public record Policy(boolean enabled,
                         int mcDataVersion,
                         long serverCaps,
                         int maxFrameBytes,
                         int helloGraceMillis,
                         int tickRate,
                         int ackWindowFrames,
                         boolean zeroCopy) {
        public Policy {
            helloGraceMillis = Math.max(0, helloGraceMillis);
            tickRate = Math.max(1, Math.min(255, tickRate));
        }

        public static Policy defaults(int mcDataVersion) {
            return new Policy(true, mcDataVersion, ClientViewCapability.ALL, ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES,
                ClientViewProtocol.DEFAULT_HELLO_GRACE_MILLIS, ClientViewProtocol.DEFAULT_TICK_RATE,
                ClientViewProtocol.DEFAULT_ACK_WINDOW_FRAMES, true);
        }
    }

    public record Result(State state, ClientViewMessage reply, boolean late) {
        public boolean accepted() {
            return state == State.CLIENT_VIEW;
        }
    }
}
