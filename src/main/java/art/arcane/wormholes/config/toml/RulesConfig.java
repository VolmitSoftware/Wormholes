package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Traversal rules engine defaults. Changes hot-reload."
})
public class RulesConfig {
    @ConfigDescription("Horizontal distance in blocks a traveler may drift during a warmup before it cancels.")
    public double warmupCancelMoveBlocks = 0.5D;

    @ConfigDescription("Cancel a warmup when the traveler takes damage.")
    public boolean warmupCancelOnDamage = true;

    @ConfigDescription("Upper clamp for a portal's warmup in seconds.")
    public int warmupMaxSeconds = 30;

    @ConfigDescription("Upper clamp for a portal's cooldown in seconds.")
    public int cooldownMaxSeconds = 3600;

    @ConfigDescription("Show the route card (name, destination, price, cooldown, refusal reason) on the action bar near portals.")
    public boolean routeCardEnabled = true;

    @ConfigDescription("Distance in blocks from the aperture plane at which the route card appears.")
    public double routeCardRange = 6.0D;

    @ConfigDescription("Route card refresh cadence in ticks per player.")
    public int routeCardIntervalTicks = 10;

    @ConfigDescription("Cache lifetime in milliseconds for expensive conditions such as PlaceholderAPI and advancements.")
    public int expensiveConditionCacheMillis = 1000;

    @ConfigDescription("Allow rule effects that run a console command. The rule author must hold wormholes.admin.")
    public boolean commandEffectsConsoleAllowed = true;

    @ConfigDescription("Seconds between charge pool regeneration ticks.")
    public int chargesRegenIntervalSeconds = 60;
}
