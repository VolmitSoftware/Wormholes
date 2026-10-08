package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.io.AtomicFileIO;
import art.arcane.volmlib.util.web.MclogsClient;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.config.toml.MainConfig;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class MinecraftDiagnostics implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private final WormholesModRuntime runtime;
    private final MinecraftStatsSnapshots stats;
    private ExecutorService writer;
    private Boolean enabled;
    private MainConfig observedSettings;
    private long nextLog;
    private long generation;
    private CompletableFuture<Dump> pending;

    MinecraftDiagnostics(WormholesModRuntime runtime, MinecraftStatsSnapshots stats) {
        this.runtime = runtime;
        this.stats = stats;
    }

    LiteralArgumentBuilder<CommandSourceStack> commands() {
        return Commands.literal("debug")
            .requires(source -> runtime.access().permission(source, "wormholes.admin")
                || runtime.access().permission(source, "wormholes.debugdump"))
            .executes(context -> {
                context.getSource().sendSuccess(() -> Component.literal("/wormholes debug version | toggle | dump [upload=true]"), false);
                return 1;
            })
            .then(Commands.literal("version").executes(context -> {
                context.getSource().sendSuccess(() -> Component.literal("Wormholes " + MinecraftNetworkService.version()), false);
                return 1;
            }))
            .then(Commands.literal("toggle").requires(source -> runtime.access().permission(source, "wormholes.admin"))
                .executes(context -> {
                    enabled = !active();
                    context.getSource().sendSuccess(() -> runtime.localization().text(context.getSource().getPlayer(),
                        enabled ? WormholesMessages.COMMAND_DEBUG_ENABLED : WormholesMessages.COMMAND_DEBUG_DISABLED, Map.of()), false);
                    return 1;
                }))
            .then(Commands.literal("dump").requires(source -> runtime.access().permission(source, "wormholes.debugdump"))
                .executes(context -> dump(context.getSource(), true))
                .then(Commands.argument("upload", BoolArgumentType.bool()).executes(context ->
                    dump(context.getSource(), BoolArgumentType.getBool(context, "upload")))));
    }

    void tick() {
        long now = System.currentTimeMillis();
        if (active() && now >= nextLog) {
            nextLog = now + 1000L;
            LOGGER.info("[debug] {}", stats.capture().replace('\n', ' '));
        }
    }

    private boolean active() {
        MainConfig settings = runtime.configuration().settings().getMain();
        if (observedSettings != settings) {
            observedSettings = settings;
            enabled = null;
        }
        return enabled == null ? settings.verboseLogging : enabled;
    }

    private int dump(CommandSourceStack source, boolean upload) {
        if (pending != null && !pending.isDone()) {
            source.sendFailure(Component.literal("A diagnostic report is already being written."));
            return 0;
        }
        if (writer == null) {
            writer = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("Wormholes-diagnostics").factory());
        }
        long submitted = generation;
        String state = stats.capture();
        Path path = runtime.stores().config().resolve("debug").resolve("report-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".txt");
        pending = CompletableFuture.supplyAsync(() -> write(path, report(state), upload), writer);
        pending.whenCompleteAsync((result, failure) -> {
            if (generation != submitted || !runtime.running()) {
                return;
            }
            if (failure != null) {
                LOGGER.error("Could not write Wormholes diagnostic report", failure);
                source.sendFailure(Component.literal("Could not write diagnostic report: " + failure.getMessage()));
                return;
            }
            source.sendSuccess(() -> Component.literal("Diagnostic report: " + result.path()), false);
            if (result.url() != null) {
                source.sendSuccess(() -> Component.literal("Uploaded diagnostic report: " + result.url()), false);
            } else if (upload) {
                source.sendFailure(Component.literal("Upload failed; the diagnostic report remains saved locally."));
            }
        }, runtime.server());
        return 1;
    }

    static String report(String state) {
        Runtime process = Runtime.getRuntime();
        StringBuilder report = new StringBuilder(state);
        report.append("Report timestamp: ").append(Instant.now()).append('\n');
        report.append("Java: ").append(System.getProperty("java.version")).append('\n');
        report.append("Operating system: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.arch")).append('\n');
        report.append("Processors: ").append(process.availableProcessors()).append('\n');
        report.append("Heap used bytes: ").append(process.totalMemory() - process.freeMemory()).append('\n');
        report.append("Heap maximum bytes: ").append(process.maxMemory()).append('\n');
        report.append("Live threads: ").append(ManagementFactory.getThreadMXBean().getThreadCount()).append('\n');
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            report.append("GC ").append(collector.getName()).append(" count=").append(collector.getCollectionCount())
                .append(" millis=").append(collector.getCollectionTime()).append('\n');
        }
        return report.toString();
    }

    private static Dump write(Path path, String report, boolean upload) {
        try {
            AtomicFileIO.writeString(path, report);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        URI url = null;
        if (upload) {
            try {
                url = new MclogsClient().publish(report, "VolmitSoftware - Wormholes - " + MinecraftNetworkService.version(),
                    "Wormholes/" + MinecraftNetworkService.version());
            } catch (IOException failure) {
                LOGGER.error("Could not upload Wormholes diagnostic report {}", path, failure);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOGGER.error("Wormholes diagnostic report upload interrupted for {}", path, interrupted);
            }
        }
        return new Dump(path, url);
    }

    @Override
    public void close() {
        generation++;
        if (writer != null) {
            writer.shutdownNow();
            writer = null;
        }
        enabled = null;
        observedSettings = null;
        pending = null;
        nextLog = 0L;
    }

    private record Dump(Path path, URI url) { }
}
