package art.arcane.wormholes.modded.client.menu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class ClientMenus {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static volatile ClientMenuSession session;
    private static volatile BooleanSupplier channelReady = () -> false;

    private ClientMenus() {
    }

    public static ClientMenuSession initialize(Consumer<byte[]> sender, BooleanSupplier channel) {
        ClientMenuSession created = new ClientMenuSession(sender, ClientMenus::monotonicMillis, LOGGER);
        channelReady = Objects.requireNonNull(channel, "channel");
        session = created;
        return created;
    }

    public static ClientMenuSession session() {
        return Objects.requireNonNull(session, "client menus are not initialized");
    }

    public static void connected() {
        ClientMenuSession current = session;
        if (current != null) {
            current.connected();
        }
    }

    public static void disconnected() {
        ClientMenuSession current = session;
        if (current != null) {
            current.disconnected();
        }
    }

    public static void tick() {
        ClientMenuSession current = session;
        if (current != null) {
            current.tick(channelReady);
        }
    }

    public static void receive(byte[] frame) {
        ClientMenuSession current = session;
        if (current != null) {
            current.receive(frame);
        }
    }

    private static long monotonicMillis() {
        return System.nanoTime() / NANOS_PER_MILLI;
    }
}
