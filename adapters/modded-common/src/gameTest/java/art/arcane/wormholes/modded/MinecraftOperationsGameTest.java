package art.arcane.wormholes.modded;

import art.arcane.wormholes.ops.backup.BackupBundle;
import art.arcane.wormholes.config.toml.OpsConfig;
import com.google.gson.JsonParser;
import art.arcane.wormholes.portal.PortalType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public final class MinecraftOperationsGameTest {
    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final MinecraftPortal portal;
    private final Path data;
    private final long started = System.currentTimeMillis();
    private boolean cleaned;
    private OpsConfig.ConsoleConfig previousConsole;
    private CompletableFuture<Void> metricsProbe;
    private Path importFile;
    private final String importName = "Imported-" + UUID.randomUUID();

    private MinecraftOperationsGameTest(GameTestHelper helper) {
        this.helper = helper;
        runtime = WormholesGameTests.RUNTIME;
        List<BlockPos> cells = new ArrayList<>();
        for (int y = 2; y < 5; y++) {
            cells.add(helper.absolutePos(new BlockPos(2, y, 3)));
            cells.add(helper.absolutePos(new BlockPos(3, y, 3)));
        }
        portal = runtime.portals().create(UUID.randomUUID(), helper.getLevel(), cells, PortalType.GATEWAY, new Vec3(0, 0, -1));
        portal.setName("Operations-" + portal.getId());
        portal.linkRemote("ops-test-missing", UUID.randomUUID());
        runtime.portals().save(portal);
        data = runtime.server().getServerDirectory().resolve("config/wormholes");
    }

    public static void run(GameTestHelper helper) {
        MinecraftOperationsGameTest test = new MinecraftOperationsGameTest(helper);
        try {
            test.start();
        } catch (RuntimeException failure) {
            test.cleanup();
            throw failure;
        }
    }

    private void start() {
        runtime.schedule(this::cleanup, 590);
        previousConsole = runtime.configuration().settings().getOps().console;
        OpsConfig.ConsoleConfig console = new OpsConfig.ConsoleConfig();
        console.enabled = true;
        console.port = 0;
        console.token = "isolated-metrics-probe";
        runtime.configuration().settings().getOps().console = console;
        helper.assertTrue(command("wormholes version") == 1, "Version command failed");
        helper.assertTrue(command("wormhole version") == 1, "Wormhole command alias failed");
        helper.assertTrue(command("wormholes admin portals find " + portal.getName()) == 1, "Name search did not find portal");
        helper.assertTrue(command("wormholes admin portals info " + portal.getId().toString().substring(0, 8)) == 1,
            "Portal prefix lookup failed");
        UUID destination = portal.getDestinationId();
        command("wormholes admin portals prune false");
        helper.assertTrue(destination.equals(portal.getDestinationId()), "Prune mutated data without confirmation");
        command("wormholes admin freeze 5");
        command("wormholes admin flush");
        command("wormholes admin freeze 0");
        helper.assertTrue(command("wormholes stats true") == 1, "Stats command failed");
        helper.assertTrue(command("wormholes admin backup now") == 1, "Backup command failed");
        startImport();
        helper.assertTrue(command("wormholes debug dump false") == 1, "Local diagnostic report command failed");
        helper.startSequence().thenWaitUntil(() -> {
            try {
                int port = runtime.operations().metricsPort();
                helper.assertTrue(port > 0, "Configured metrics listener did not start");
                if (metricsProbe == null) {
                    metricsProbe = CompletableFuture.runAsync(() -> probeMetrics(port));
                }
                helper.assertTrue(metricsProbe.isDone(), "Metrics HTTP requests did not finish");
                metricsProbe.join();
                helper.assertTrue(runtime.portals().snapshot().stream().anyMatch(candidate -> candidate.getName().equals(importName)),
                    "Third-party import did not create a native portal");
                Path debug = data.resolve("debug");
                boolean reportWritten = false;
                if (Files.isDirectory(debug)) {
                    try (Stream<Path> paths = Files.list(debug)) {
                        for (Path report : paths.toList()) {
                            reportWritten |= report.getFileName().toString().endsWith(".txt")
                                && Files.getLastModifiedTime(report).toMillis() >= started
                                && Files.readString(report).contains("Heap maximum bytes:");
                        }
                    }
                }
                helper.assertTrue(reportWritten, "Local diagnostic report was not written");
                Path stats = data.resolve("stats-snapshot.txt");
                helper.assertTrue(Files.isRegularFile(stats) && Files.getLastModifiedTime(stats).toMillis() >= started,
                    "Stats snapshot was not refreshed");
                helper.assertTrue(Files.readString(stats).contains("Projectors:"), "Stats file does not contain native projection counters");
                boolean found = false;
                Path backups = data.resolve("backups");
                if (Files.isDirectory(backups)) {
                    try (Stream<Path> paths = Files.list(backups)) {
                        for (Path path : paths.filter(file -> file.toString().endsWith(".zip")).toList()) {
                            if (Files.getLastModifiedTime(path).toMillis() >= started) {
                                BackupBundle bundle = BackupBundle.read(path);
                                found |= bundle.portalEntries().stream().anyMatch(entry -> entry.endsWith(portal.getId() + ".json"));
                            }
                        }
                    }
                }
                helper.assertTrue(found, "Backup missed the portal's queued save");
            } catch (IOException failure) {
                throw new IllegalStateException("Could not verify native operations output", failure);
            }
        }).thenExecute(() -> {
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS ops_runtime version find prefix_info prune_confirmation freeze flush stats_atomic_file backup_pending_save thirdparty_import local_debug_dump authenticated_metrics snapshot_history");
            cleanup();
        }).thenSucceed();
    }

    private static void probeMetrics(int port) {
        try (HttpClient client = HttpClient.newHttpClient()) {
            URI base = URI.create("http://127.0.0.1:" + port);
            HttpRequest anonymous = HttpRequest.newBuilder(base.resolve("/metrics")).timeout(Duration.ofSeconds(5)).build();
            if (client.send(anonymous, HttpResponse.BodyHandlers.ofString()).statusCode() != 401) {
                throw new IllegalStateException("Metrics endpoint admitted an anonymous request");
            }
            HttpRequest metrics = HttpRequest.newBuilder(base.resolve("/metrics")).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer isolated-metrics-probe").build();
            HttpResponse<String> text = client.send(metrics, HttpResponse.BodyHandlers.ofString());
            if (text.statusCode() != 200 || !text.body().contains("wormholes_portals ")) {
                throw new IllegalStateException("Metrics endpoint omitted the native portal count");
            }
            HttpRequest snapshot = HttpRequest.newBuilder(base.resolve("/snapshot")).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer isolated-metrics-probe").build();
            HttpResponse<String> json = client.send(snapshot, HttpResponse.BodyHandlers.ofString());
            if (json.statusCode() != 200 || !JsonParser.parseString(json.body()).getAsJsonObject()
                .getAsJsonObject("history").has("wormholes.portals")) {
                throw new IllegalStateException("Metrics snapshot omitted native metric history");
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Metrics request failed", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Metrics request interrupted", failure);
        }
    }

    private void startImport() {
        Path file = runtime.server().getServerDirectory().resolve("plugins/BetterPortals/portals.json");
        BlockPos origin = helper.absolutePos(new BlockPos(10, 2, 3));
        String document = "{\"portals\":[{\"name\":\"" + importName + "\",\"originPos\":{\"world\":\""
            + helper.getLevel().dimension().identifier() + "\",\"x\":" + origin.getX() + ",\"y\":" + origin.getY()
            + ",\"z\":" + origin.getZ() + "},\"portalSize\":{\"x\":2,\"y\":3}}]}";
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, document, StandardOpenOption.CREATE_NEW);
            importFile = file;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write isolated import fixture", failure);
        }
        helper.assertTrue(command("wormholes admin backup import-from betterportals dry=false") == 1,
            "Third-party import command failed");
    }

    private int command(String input) {
        try {
            return runtime.server().getCommands().getDispatcher().execute(input, runtime.server().createCommandSourceStack());
        } catch (CommandSyntaxException failure) {
            throw new IllegalStateException("Could not run operations command: " + input, failure);
        }
    }

    private void cleanup() {
        if (!cleaned) {
            cleaned = true;
            if (previousConsole != null) {
                runtime.configuration().settings().getOps().console = previousConsole;
            }
            runtime.projections().freeze(0);
            runtime.portals().remove(portal.getId());
            for (MinecraftPortal candidate : runtime.portals().snapshot()) {
                if (candidate.getName().equals(importName)) {
                    runtime.portals().remove(candidate.getId());
                }
            }
            if (importFile != null) {
                try {
                    Files.deleteIfExists(importFile);
                } catch (IOException failure) {
                    LoggerFactory.getLogger("WormholesGameTest").error("Could not delete import fixture {}", importFile, failure);
                }
            }
        }
    }
}
