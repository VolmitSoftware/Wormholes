package art.arcane.wormholes.network.client;

public record RemoteViewOptions(boolean enabled, int routes, int chunksPerTick, int bytesPerTick) {
    public static final int MAX_ROUTES = 4;
    public static final int MAX_CHUNKS_PER_TICK = 64;
    public static final int MIN_BYTES_PER_TICK = 16 * 1024;
    public static final int MAX_BYTES_PER_TICK = 2 * 1024 * 1024;
    public static final RemoteViewOptions DEFAULT = new RemoteViewOptions(true, 2, 8, 192 * 1024);

    public RemoteViewOptions {
        routes = Math.max(1, Math.min(MAX_ROUTES, routes));
        chunksPerTick = Math.max(1, Math.min(MAX_CHUNKS_PER_TICK, chunksPerTick));
        bytesPerTick = Math.max(MIN_BYTES_PER_TICK, Math.min(MAX_BYTES_PER_TICK, bytesPerTick));
    }

    public long withheldCaps() {
        return enabled ? 0L : ClientViewExtensions.REMOTE_VIEW | ClientViewExtensions.SEAMLESS_TRAVEL;
    }
}
