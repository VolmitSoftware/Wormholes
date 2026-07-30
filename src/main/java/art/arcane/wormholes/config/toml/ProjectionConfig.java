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
    @ConfigDescription({
        "Thickness in blocks of the solid blackout shell at the projection's far, left, right, top, and bottom boundaries.",
        "Accepted values are 1 or 2."
    })
    public int blackoutShellThicknessBlocks = 2;
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
    public int maxNewObserverScansPerTick = 64;
    public int interestGraceTicks = 5;
    public int initialResendPasses = 4;
    @ConfigDescription({
        "Hard ceiling on how many blocks a single portal may project in one pass.",
        "Standing inside a portal aperture makes the visible cone approach a full hemisphere, which costs depth-blocks cubed;",
        "when the cone exceeds this budget the render depth is shortened for that pass instead, which keeps the aperture rim intact.",
        "Set to 0 to disable the ceiling (not recommended: the through-portal frame can then cost millions of cells)."
    })
    public int maxProjectedCells = 250000;
}
