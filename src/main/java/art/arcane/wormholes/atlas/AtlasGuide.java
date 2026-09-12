package art.arcane.wormholes.atlas;

/**
 * The compass-style bearing the atlas shows on the action bar. Plain text so it reads the same on
 * Java and Bedrock: an eight-way arrow relative to where the player is looking, then the distance.
 */
public final class AtlasGuide {
    private static final String[] ARROWS = {"^", "^>", ">", "v>", "v", "<v", "<", "<^"};
    private static final double DEGREES_PER_SECTOR = 45.0D;

    private AtlasGuide() {
    }

    /** 0 is straight ahead, then clockwise in eighths of a turn. */
    public static int sector(float viewerYaw, double deltaX, double deltaZ) {
        if (deltaX == 0.0D && deltaZ == 0.0D) {
            return 0;
        }
        double target = Math.toDegrees(Math.atan2(-deltaX, deltaZ));
        double relative = wrap(target - viewerYaw);
        return Math.floorMod((int) Math.round(relative / DEGREES_PER_SECTOR), ARROWS.length);
    }

    public static String arrow(int sector) {
        return ARROWS[Math.floorMod(sector, ARROWS.length)];
    }

    public static String bearing(float viewerYaw, double deltaX, double deltaZ) {
        long blocks = Math.round(Math.sqrt(deltaX * deltaX + deltaZ * deltaZ));
        return arrow(sector(viewerYaw, deltaX, deltaZ)) + " " + blocks + "m";
    }

    private static double wrap(double degrees) {
        double wrapped = degrees % 360.0D;
        if (wrapped >= 180.0D) {
            wrapped -= 360.0D;
        }
        if (wrapped < -180.0D) {
            wrapped += 360.0D;
        }
        return wrapped;
    }
}
