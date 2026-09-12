package art.arcane.wormholes.door;

import art.arcane.wormholes.util.project.config.TomlCodec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Per-traveler copies of an instanced template.
 *
 * <p>A template is shared unless its sidecar says otherwise, so nothing changes for the templates
 * that were already there. An instance's binding is derived from the template name and the traveler,
 * which is what lets somebody walk out and back into the room they were already in.</p>
 */
public final class PocketInstances {
    public static final String OPTIONS_EXTENSION = ".toml";
    /** Reset the moment the last occupant leaves. */
    public static final String RESET_ON_EMPTY = "on-empty";
    /** Reset once it has been empty for {@code [pockets] instance-reset-seconds}. */
    public static final String RESET_TIMER = "timer";
    /** Only an operator resets it. */
    public static final String RESET_MANUAL = "manual";

    private final PocketTemplateService templates;

    public PocketInstances(PocketTemplateService templates) {
        this.templates = Objects.requireNonNull(templates, "templates");
    }

    public boolean isInstanced(String templateName) {
        return options(templateName).instanced;
    }

    public PocketTemplateOptions options(String templateName) {
        Path sidecar = sidecar(templateName);
        if (!Files.isRegularFile(sidecar)) {
            return new PocketTemplateOptions();
        }
        TomlCodec.LoadResult<PocketTemplateOptions> result =
            TomlCodec.readExisting(sidecar.toFile(), PocketTemplateOptions.class);
        return result.isSuccess() && result.value() != null ? result.value() : new PocketTemplateOptions();
    }

    public Path sidecar(String templateName) {
        Path structure = templates.file(templateName);
        String fileName = structure.getFileName().toString();
        return structure.resolveSibling(
            fileName.substring(0, fileName.length() - PocketTemplateService.EXTENSION.length()) + OPTIONS_EXTENSION);
    }

    /** The pocket one traveler's copy of a template lives in; stable so they can come back to it. */
    public static PocketBinding bindingFor(String templateName, UUID travelerId) {
        Objects.requireNonNull(templateName, "templateName");
        Objects.requireNonNull(travelerId, "travelerId");
        String seed = "wormholes:pocket-instance:v1:" + templateName + ':' + travelerId;
        return PocketBinding.personal(UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)));
    }

    /** How long after a traveler is admitted the room is treated as busy, covering their entry flight. */
    public static final long ENTRY_GRACE_MILLIS = 15_000L;

    public static PocketInstanceInfo newInstance(String templateName, UUID travelerId, String resetPolicy, long now) {
        return new PocketInstanceInfo(
            Objects.requireNonNull(templateName, "templateName"),
            bindingFor(templateName, travelerId),
            now,
            normalizePolicy(resetPolicy),
            now,
            now);
    }

    /**
     * Whether an instance is due to be wiped and re-stamped.
     *
     * <p>A room nobody has entered since its last reset is already its template, so it is never wiped
     * again: the sweep runs every 20 seconds and would otherwise clear and re-stamp every idle instance
     * on the server, forever. An unreadable policy is treated as manual: never wipe a room on a value
     * nobody meant.</p>
     */
    public static boolean shouldReset(PocketInstanceInfo info, boolean occupied, long nowMillis, long resetSeconds) {
        Objects.requireNonNull(info, "info");
        if (occupied || !info.usedSinceReset()) {
            return false;
        }
        return switch (normalizePolicy(info.resetPolicy())) {
            case RESET_ON_EMPTY -> true;
            case RESET_TIMER -> nowMillis - info.lastOccupiedMillis() >= resetSeconds * 1_000L;
            default -> false;
        };
    }

    /**
     * Which instances to drop once there are more live than the cap allows.
     *
     * <p>Oldest idle first, and an occupied instance is never on the list: evicting somebody's room
     * out from under them is worse than being one over the cap for a moment.</p>
     */
    public static List<PocketSpace> evictionOrder(List<PocketSpace> live, Set<UUID> occupied, int maxLive) {
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(occupied, "occupied");
        int excess = live.size() - Math.max(0, maxLive);
        if (excess <= 0) {
            return List.of();
        }
        List<PocketSpace> idle = new ArrayList<>();
        for (PocketSpace space : live) {
            if (space.instance() != null && !occupied.contains(space.spaceId())) {
                idle.add(space);
            }
        }
        idle.sort(Comparator.comparingLong(space -> space.instance().lastOccupiedMillis()));
        return List.copyOf(idle.subList(0, Math.min(excess, idle.size())));
    }

    private static String normalizePolicy(String policy) {
        String normalized = policy == null ? "" : policy.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case RESET_ON_EMPTY, RESET_TIMER, RESET_MANUAL -> normalized;
            default -> RESET_MANUAL;
        };
    }
}
