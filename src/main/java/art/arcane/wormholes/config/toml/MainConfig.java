package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Gameplay settings. Changes hot-reload."
})
public class MainConfig {
    @ConfigDescription("Bundled locale to use. An optional plugins/Wormholes/languages/<locale>.toml file overrides individual bundled messages.")
    public String language = "en_US";
    @ConfigDescription("Comma-separated bundled or custom fallback locales in priority order. Built-in English is always the final fallback.")
    public String languageFallbacks = "";
    @ConfigDescription("Compatibility override for portal particles. Prefer the top-level quality profile.")
    public boolean enableParticles = true;

    @ConfigDescription("Replace vanilla Nether and End portals with auto-linking Wormholes portals.")
    public boolean replaceNetherAndEndPortals = true;

    @ConfigDescription("Enable the complete Dimensional Doors survival feature set.")
    public boolean dimensionalDoorsEnabled = true;
    public double portalConstructSpeed = 0.975;
    public double portalCollapseSpeed = 0.91;
    public boolean verboseLogging = false;
    public boolean debugRendering = false;
    public int teleportCooldownMillis = 1000;
    @ConfigDescription("Allow other plugins to price or veto a portal traversal through the Wormholes traversal API. When false, no cost provider is called and neither traversal event fires.")
    public boolean traversalApiEnabled = true;
    @ConfigDescription("What to do when a third-party traversal cost provider throws or misbehaves: allow (treat it as a refusal to charge and let the traversal through free) or deny (close the portal).")
    public String traversalApiProviderFailurePolicy = "allow";
    @ConfigDescription("Failures before a misbehaving traversal cost provider is disabled for this session. 0 disables quarantine.")
    public int traversalApiProviderFaultLimit = 5;
    @ConfigDescription("Warn when a single traversal cost provider call takes at least this many milliseconds. 0 disables the warning.")
    public int traversalApiSlowProviderMillis = 5;
    @ConfigDescription("Send the destination's chunks to the player during the moment a portal traversal commits, so the destination world is already drawn when they arrive instead of appearing blank while it streams in. Off by default until verified on a live server.")
    public boolean chunkPreSendEnabled = false;
    @ConfigDescription("How many chunks out from the destination to pre-send. Further clamped to what the client will accept.")
    public int chunkPreSendRadiusChunks = 3;
    @ConfigDescription("Hard ceiling on chunks pre-sent for one traversal. A partial pre-send is a shorter blink; an overrun is a server stall.")
    public int chunkPreSendMaxChunks = 32;
    @ConfigDescription("Microseconds of the commitment tick the pre-send may use before it stops early and delivers what it has.")
    public int chunkPreSendBudgetMicros = 2000;
    public boolean arrivalPrewarmOnInterest = true;
    public int arrivalWarmRadiusChunks = 4;
    public int arrivalWarmMaxRadiusChunks = 10;
    public int arrivalWarmHoldMillis = 5000;
    public int arrivalWarmThrottleMillis = 1000;
    public boolean arrivalTransitionMask = true;
    public int arrivalTransitionMaskTicks = 25;

    @ConfigDescription("Raise the server's per-player chunk streaming limits once at startup so destination geometry can reach the client before arrival. Only ever raises; a higher server value is left alone. Applied once and never touched again.")
    public boolean chunkSendRateTuner = true;
    @ConfigDescription("Target per-player chunk send rate in chunks per second. Paper's compiled default is 75. Zero or negative means unlimited, and anything above 10000 is treated as unlimited.")
    public double chunkSendRateTarget = 1000.0;
    @ConfigDescription("Target per-player chunk load rate in chunks per second. Paper's compiled default is 100. Zero or negative means unlimited, and anything above 10000 is treated as unlimited.")
    public double chunkLoadRateTarget = 1000.0;
}
