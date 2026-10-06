package art.arcane.optics.spi;

public interface OpticsMetrics {
    static OpticsMetrics none() {
        return NoMetrics.INSTANCE;
    }

    void failure(String reason);

    void packet();

    void count(String key, long delta);

    long nanoTime();
}
