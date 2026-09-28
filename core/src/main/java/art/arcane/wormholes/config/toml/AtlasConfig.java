package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Player-facing portal atlas. Changes hot-reload."
})
public class AtlasConfig {
    @ConfigDescription("Enable /atlas, portal discovery, favorites, recents, and the guide bearing.")
    public boolean enabled = true;

    @ConfigDescription("Portals outside a public network appear in a player's atlas only after that player stood at them.")
    public boolean discoveryRequired = true;

    @ConfigDescription("Distance in blocks from a portal aperture that counts as a visit.")
    public double discoveryRadius = 6.0D;

    @ConfigDescription("Show portal coordinates in the atlas list.")
    public boolean showCoordinates = false;

    @ConfigDescription("Portals kept in a player's recent list.")
    public int recentLimit = 10;

    @ConfigDescription("Portals a player may pin as favorites.")
    public int favoritesLimit = 27;

    @ConfigDescription("Show an action-bar bearing to the portal a player is guiding to.")
    public boolean guideEnabled = true;
}
