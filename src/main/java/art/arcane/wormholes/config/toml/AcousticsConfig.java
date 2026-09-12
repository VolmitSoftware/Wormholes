package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Sound transport through the aperture. Changes hot-reload."
})
public class AcousticsConfig {
    @ConfigDescription("Per-portal acoustics default: off, ambient, ambient_events or full.")
    public String profileDefault = "ambient";
    @ConfigDescription("Destination radius in blocks around the far aperture sampled for sound events.")
    public double radius = 24.0;
    @ConfigDescription("Relayed sounds per second per observer.")
    public int rateCapPerObserver = 8;
}
