package art.arcane.wormholes.render.bedrock;

import art.arcane.wormholes.render.FidelitySettings;

/**
 * Rendering caps applied to one viewer. Java viewers get every channel; Bedrock viewers (through
 * Geyser) keep block changes, block entities and sounds, and lose display entities, light overlays
 * and entity count beyond the configured caps unless the operator opts them back in.
 */
public record BedrockProfile(boolean bedrock, boolean displayEntities, boolean lightingFidelity, int entityCap, int blockBatchLimit) {
    public static final int BLOCK_BATCH_LIMIT = 64;
    public static final BedrockProfile JAVA = new BedrockProfile(false, true, true, Integer.MAX_VALUE, Integer.MAX_VALUE);

    public static BedrockProfile forBedrock() {
        return new BedrockProfile(true, FidelitySettings.bedrockDisplayEntities, FidelitySettings.bedrockLightingFidelity,
            FidelitySettings.bedrockEntityCap, BLOCK_BATCH_LIMIT);
    }

    public boolean withholdsDisplays() {
        return !displayEntities;
    }

    public int entityLimit(int configuredLimit) {
        return Math.min(configuredLimit, entityCap);
    }
}
