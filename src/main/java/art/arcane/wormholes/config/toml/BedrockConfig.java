package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Bedrock and Geyser client degradation profile. Changes hot-reload."
})
public class BedrockConfig {
    @ConfigDescription("Detect Bedrock viewers through the Floodgate API when present, otherwise through the client brand and UUID shape.")
    public boolean enabled = true;
    @ConfigDescription("Send BlockDisplay-based blackout shells, surface skins and name labels to Bedrock viewers.")
    public boolean displayEntities = false;
    @ConfigDescription("Send projected light overlays to Bedrock viewers.")
    public boolean lightingFidelity = false;
    @ConfigDescription("Maximum projected entities per portal for a Bedrock viewer.")
    public int entityCap = 8;
}
