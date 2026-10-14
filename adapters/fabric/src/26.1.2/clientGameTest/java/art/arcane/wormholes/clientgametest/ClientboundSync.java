package art.arcane.wormholes.clientgametest;

import java.util.concurrent.atomic.AtomicInteger;

public final class ClientboundSync {
    private static final int PREFIX = 0x57480000;
    private static final int MASK = 0xFFFF0000;
    private static final AtomicInteger NEXT = new AtomicInteger();
    private static volatile int received;

    private ClientboundSync() {
    }

    public static void received(int id) {
        if ((id & MASK) == PREFIX) {
            received = id;
        }
    }

    static int next() {
        return PREFIX | (NEXT.incrementAndGet() & ~MASK);
    }

    static boolean reached(int id) {
        return received == id;
    }
}
