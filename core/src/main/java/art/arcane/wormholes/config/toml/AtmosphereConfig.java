package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Destination atmosphere through the aperture. Changes hot-reload."
})
public class AtmosphereConfig {
    @ConfigDescription("Per-portal atmosphere default: off, tint, tint_light or full.")
    public String modeDefault = "off";
    @ConfigDescription("Retint the biome of chunk sections the view volume dominates so grass, foliage, water and fog take the destination colour.")
    public boolean biomeTint = false;
    @ConfigDescription("Fraction of a chunk section's 4x4x4 biome cells the view must cover before that section is retinted.")
    public double biomeDominance = 0.6;
    @ConfigDescription("Promote destination sky-light rebasing to portals whose atmosphere mode is tint_light or full, even when render.lighting-fidelity is off. False is the hard off switch.")
    public boolean skyLight = false;
    @ConfigDescription("Give the blackout far shell a destination-derived block (end stone, netherrack, sky-tinted glass) on portals in full atmosphere mode.")
    public boolean fogPlate = true;
    @ConfigDescription("Relay destination rain and snow as particles inside the view volume and dim projected sky light while it storms.")
    public boolean weather = true;
}
