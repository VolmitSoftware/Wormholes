package art.arcane.wormholes.door;

import java.util.Locale;
import java.util.Objects;

/**
 * Per-pocket room dimensions and build materials.
 *
 * <p>The room is a cube anchored at its minimum corner, so growing or shrinking
 * a pocket never moves anything already built inside it. Materials are stored as
 * plain names because this layer stays free of server types; the world layer
 * resolves and validates them.</p>
 */
public record PocketShell(int size, String shellMaterial, String returnDoorMaterial) {
    /** Sixteen blocks on every axis, including the shell, for one horizontal chunk. */
    public static final int DEFAULT_SIZE = 16;
    /** Below this the return door and its clearance no longer fit inside the shell. */
    public static final int MIN_SIZE = 8;
    /**
     * Bounded by the pocket dimension's build height above the room floor and by
     * the cost of laying and verifying the shell on a region thread.
     */
    public static final int MAX_SIZE = 128;
    public static final String DEFAULT_SHELL_MATERIAL = "SMOOTH_STONE";
    public static final String DEFAULT_RETURN_DOOR_MATERIAL = "CRIMSON_DOOR";

    private static final PocketShell DEFAULTS =
        new PocketShell(DEFAULT_SIZE, DEFAULT_SHELL_MATERIAL, DEFAULT_RETURN_DOOR_MATERIAL);

    public PocketShell {
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new IllegalArgumentException("pocket size must be between " + MIN_SIZE + " and " + MAX_SIZE);
        }
        shellMaterial = normalizeMaterial(shellMaterial, "shellMaterial");
        returnDoorMaterial = normalizeMaterial(returnDoorMaterial, "returnDoorMaterial");
    }

    public static PocketShell defaults() {
        return DEFAULTS;
    }

    public PocketShell withSize(int updatedSize) {
        return updatedSize == size ? this : new PocketShell(updatedSize, shellMaterial, returnDoorMaterial);
    }

    public PocketShell withShellMaterial(String material) {
        String normalized = normalizeMaterial(material, "shellMaterial");
        return normalized.equals(shellMaterial) ? this : new PocketShell(size, normalized, returnDoorMaterial);
    }

    public PocketShell withReturnDoorMaterial(String material) {
        String normalized = normalizeMaterial(material, "returnDoorMaterial");
        return normalized.equals(returnDoorMaterial) ? this : new PocketShell(size, shellMaterial, normalized);
    }

    public static boolean isSupportedSize(int candidate) {
        return candidate >= MIN_SIZE && candidate <= MAX_SIZE;
    }

    /** Accepts {@code minecraft:stone} and {@code stone} alike, and yields {@code STONE}. */
    public static String normalizeMaterial(String material, String field) {
        String required = Objects.requireNonNull(material, field).trim();
        int namespace = required.indexOf(':');
        if (namespace >= 0) {
            required = required.substring(namespace + 1);
        }
        if (required.isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return required.toUpperCase(Locale.ROOT);
    }
}
