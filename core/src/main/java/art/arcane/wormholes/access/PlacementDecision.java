package art.arcane.wormholes.access;

import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of a placement check. {@code plugin} names the claim plugin that refused or broke, so the
 * player-facing reason can say which one it was. A FAILURE means the plugin is present but could not
 * be asked, and the caller fails closed.
 */
public final class PlacementDecision {
    private static final PlacementDecision ALLOWED = new PlacementDecision(Status.ALLOWED, "", null);

    private final Status status;
    private final String plugin;
    private final Throwable failure;

    private PlacementDecision(Status status, String plugin, Throwable failure) {
        this.status = status;
        this.plugin = plugin;
        this.failure = failure;
    }

    public static PlacementDecision allowedResult() {
        return ALLOWED;
    }

    public static PlacementDecision deniedResult(String plugin) {
        return new PlacementDecision(Status.DENIED, Objects.requireNonNull(plugin, "plugin"), null);
    }

    public static PlacementDecision failureResult(String plugin, Throwable failure) {
        return new PlacementDecision(Status.FAILURE, Objects.requireNonNull(plugin, "plugin"),
            Objects.requireNonNull(failure, "failure"));
    }

    public Status status() {
        return status;
    }

    public boolean allowed() {
        return status == Status.ALLOWED;
    }

    public String plugin() {
        return plugin;
    }

    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    public enum Status {
        ALLOWED,
        DENIED,
        FAILURE
    }
}
