package art.arcane.wormholes.transit;

import java.util.Locale;

public final class TraversalCues {
    public static final String THRESHOLD_SOUND = "minecraft:block.respawn_anchor.set_spawn";
    public static final String OVERWORLD_SOUND = "minecraft:block.amethyst_block.chime";
    public static final String NETHER_SOUND = "minecraft:block.respawn_anchor.deplete";
    public static final String END_SOUND = "minecraft:entity.shulker.teleport";
    public static final int THRESHOLD_PARTICLES = 24;
    public static final double THRESHOLD_SPREAD = 0.35D;
    public static final double THRESHOLD_SPEED = 0.05D;

    private TraversalCues() { }

    public static String particleKey(String effect) {
        if (effect == null || effect.isBlank()) {
            return "minecraft:reverse_portal";
        }
        String name = effect.trim().toLowerCase(Locale.ROOT);
        return name.indexOf(':') < 0 ? "minecraft:" + name : name;
    }

    public static String arrivalSound(String dimension, String override) {
        if (override != null && !override.isBlank()) {
            return override;
        }
        return switch (dimension) {
            case "minecraft:the_nether" -> NETHER_SOUND;
            case "minecraft:the_end" -> END_SOUND;
            default -> OVERWORLD_SOUND;
        };
    }

    public static float arrivalPitch(String dimension) {
        return switch (dimension) {
            case "minecraft:the_nether" -> 0.8F;
            case "minecraft:the_end" -> 1.2F;
            default -> 1.0F;
        };
    }
}
