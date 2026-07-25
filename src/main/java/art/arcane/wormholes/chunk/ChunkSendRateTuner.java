package art.arcane.wormholes.chunk;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.service.WormholesTelemetry;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

public final class ChunkSendRateTuner {
    public static final double MINIMUM_RATE = 1.0D;
    public static final double UNLIMITED_RATE = 10000.0D;
    public static final String FAILURE_FIELD_UNAVAILABLE = "CHUNK_SEND_RATE_FIELD_UNAVAILABLE";

    private ChunkSendRateTuner() {
    }

    public enum Status {
        DISABLED,
        UNSUPPORTED,
        UNAVAILABLE,
        UNCHANGED,
        APPLIED
    }

    public record Targets(double sendRate, double loadRate) {
        public double forLimit(ChunkSendRateLimit limit) {
            return switch (limit) {
                case SEND -> sendRate;
                case LOAD -> loadRate;
            };
        }
    }

    public record Adjustment(ChunkSendRateLimit limit, double previous, double applied) {
        public boolean changed() {
            return applied != previous;
        }
    }

    public record Outcome(Status status, List<Adjustment> adjustments, List<ChunkSendRateLimit> unavailable) {
        public Outcome {
            adjustments = List.copyOf(adjustments);
            unavailable = List.copyOf(unavailable);
        }
    }

    public static void install(Plugin plugin) {
        Objects.requireNonNull(plugin);
        ChunkSendRateAccessor accessor = PaperChunkSendRateAccessor.resolve();
        Outcome outcome = apply(
            accessor,
            Settings.CHUNK_SEND_RATE_TUNER,
            new Targets(Settings.CHUNK_SEND_RATE_TARGET, Settings.CHUNK_LOAD_RATE_TARGET));

        if (outcome.status() == Status.DISABLED) {
            return;
        }

        plugin.getLogger().info(describe(outcome, accessor.describe()));
    }

    public static double effectiveRate(double configured) {
        if (configured <= 0.0D || configured > UNLIMITED_RATE) {
            return UNLIMITED_RATE;
        }

        return Math.max(MINIMUM_RATE, configured);
    }

    public static Outcome apply(ChunkSendRateAccessor accessor, boolean enabled, Targets targets) {
        Objects.requireNonNull(accessor);
        Objects.requireNonNull(targets);

        if (!enabled) {
            return new Outcome(Status.DISABLED, List.of(), List.of());
        }

        if (!accessor.available()) {
            return new Outcome(Status.UNSUPPORTED, List.of(), List.of());
        }

        List<Adjustment> adjustments = new ArrayList<>(ChunkSendRateLimit.values().length);
        List<ChunkSendRateLimit> unavailable = new ArrayList<>(ChunkSendRateLimit.values().length);

        for (ChunkSendRateLimit limit : ChunkSendRateLimit.values()) {
            OptionalDouble current = accessor.read(limit);
            if (current.isEmpty()) {
                unavailable.add(limit);
                continue;
            }

            double configured = current.getAsDouble();
            double desired = effectiveRate(targets.forLimit(limit));
            if (effectiveRate(configured) >= desired) {
                adjustments.add(new Adjustment(limit, configured, configured));
                continue;
            }

            if (!accessor.write(limit, desired)) {
                unavailable.add(limit);
                continue;
            }

            adjustments.add(new Adjustment(limit, configured, desired));
        }

        if (!unavailable.isEmpty()) {
            WormholesTelemetry.countFailure(FAILURE_FIELD_UNAVAILABLE);
        }

        return new Outcome(statusOf(adjustments, unavailable), adjustments, unavailable);
    }

    public static String describe(Outcome outcome, String platform) {
        Objects.requireNonNull(outcome);
        StringBuilder line = new StringBuilder(128);
        line.append("Chunk rate tuner");
        if (platform != null && !platform.isEmpty()) {
            line.append(" (").append(platform).append(')');
        }
        line.append(": ");

        switch (outcome.status()) {
            case DISABLED -> line.append("disabled by configuration");
            case UNSUPPORTED -> line.append("no-op, server does not expose Paper chunk loading configuration");
            case UNAVAILABLE -> line.append("no-op, unreadable fields ").append(fieldNames(outcome.unavailable()));
            case UNCHANGED, APPLIED -> {
                line.append(rates(outcome.adjustments()));
                if (!outcome.unavailable().isEmpty()) {
                    line.append("; unreadable fields ").append(fieldNames(outcome.unavailable()));
                }
            }
        }

        return line.toString();
    }

    private static Status statusOf(List<Adjustment> adjustments, List<ChunkSendRateLimit> unavailable) {
        if (adjustments.isEmpty()) {
            return unavailable.isEmpty() ? Status.UNCHANGED : Status.UNAVAILABLE;
        }

        for (Adjustment adjustment : adjustments) {
            if (adjustment.changed()) {
                return Status.APPLIED;
            }
        }

        return Status.UNCHANGED;
    }

    private static String rates(List<Adjustment> adjustments) {
        StringBuilder out = new StringBuilder(64);
        for (Adjustment adjustment : adjustments) {
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(adjustment.limit().label()).append(' ').append(adjustment.previous());
            if (adjustment.changed()) {
                out.append(" -> ").append(adjustment.applied());
            } else {
                out.append(" unchanged");
            }
        }
        return out.toString();
    }

    private static String fieldNames(List<ChunkSendRateLimit> limits) {
        StringBuilder out = new StringBuilder(64);
        for (ChunkSendRateLimit limit : limits) {
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(limit.field());
        }
        return out.toString();
    }
}
