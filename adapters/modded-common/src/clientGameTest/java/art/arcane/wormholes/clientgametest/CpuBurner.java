package art.arcane.wormholes.clientgametest;

import java.util.ArrayList;
import java.util.List;

final class CpuBurner implements AutoCloseable {
    private final List<Thread> threads;
    private volatile boolean running = true;
    private volatile long sink;

    private CpuBurner(int count) {
        threads = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Thread thread = new Thread(this::burn, "Wormholes client gametest CPU burner " + index);
            thread.setDaemon(true);
            threads.add(thread);
        }
    }

    static CpuBurner start() {
        CpuBurner burner = new CpuBurner(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        for (Thread thread : burner.threads) {
            thread.start();
        }
        return burner;
    }

    @Override
    public void close() {
        running = false;
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while stopping the CPU burner", interrupted);
            }
        }
    }

    private void burn() {
        long value = 1L;
        while (running) {
            value = value * 6364136223846793005L + 1442695040888963407L;
        }
        sink = value;
    }
}
