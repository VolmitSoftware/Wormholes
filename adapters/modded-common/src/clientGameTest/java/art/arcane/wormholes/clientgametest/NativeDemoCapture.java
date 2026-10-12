package art.arcane.wormholes.clientgametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

final class NativeDemoCapture {
    private static final int ACK_TIMEOUT_TICKS = 1200;
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final SeamlessClient client;
    private final Path directory;
    private String active;

    NativeDemoCapture(SeamlessClient client) {
        this.client = Objects.requireNonNull(client);
        directory = Path.of(Objects.requireNonNull(System.getProperty("automator.output"))).resolve("native-demo");
    }

    void begin(String id) {
        if (active != null || !id.matches("[a-z0-9-]+")) {
            throw new IllegalStateException("Invalid or overlapping demo take: " + id);
        }
        active = id;
        request("ready", new JsonObject());
    }

    void end(String id) {
        if (!id.equals(active)) {
            throw new IllegalStateException("Demo take does not match the active recording: " + id);
        }
        request("complete", new JsonObject());
        active = null;
    }

    void turn(float yaw, float pitch, int ticks) {
        if (active == null || !Float.isFinite(yaw) || !Float.isFinite(pitch) || ticks < 1 || ticks > 200) {
            throw new IllegalArgumentException("A finite timed turn requires an active demo take");
        }
        JsonObject turn = new JsonObject();
        turn.addProperty("yaw", yaw);
        turn.addProperty("pitch", pitch);
        turn.addProperty("ticks", ticks);
        request("turn", turn);
        client.waitTicks(ticks + 2);
    }

    private void request(String stage, JsonObject message) {
        int expected = SEQUENCE.incrementAndGet();
        message.addProperty("id", active);
        message.addProperty("stage", stage);
        message.addProperty("sequence", expected);
        try {
            Files.createDirectories(directory);
            Path pending = directory.resolve("phase.tmp");
            Files.writeString(pending, message.toString(), StandardCharsets.UTF_8);
            Files.move(pending, directory.resolve("phase.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            for (int tick = 0; tick < ACK_TIMEOUT_TICKS; tick++) {
                Path acknowledgment = directory.resolve("ack.json");
                if (Files.isRegularFile(acknowledgment)) {
                    JsonObject ack;
                    try (Reader reader = Files.newBufferedReader(acknowledgment, StandardCharsets.UTF_8)) {
                        ack = JsonParser.parseReader(reader).getAsJsonObject();
                    }
                    if (ack.get("sequence").getAsInt() == expected) {
                        if (!ack.get("success").getAsBoolean()) {
                            throw new IllegalStateException("Native demo capture failed: " + ack.get("error").getAsString());
                        }
                        return;
                    }
                }
                client.waitTicks(1);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Native demo capture handshake failed", error);
        }
        throw new IllegalStateException("Native demo capture acknowledgment timed out: " + active + " " + stage);
    }
}
