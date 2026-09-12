package art.arcane.wormholes.door;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable allocation of one logical pocket within the shared void world. */
public record PocketSpace(
    UUID spaceId,
    PocketBinding binding,
    long slot,
    int centerX,
    int centerY,
    int centerZ,
    PocketShell shell,
    String templateName,
    PocketRules rules,
    PocketRoster roster,
    List<PocketRoom> rooms,
    PocketInstanceInfo instance
) {
    /** No template: the pocket is the plain cube its shell describes. */
    public static final String NO_TEMPLATE = "";

    public PocketSpace {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(shell, "shell");
        Objects.requireNonNull(templateName, "templateName");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(roster, "roster");
        rooms = List.copyOf(Objects.requireNonNull(rooms, "rooms"));
        if (slot < 0) {
            throw new IllegalArgumentException("slot cannot be negative");
        }
    }

    public PocketSpace(
        UUID spaceId,
        PocketBinding binding,
        long slot,
        int centerX,
        int centerY,
        int centerZ,
        PocketShell shell
    ) {
        this(spaceId, binding, slot, centerX, centerY, centerZ, shell,
            NO_TEMPLATE, PocketRules.defaults(), PocketRoster.empty(), List.of(), null);
    }

    public PocketSpace withShell(PocketShell updated) {
        Objects.requireNonNull(updated, "updated");
        return updated.equals(shell) ? this : copyWith(updated, templateName, rules, roster, rooms, instance);
    }

    public PocketSpace withTemplateName(String updated) {
        Objects.requireNonNull(updated, "updated");
        return updated.equals(templateName) ? this : copyWith(shell, updated, rules, roster, rooms, instance);
    }

    public PocketSpace withRules(PocketRules updated) {
        Objects.requireNonNull(updated, "updated");
        return updated.equals(rules) ? this : copyWith(shell, templateName, updated, roster, rooms, instance);
    }

    public PocketSpace withRoster(PocketRoster updated) {
        Objects.requireNonNull(updated, "updated");
        return updated.equals(roster) ? this : copyWith(shell, templateName, rules, updated, rooms, instance);
    }

    public PocketSpace withRooms(List<PocketRoom> updated) {
        List<PocketRoom> copied = List.copyOf(Objects.requireNonNull(updated, "updated"));
        return copied.equals(rooms) ? this : copyWith(shell, templateName, rules, roster, copied, instance);
    }

    public PocketSpace withInstance(PocketInstanceInfo updated) {
        return Objects.equals(updated, instance)
            ? this
            : copyWith(shell, templateName, rules, roster, rooms, updated);
    }

    private PocketSpace copyWith(
        PocketShell shell,
        String templateName,
        PocketRules rules,
        PocketRoster roster,
        List<PocketRoom> rooms,
        PocketInstanceInfo instance
    ) {
        return new PocketSpace(spaceId, binding, slot, centerX, centerY, centerZ, shell,
            templateName, rules, roster, rooms, instance);
    }
}
