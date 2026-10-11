package art.arcane.wormholes.render;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.netty.buffer.UnpooledByteBufAllocationHelper;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;

public final class ProjectionPacketOrigin {
    private static final ReferenceQueue<Object> RETIRED = new ReferenceQueue<Object>();
    private static final Map<BufferToken, Consumer<Boolean>> TOKENS = new HashMap<BufferToken, Consumer<Boolean>>();

    private static final ThreadLocal<Consumer<Boolean>> COMPLETION = new ThreadLocal<Consumer<Boolean>>();
    private static final Consumer<Boolean> NO_COMPLETION = success -> { };

    private ProjectionPacketOrigin() {
    }

    static void withCompletion(Consumer<Boolean> completion, Runnable send) {
        Consumer<Boolean> previous = COMPLETION.get();
        COMPLETION.set(completion);
        try {
            send.run();
        } finally {
            if (previous == null) {
                COMPLETION.remove();
            } else {
                COMPLETION.set(previous);
            }
        }
    }

    static Consumer<Boolean> currentCompletion() {
        return COMPLETION.get();
    }

    static void prepare(Player observer, PacketWrapper<?> packet) {
        Object channel = PacketEvents.getAPI().getPlayerManager().getChannel(observer);
        Object buffer = channel == null ? UnpooledByteBufAllocationHelper.buffer() : ChannelHelper.pooledByteBuf(channel);
        packet.setBuffer(buffer);
        mark(buffer, observer.getUniqueId());
    }

    static void discard(PacketWrapper<?> packet) {
        Object buffer = packet.getBuffer();
        if (buffer != null && consume(buffer)) {
            packet.setBuffer(null);
            ByteBufHelper.release(buffer);
        }
    }

    public static void send(Player observer, PacketWrapper<?> packet) {
        prepare(observer, packet);
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(observer, packet);
        } catch (RuntimeException failure) {
            discard(packet);
            throw failure;
        }
    }

    static boolean managed(Consumer<Boolean> completion) {
        return completion != null && completion != NO_COMPLETION;
    }

    static synchronized void forget(UUID observerId) {
        TOKENS.keySet().removeIf(token -> observerId.equals(token.observerId));
        drain();
    }

    static synchronized void clear() {
        TOKENS.clear();
        drain();
    }

    static void mark(Object buffer) {
        mark(buffer, null);
    }

    private static synchronized void mark(Object buffer, UUID observerId) {
        drain();
        Consumer<Boolean> completion = COMPLETION.get();
        TOKENS.put(new BufferToken(buffer, RETIRED, observerId), completion == null ? NO_COMPLETION : completion);
    }

    static boolean consume(Object buffer) {
        return consumeCompletion(buffer) != null;
    }

    static synchronized Consumer<Boolean> consumeCompletion(Object buffer) {
        drain();
        return TOKENS.remove(new BufferToken(buffer, null, null));
    }

    private static void drain() {
        BufferToken retired;
        while ((retired = (BufferToken) RETIRED.poll()) != null) {
            TOKENS.remove(retired);
        }
    }

    private static final class BufferToken extends WeakReference<Object> {
        private final int identity;
        private final UUID observerId;

        private BufferToken(Object buffer, ReferenceQueue<Object> queue, UUID observerId) {
            super(buffer, queue);
            identity = System.identityHashCode(buffer);
            this.observerId = observerId;
        }

        @Override
        public int hashCode() {
            return identity;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            Object buffer = get();
            return buffer != null && other instanceof BufferToken token && buffer == token.get();
        }
    }
}
