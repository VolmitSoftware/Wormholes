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
    @ConfigDescription({
        "Angular guard band around Venticular occlusion silhouettes, in degrees.",
        "Higher values reveal hidden blocks earlier while the observer moves, reducing edge bleed at the cost of sending more geometry.",
        "Set to 0 for exact geometric culling without movement compensation."
    })
    public double occlusionRevealMarginDegrees = 1.0;
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
        "Soft projection frame budget in microseconds per execution thread and manager tick, including final claim flushing.",
        "Recent observer costs predict whether more block work fits; large geometry scans yield at the deadline while committed views and entity updates remain available.",
        "Pending scans continue between scheduled refresh ticks without starting unrelated refreshes early.",
        "Finalization and required cleanup can exceed the budget. Set to 0 for unlimited frame time."
    })
    public int maxFrameMicros = 30_000;
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
    @ConfigDescription({
        "Build the portal-scoped projection stage (destination sampling, block-state transform, buried-cell culling) once per portal and share it between every observer.",
        "Off falls back to per-observer sampling for every cell."
    })
    public boolean sharedPlate = true;
    @ConfigDescription("Total memory the shared view plates may hold; the oldest plate is evicted first.")
    public long plateMaxBytes = 33_554_432L;
    @ConfigDescription("Worker threads that build shared view plates from region snapshots and remote views.")
    public int plateWorkers = 2;
    @ConfigDescription({
        "Keep already-sent projected cells in place when they leave the projection cone or turn hidden while the local wall at the eye-to-cell crossing proves nobody can see them.",
        "Held cells cost no packets; they revert to the real blocks when the portal closes, the local wall changes, the destination changes, or the observer crosses the portal plane."
    })
    public boolean holdInvisibleClaims = true;
    @ConfigDescription("Upper bound on held cells per portal and observer; the oldest held cells revert first once it is exceeded.")
    public int maxHeldCellsPerPortal = 65536;
    @ConfigDescription({
        "Horizontal camera field of view, in degrees, that gives a portal full refresh priority when the per-observer portal budget is scarce; the vertical extent follows a 16:9 screen.",
        "Portals just outside the view and portals already scanned from the observer's current position refresh at reduced priority; portals behind the camera keep what they already show and refresh only when starved."
    })
    public double gazeFovDegrees = 110.0;
    @ConfigDescription("Ticks of head-turn prediction: a portal about to enter the camera cone at the current turn speed is treated as already in view.")
    public int gazeLookaheadTicks = 3;
    @ConfigDescription("A portal that has not refreshed for this many ticks is refreshed next regardless of where the observer looks.")
    public int gazeMaxStarveTicks = 20;
    @ConfigDescription("Finish a projection pass (occlusion filtering and commit) in the same tick its geometry scan completes when frame budget remains.")
    public boolean finishInSlot = true;

    public ProjectionConfig copy() {
        ProjectionConfig copy = new ProjectionConfig();
        copy.range = range;
        copy.refreshIntervalTicks = refreshIntervalTicks;
        copy.nearPlanePadding = nearPlanePadding;
        copy.aperturePaddingBlocks = aperturePaddingBlocks;
        copy.frustumCullingRatio = frustumCullingRatio;
        copy.occlusionRevealMarginDegrees = occlusionRevealMarginDegrees;
        copy.depthBlocks = depthBlocks;
        copy.recursivePortalDepth = recursivePortalDepth;
        copy.stableCellResampleIntervalTicks = stableCellResampleIntervalTicks;
        copy.clientViewDistanceCap = clientViewDistanceCap;
        copy.foveatedUnrendering = foveatedUnrendering;
        copy.observerInterestDot = observerInterestDot;
        copy.sideGraceDot = sideGraceDot;
        copy.maxProjectorsPerTick = maxProjectorsPerTick;
        copy.maxPortalsPerObserverTick = maxPortalsPerObserverTick;
        copy.maxFrameMicros = maxFrameMicros;
        copy.maxNewObserverScansPerTick = maxNewObserverScansPerTick;
        copy.interestGraceTicks = interestGraceTicks;
        copy.initialResendPasses = initialResendPasses;
        copy.maxProjectedCells = maxProjectedCells;
        copy.sharedPlate = sharedPlate;
        copy.plateMaxBytes = plateMaxBytes;
        copy.plateWorkers = plateWorkers;
        copy.holdInvisibleClaims = holdInvisibleClaims;
        copy.maxHeldCellsPerPortal = maxHeldCellsPerPortal;
        copy.gazeFovDegrees = gazeFovDegrees;
        copy.gazeLookaheadTicks = gazeLookaheadTicks;
        copy.gazeMaxStarveTicks = gazeMaxStarveTicks;
        copy.finishInSlot = finishInSlot;
        return copy;
    }
}
