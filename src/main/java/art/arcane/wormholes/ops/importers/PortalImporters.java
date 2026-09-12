package art.arcane.wormholes.ops.importers;

import java.util.List;

/** The importers the operator can name on the command line. */
public final class PortalImporters {
    private PortalImporters() {
    }

    public static List<String> ids() {
        return List.of("stargate", "advancedportals", "multiverse", "betterportals", "essentials");
    }

    /** Blank means no frame; anything else must be {@code width,height}. */
    public static int[] parseFrame(String frame) {
        if (frame == null || frame.isBlank()) {
            return new int[]{0, 0};
        }
        String[] parts = frame.split(",");
        if (parts.length != 2) {
            throw new IllegalArgumentException("frame must be width,height");
        }
        try {
            int width = Integer.parseInt(parts[0].trim());
            int height = Integer.parseInt(parts[1].trim());
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("frame width and height must be at least 1");
            }
            return new int[]{width, height};
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("frame must be width,height");
        }
    }

    /** Null when the id is not one of {@link #ids()}. */
    public static PortalImporter byId(String id, int frameWidth, int frameHeight) {
        if (id == null) {
            return null;
        }
        return switch (id.trim().toLowerCase()) {
            case "stargate" -> new StargateImporter();
            case "advancedportals" -> new AdvancedPortalsImporter();
            case "multiverse" -> new MultiversePortalsImporter();
            case "betterportals" -> new BetterPortalsImporter();
            case "essentials" -> new EssentialsWarpsImporter(frameWidth, frameHeight);
            default -> null;
        };
    }
}
