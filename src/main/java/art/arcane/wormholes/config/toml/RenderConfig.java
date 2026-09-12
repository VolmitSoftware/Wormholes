package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

import java.util.ArrayList;
import java.util.List;

@ConfigDoc({
    "Advanced visual compatibility overrides."
})
public class RenderConfig {
    @ConfigDescription("Send destination-world lighting with projected blocks.")
    public boolean lightingFidelity = false;
    @ConfigDescription("Show destination-side entities inside portal projections.")
    public boolean entitySpoofing = true;
    public int lightingRefreshIntervalTicks = 4;
    public int lightingMaxSectionsPerPass = 2;
    public boolean adaptiveLighting = true;
    public int entityUpdateIntervalTicks = 1;
    public double entitySpoofRange = 48.0;
    public int entityCandidateCacheTicks = 3;
    public int maxSpoofedEntities = 24;
    public double captureZoneRadius = 8.0;
    @ConfigDescription("Project sign text, banner patterns, heads, decorated pots, bells and spawner mob types through the aperture.")
    public boolean blockEntities = true;
    @ConfigDescription("Block-entity data packets sent per observer per tick.")
    public int blockEntityBudgetPerTick = 64;
    @ConfigDescription("Block-entity types (minecraft namespace implied) whose appearance is projected.")
    public List<String> blockEntityTypes = new ArrayList<String>(DEFAULT_BLOCK_ENTITY_TYPES);
    @ConfigDescription("Send container contents with projected block entities. Contents never cross while this is false.")
    public boolean blockEntityContainers = false;

    public static final List<String> DEFAULT_BLOCK_ENTITY_TYPES = List.of(
        "sign", "hanging_sign", "banner", "skull", "decorated_pot", "bell", "spawner");
}
