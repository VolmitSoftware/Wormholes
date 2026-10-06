package art.arcane.optics.fidelity;

public final class FogPlatePolicy {
    private FogPlatePolicy() {
    }

    public static boolean applies(boolean fogPlateEnabled, AtmosphereMode mode) {
        return fogPlateEnabled && mode != null && mode.usesFogPlate();
    }

    public static String shellState(Dimension environment) {
        if (environment == null) {
            return null;
        }
        return switch (environment) {
            case OVERWORLD -> "minecraft:light_blue_stained_glass";
            case NETHER -> "minecraft:netherrack";
            case END -> "minecraft:end_stone";
            default -> null;
        };
    }

    public enum Dimension {
        OVERWORLD, NETHER, END, CUSTOM
    }
}
