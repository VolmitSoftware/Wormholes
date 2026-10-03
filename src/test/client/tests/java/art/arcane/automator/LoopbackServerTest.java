package art.arcane.automator;

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class LoopbackServerTest {
    private static final String TOKEN = "test-token-which-is-long-enough";

    private LoopbackServerTest() {
    }

    public static void main(String[] arguments) throws Exception {
        try (LoopbackServer server = new LoopbackServer(0, TOKEN);
             HttpClient client = HttpClient.newHttpClient()) {
            String base = "http://127.0.0.1:" + server.port();
            HttpRequest denied = HttpRequest.newBuilder(URI.create(base + "/state")).GET().build();
            assertStatus(client.send(denied, HttpResponse.BodyHandlers.ofString()), 403, "unauthenticated state access");
            HttpRequest browser = request(base + "/state").header("Origin", "http://127.0.0.1").GET().build();
            assertStatus(client.send(browser, HttpResponse.BodyHandlers.ofString()), 403, "browser origin access");
            HttpRequest malformed = request(base + "/command").POST(HttpRequest.BodyPublishers.ofString("not json")).build();
            assertStatus(client.send(malformed, HttpResponse.BodyHandlers.ofString()), 400, "malformed request");
            HttpRequest oversized = request(base + "/command").POST(HttpRequest.BodyPublishers.ofString("x".repeat(8193))).build();
            assertStatus(client.send(oversized, HttpResponse.BodyHandlers.ofString()), 400, "oversized request");
            HttpRequest unknownKey = request(base + "/command").POST(HttpRequest.BodyPublishers.ofString("{\"op\":\"keys\",\"backward\":true}")).build();
            assertStatus(client.send(unknownKey, HttpResponse.BodyHandlers.ofString()), 400, "unknown movement key");
            if (server.poll() != null) {
                throw new AssertionError("Invalid movement input reached the game-thread queue");
            }
            for (String body : new String[]{"{\"op\":\"keys\",\"playerList\":true}", "{\"op\":\"keys\",\"hold\":[\"playerList\"]}", "{\"op\":\"keys\",\"playerList\":false}"}) {
                HttpRequest playerList = request(base + "/command").POST(HttpRequest.BodyPublishers.ofString(body)).build();
                CompletableFuture<HttpResponse<String>> playerListResult = client.sendAsync(playerList, HttpResponse.BodyHandlers.ofString());
                LoopbackServer.Request playerListRequest = awaitRequest(server);
                if (!playerListRequest.input().get("op").getAsString().equals("keys")) {
                    throw new AssertionError("Player list input changed before dispatch");
                }
                JsonObject playerListReply = new JsonObject();
                playerListReply.addProperty("ok", true);
                playerListRequest.result().complete(playerListReply);
                assertStatus(playerListResult.get(3, TimeUnit.SECONDS), 200, "player list key and hold validation");
            }
            HttpRequest pauseScreen = request(base + "/command")
                .POST(HttpRequest.BodyPublishers.ofString("{\"op\":\"pause-screen\"}")).build();
            CompletableFuture<HttpResponse<String>> paused = client.sendAsync(pauseScreen, HttpResponse.BodyHandlers.ofString());
            LoopbackServer.Request pauseRequest = awaitRequest(server);
            if (!pauseRequest.input().get("op").getAsString().equals("pause-screen") || paused.isDone()) {
                throw new AssertionError("Pause screen must wait for native game-thread dispatch");
            }
            JsonObject pauseReply = new JsonObject();
            pauseReply.addProperty("ok", true);
            pauseRequest.result().complete(pauseReply);
            assertStatus(paused.get(3, TimeUnit.SECONDS), 200, "native pause screen dispatch");
            HttpRequest clearChat = request(base + "/command")
                .POST(HttpRequest.BodyPublishers.ofString("{\"op\":\"clear-chat\"}")).build();
            CompletableFuture<HttpResponse<String>> cleared = client.sendAsync(clearChat, HttpResponse.BodyHandlers.ofString());
            LoopbackServer.Request clearRequest = awaitRequest(server);
            if (!clearRequest.input().get("op").getAsString().equals("clear-chat") || cleared.isDone()) {
                throw new AssertionError("Chat clearing must wait for native game-thread dispatch");
            }
            JsonObject clearReply = new JsonObject();
            clearReply.addProperty("ok", true);
            clearRequest.result().complete(clearReply);
            assertStatus(cleared.get(3, TimeUnit.SECONDS), 200, "native chat clear dispatch");
            HttpRequest input = request(base + "/command").POST(HttpRequest.BodyPublishers.ofString("{\"op\":\"chat\",\"text\":\"Garden\"}")).build();
            CompletableFuture<HttpResponse<String>> result = client.sendAsync(input, HttpResponse.BodyHandlers.ofString());
            LoopbackServer.Request received = awaitRequest(server);
            if (!received.input().get("text").getAsString().equals("Garden")) {
                throw new AssertionError("Naming input changed before reaching the game thread");
            }
            if (result.isDone()) {
                throw new AssertionError("Response completed before the game thread applied input");
            }
            JsonObject reply = new JsonObject();
            reply.addProperty("ok", true);
            reply.addProperty("ticks", 42);
            received.result().complete(reply);
            HttpResponse<String> applied = result.get(3, TimeUnit.SECONDS);
            assertStatus(applied, 200, "applied input response");
            if (!applied.body().contains("42")) {
                throw new AssertionError("Response lost the applied game state");
            }
        }
        System.out.println("Loopback authorization, request validation and game-thread dispatch passed");
    }

    private static HttpRequest.Builder request(String address) {
        return HttpRequest.newBuilder(URI.create(address)).timeout(Duration.ofSeconds(3)).header("X-Automator-Token", TOKEN);
    }

    private static LoopbackServer.Request awaitRequest(LoopbackServer server) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            LoopbackServer.Request request = server.poll();
            if (request != null) {
                return request;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Authenticated input did not reach the game-thread queue");
    }

    private static void assertStatus(HttpResponse<String> response, int expected, String label) {
        if (response.statusCode() != expected) {
            throw new AssertionError(label + " returned " + response.statusCode() + " instead of " + expected);
        }
    }
}
