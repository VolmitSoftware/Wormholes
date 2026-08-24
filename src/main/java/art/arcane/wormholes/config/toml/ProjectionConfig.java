package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Advanced projection compatibility overrides."
})
public class ProjectionConfig {
    public double range = 48.0;
    public int refreshIntervalTicks = 1;
    public double nearPlanePadding = 2.0;
    @ConfigDescription({
        "How far (in blocks) the projected image extends outward past the portal aperture edges.",
        "Raise this if real blocks bleed through at the rim of the projection; each +0.25 widens the rendered window by a quarter block on every side."
    })
    public double aperturePaddingBlocks = 0.75;
    public double frustumCullingRatio = 0.2;
    public int depthBlocks = 64;
    public int recursivePortalDepth = 3;
    public int stableCellResampleIntervalTicks = 4;
    public boolean clientViewDistanceCap = true;
    public boolean foveatedUnrendering = false;
    public double observerInterestDot = -0.2;
    public double sideGraceDot = 0.12;
    public int maxProjectorsPerTick = 24;
    public int maxPortalsPerObserverTick = 4;
    @ConfigDescription({
        "Maximum player-owner reconciliation frames admitted per tick across normal projection and surface skins.",
        "Existing observer state has priority while a rotating share remains available for discovery."
    })
    public int maxNewObserverScansPerTick = 64;
    public int interestGraceTicks = 5;
    @ConfigDescription({
        "Number of complete startup projection sends after a view is created.",
        "One pass establishes the client view; raise this only to diagnose a packet intermediary that loses initial block changes."
    })
    public int initialResendPasses = 1;
    @ConfigDescription({
        "Hard ceiling on how many candidate block positions a single portal may scan in one pass.",
        "Budget fitting removes lateral padding first so normal close-range views keep their configured depth;",
        "depth is shortened only when the aperture-aligned scan still exceeds this ceiling.",
        "An aperture that cannot fit even at zero depth stays empty instead of exceeding the ceiling.",
        "Set to 0 to disable the ceiling (not recommended: the through-portal scan can then cost millions of cells)."
    })
    public int maxProjectedCells = 250000;
}
