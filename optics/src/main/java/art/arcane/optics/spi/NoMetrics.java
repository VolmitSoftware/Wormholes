package art.arcane.optics.spi;

final class NoMetrics implements OpticsMetrics {
    static final NoMetrics INSTANCE = new NoMetrics();

    private NoMetrics() {
    }

    @Override
    public void failure(String reason) {
    }

    @Override
    public void packet() {
    }

    @Override
    public void count(String key, long delta) {
    }

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }
}
