package art.arcane.wormholes.ops.console;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Read-only HTTP endpoint serving OpenMetrics at /metrics and a JSON snapshot at /snapshot. It is
 * off by default, binds loopback by default, and refuses to start without a bearer token.
 */
public final class MetricsEndpoint {
    private static final String METRICS_PATH = "/metrics";
    private static final String SNAPSHOT_PATH = "/snapshot";
    private static final int BACKLOG = 4;
    private static final int STOP_DELAY_SECONDS = 1;

    private final String bind;
    private final int port;
    private final String token;
    private final MetricsSource source;
    private final MetricsHistory history;

    private HttpServer server;
    private ExecutorService executor;

    public MetricsEndpoint(String bind, int port, String token, MetricsSource source, MetricsHistory history) {
        this.bind = bind == null || bind.isBlank() ? "127.0.0.1" : bind.trim();
        this.port = port;
        this.token = token == null ? "" : token.trim();
        this.source = Objects.requireNonNull(source, "source");
        this.history = Objects.requireNonNull(history, "history");
    }

    /** @throws IllegalStateException when the token is empty, IOException when the bind fails. */
    public void start() throws IOException {
        if (token.isEmpty()) {
            throw new IllegalStateException("Metrics endpoint requires a token");
        }
        if (server != null) {
            return;
        }
        HttpServer started = HttpServer.create(new InetSocketAddress(bind, Math.max(0, port)), BACKLOG);
        ExecutorService pool = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "wormholes-metrics");
            thread.setDaemon(true);
            return thread;
        });
        started.setExecutor(pool);
        started.createContext("/", this::handle);
        started.start();
        this.server = started;
        this.executor = pool;
    }

    public int boundPort() {
        return server == null ? 0 : server.getAddress().getPort();
    }

    public String boundAddress() {
        return bind + ":" + boundPort();
    }

    public boolean isRunning() {
        return server != null;
    }

    public void stop() {
        HttpServer running = server;
        server = null;
        if (running != null) {
            running.stop(STOP_DELAY_SECONDS);
        }
        ExecutorService pool = executor;
        executor = null;
        if (pool != null) {
            pool.shutdownNow();
            try {
                pool.awaitTermination(STOP_DELAY_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!authorized(exchange)) {
                respond(exchange, 401, "text/plain; charset=utf-8", "unauthorized\n");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (METRICS_PATH.equals(path)) {
                respond(exchange, 200, "text/plain; version=1.0.0; charset=utf-8", OpenMetricsRenderer.render(source));
                return;
            }
            if (SNAPSHOT_PATH.equals(path)) {
                respond(exchange, 200, "application/json; charset=utf-8",
                    SnapshotJson.render(source, history, System.currentTimeMillis()));
                return;
            }
            respond(exchange, 404, "text/plain; charset=utf-8", "not found\n");
        } finally {
            exchange.close();
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        byte[] presented = header.substring("Bearer ".length()).trim().getBytes(StandardCharsets.UTF_8);
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(presented, expected);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
