package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Projection level of detail and dissolve. Changes hot-reload."
})
public class LodConfig {
    @ConfigDescription("Depth in blocks beyond which same-material runs along the view axis are merged.")
    public int distanceBlocks = 32;
    @ConfigDescription("Collapse runs along the view axis past distance-blocks; every second slab reuses the previous slab's samples.")
    public boolean mergeRuns = true;
    @ConfigDescription("Depth in blocks beyond which flowers, grass, fences and panes are skipped.")
    public int detailCutoffBlocks = 48;
    @ConfigDescription("Ticks over which a new view is admitted near-to-far and a retiring view is released far-to-near.")
    public int dissolveTicks = 8;
}
