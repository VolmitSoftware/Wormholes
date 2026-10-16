package art.arcane.wormholes.clientgametest;

import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

public final class QaReplies {
    private static final String PREFIX = "[whqa] ";
    private static final String ERROR = "error ";
    private static final int RECENT_LIMIT = 64;
    private static final BlockingQueue<String> REPLIES = new LinkedBlockingQueue<>();
    private static final Deque<String> RECENT = new ArrayDeque<>(RECENT_LIMIT);

    private QaReplies() {
    }

    public static void received(String text) {
        synchronized (RECENT) {
            if (RECENT.size() == RECENT_LIMIT) {
                RECENT.removeFirst();
            }
            RECENT.addLast(text);
        }
        if (text.startsWith(PREFIX)) {
            REPLIES.add(text.substring(PREFIX.length()));
        }
    }

    public static List<String> recent() {
        synchronized (RECENT) {
            return List.copyOf(RECENT);
        }
    }

    public static void clearRecent() {
        synchronized (RECENT) {
            RECENT.clear();
        }
    }

    static String command(TestClient client, String command, String reply, int timeoutTicks) {
        return command(client, command, reply, timeoutTicks, minecraft -> {
        });
    }

    static String command(TestClient client, String command, String reply, int timeoutTicks, Consumer<Minecraft> sameTick) {
        REPLIES.clear();
        client.runOnClient(minecraft -> {
            minecraft.getConnection().sendCommand(command);
            sameTick.accept(minecraft);
        });
        for (int tick = 0; tick <= timeoutTicks; tick++) {
            String received = REPLIES.poll();
            while (received != null) {
                if (received.startsWith(ERROR)) {
                    throw new AssertionError("/" + command + " failed on the server: " + received.substring(ERROR.length()));
                }
                if (received.startsWith(reply + " ") || received.equals(reply)) {
                    return received.substring(Math.min(received.length(), reply.length() + 1));
                }
                received = REPLIES.poll();
            }
            client.waitTicks(1);
        }
        throw new AssertionError("/" + command + " got no '" + reply + "' reply within " + timeoutTicks + " ticks");
    }
}
