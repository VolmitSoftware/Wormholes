package art.arcane.wormholes.ops.console;

import art.arcane.volmlib.util.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsEndpointTest {
    private MetricsEndpoint endpoint;

    @AfterEach
    void stopEndpoint() {
        if (endpoint != null) {
            endpoint.stop();
            endpoint = null;
        }
    }

    @Test
    void anEmptyTokenRefusesToStart() {
        MetricsEndpoint blank = new MetricsEndpoint("127.0.0.1", 0, "  ", source(), new MetricsHistory(1));
        assertThrows(IllegalStateException.class, blank::start);
        assertEquals(0, blank.boundPort());
    }

    @Test
    void metricsNeedsABearerTokenAndThenRendersTheSeries() throws Exception {
        endpoint = startEndpoint();

        HttpResponse<String> anonymous = get("/metrics", null);
        assertEquals(401, anonymous.statusCode());

        HttpResponse<String> wrong = get("/metrics", "nope");
        assertEquals(401, wrong.statusCode());

        HttpResponse<String> allowed = get("/metrics", "s3cret");
        assertEquals(200, allowed.statusCode());
        assertTrue(allowed.body().contains("wormholes_portals 12.0"), allowed.body());
        assertTrue(allowed.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
    }

    @Test
    void snapshotRendersMetricsPeersFailuresAndHistory() throws Exception {
        MetricsHistory history = new MetricsHistory(1);
        history.record(Map.of("wormholes.portals", Double.valueOf(11.0D)), 1_000L);
        history.record(Map.of("wormholes.portals", Double.valueOf(12.0D)), 2_000L);
        endpoint = new MetricsEndpoint("127.0.0.1", 0, "s3cret", source(), history);
        endpoint.start();

        HttpResponse<String> response = get("/snapshot", "s3cret");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));

        JSONObject json = new JSONObject(response.body());
        assertEquals(12.0D, json.getJSONObject("metrics").getDouble("wormholes.portals"));
        assertEquals("beta", json.getJSONArray("peers").getJSONObject(0).getString("peer"));
        assertEquals(3, json.getJSONObject("failures").getInt("HANDOFF_TIMED_OUT"));
        assertEquals(2, json.getJSONObject("history").getJSONArray("wormholes.portals").length());
    }

    @Test
    void unknownPathsAre404AndTheBoundPortIsReported() throws Exception {
        endpoint = startEndpoint();
        assertNotEquals(0, endpoint.boundPort());
        assertEquals(404, get("/nope", "s3cret").statusCode());
    }

    private MetricsEndpoint startEndpoint() throws IOException {
        MetricsEndpoint started = new MetricsEndpoint("127.0.0.1", 0, "s3cret", source(), new MetricsHistory(1));
        started.start();
        return started;
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + endpointPort() + path))
            .timeout(Duration.ofSeconds(10));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private int endpointPort() {
        return endpoint.boundPort();
    }

    private static MetricsSource source() {
        return new MetricsSource() {
            @Override
            public Map<String, Double> metrics() {
                return Map.of("wormholes.portals", Double.valueOf(12.0D));
            }

            @Override
            public List<Peer> peers() {
                return List.of(new Peer("beta", "TCP", "dict", 12L, true));
            }

            @Override
            public Map<String, Long> failures() {
                return Map.of("HANDOFF_TIMED_OUT", Long.valueOf(3L));
            }
        };
    }
}
