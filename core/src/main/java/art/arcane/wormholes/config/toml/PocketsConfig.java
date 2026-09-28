package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Pocket dimension templates, rosters, and instancing. Changes hot-reload."
})
public class PocketsConfig {
    @ConfigDescription("Folder under the plugin data directory holding vanilla structure files (.nbt) usable as pocket templates.")
    public String templatesDir = "pockets/templates";

    @ConfigDescription("Template pasted into a pocket the first time it is provisioned. Empty leaves the plain cube.")
    public String defaultTemplate = "";

    @ConfigDescription("Most rooms one pocket may grow to, laid out on a 3x3 grid inside its own slot.")
    public int roomsPerPocketMax = 9;

    @ConfigDescription("When an instanced pocket is wiped and re-pasted: on-empty, timer, or manual.")
    public String instanceReset = "on-empty";

    @ConfigDescription("Seconds an empty instance survives before the timer policy resets it.")
    public int instanceResetSeconds = 600;

    @ConfigDescription("Live instances kept before the oldest empty one is evicted.")
    public int instanceMaxLive = 64;

    @ConfigDescription("Whether new pockets let mobs spawn.")
    public boolean rulesDefaultMobs = false;

    @ConfigDescription("Whether new pockets allow player-versus-player damage.")
    public boolean rulesDefaultPvp = false;

    @ConfigDescription("Whether new pockets keep inventory and levels on death.")
    public boolean rulesDefaultKeepInventory = true;

    @ConfigDescription("Fixed client time inside new pockets, in ticks. -1 follows the pocket world.")
    public long rulesDefaultFixedTime = -1L;

    @ConfigDescription("Who may build in new pockets: everyone, builders, or owner.")
    public String rulesDefaultBuild = "builders";
}
