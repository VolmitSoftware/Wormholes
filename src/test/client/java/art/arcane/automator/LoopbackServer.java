package art.arcane.automator;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class LoopbackServer implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private static final int REQUEST_LIMIT = 8192;
    private static final Set<String> INPUT_KEYS = Set.of("forward", "back", "left", "right", "jump", "sneak", "sprint", "attack", "use",
            "swapHands", "drop", "inventory");
    private static final Set<String> KEY_OPTIONS = Set.of("op", "leaseTicks", "hold");

    private final HttpServer server;
    private final byte[] token;
    private final ExecutorService executor;
    private final ArrayBlockingQueue<Request> requests = new ArrayBlockingQueue<>(64);

    public LoopbackServer(int port, String token) throws IOException {
        if (token == null || token.length() < 16) {
            throw new IllegalArgumentException("automator.token must contain at least 16 characters");
        }
        this.token = token.getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 8);
        executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().name("Instance Automator HTTP-", 0).factory());
        server.createContext("/", this::http);
        server.setExecutor(executor);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public Request poll() {
        return requests.poll();
    }

    @Override
    public void close() {
        Request request;
        while ((request = requests.poll()) != null) {
            request.result().completeExceptionally(new IllegalStateException("Client bridge closed"));
        }
        server.stop(0);
        executor.shutdownNow();
    }

    private void http(HttpExchange exchange) throws IOException {
        String supplied = exchange.getRequestHeaders().getFirst("X-Automator-Token");
        if (supplied == null || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))
                || exchange.getRequestHeaders().containsKey("Origin")) {
            respond(exchange, 403, error("Automator token required; browser origins are not accepted"));
            return;
        }
        JsonObject input;
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if (method.equals("GET") && path.equals("/state")) {
                input = new JsonObject();
                input.addProperty("op", "state");
            } else if (method.equals("POST") && path.equals("/command")) {
                byte[] body = exchange.getRequestBody().readNBytes(REQUEST_LIMIT + 1);
                if (body.length > REQUEST_LIMIT) {
                    throw new IllegalArgumentException("Request exceeds " + REQUEST_LIMIT + " bytes");
                }
                input = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
                if (!input.has("op") || !input.get("op").isJsonPrimitive()
                        || !input.get("op").getAsJsonPrimitive().isString() || input.get("op").getAsString().isBlank()) {
                    throw new IllegalArgumentException("A string op is required");
                }
                validateKeys(input);
            } else {
                respond(exchange, 404, error("Use GET /state or POST /command"));
                return;
            }
        } catch (RuntimeException failure) {
            respond(exchange, 400, error(failure.toString()));
            return;
        }
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        if (!requests.offer(new Request(input, result))) {
            respond(exchange, 429, error("Client command queue is full"));
            return;
        }
        try {
            respond(exchange, 200, result.get(15, TimeUnit.SECONDS));
        } catch (TimeoutException failure) {
            result.cancel(false);
            respond(exchange, 504, error("Client did not process command within fifteen seconds"));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            result.cancel(false);
            respond(exchange, 503, error("Client bridge interrupted"));
        } catch (ExecutionException failure) {
            respond(exchange, 400, error(failure.getCause().toString()));
        }
    }

    private static JsonObject error(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", message);
        return result;
    }

    private static void validateKeys(JsonObject input) {
        if (!input.get("op").getAsString().equals("keys")) {
            return;
        }
        for (String name : input.keySet()) {
            if (!INPUT_KEYS.contains(name) && !KEY_OPTIONS.contains(name)) {
                throw new IllegalArgumentException("Unknown input key " + name);
            }
            if (INPUT_KEYS.contains(name) && (!input.get(name).isJsonPrimitive()
                    || !input.get(name).getAsJsonPrimitive().isBoolean())) {
                throw new IllegalArgumentException(name + " must be a boolean");
            }
        }
        if (input.has("hold")) {
            for (JsonElement entry : input.getAsJsonArray("hold")) {
                if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString() || !INPUT_KEYS.contains(entry.getAsString())) {
                    throw new IllegalArgumentException("Unknown held input key " + entry);
                }
            }
        }
    }

    private static void respond(HttpExchange exchange, int status, JsonObject value) throws IOException {
        byte[] body = GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (exchange) {
            exchange.getResponseBody().write(body);
        }
    }

    public record Request(JsonObject input, CompletableFuture<JsonObject> result) {
    }
}
