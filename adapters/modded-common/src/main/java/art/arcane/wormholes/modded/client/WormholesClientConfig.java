package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.render.client.ClientViewSweep;
import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;
import art.arcane.wormholes.util.project.config.TomlCodec;

import java.nio.file.Path;

@ConfigDoc({
    "Wormholes client settings. ClientView streams portal plates to this client and applies them locally; these knobs control that apply path.",
    "Read once when the game starts."
})
public class WormholesClientConfig {
    public static final String FILE_NAME = "wormholes-client.toml";
    public static final int MIN_PLATE_MEMORY_MB = 16;
    public static final int MAX_PLATE_MEMORY_MB = 4096;
    public static final double MAX_HYSTERESIS_BLOCKS = 4.0D;
    public static final int MAX_SECTIONS_PER_TICK = 65535;
    public static final double MAX_ATMOSPHERE_DOMINANCE_BLOCKS = 16.0D;

    @ConfigDescription("Accept ClientView offers from servers. Off keeps this client on the vanilla projection path everywhere.")
    public boolean enabled = true;
    @ConfigDescription("Memory budget in MiB for received plates and cached brick content. Plates that would exceed it are refused.")
    public int maxPlateMemoryMb = 256;
    @ConfigDescription("Write projected cells straight into chunk sections instead of the vanilla block update path. Measure before enabling.")
    public boolean bulkWrite = false;
    @ConfigDescription("Cone hysteresis in blocks: cells enter the projection at this padding and leave at twice it.")
    public double hysteresisBlocks = ClientViewSweep.DEFAULT_HYSTERESIS_BLOCKS;
    @ConfigDescription("Maximum chunk sections touched per tick while applying cone changes. 0 applies everything immediately.")
    public int sectionsPerTick = 0;
    @ConfigDescription("Show a ClientView status line on the F3 debug screen.")
    public boolean showDebugOverlay = false;
    @ConfigDescription("Distance in blocks from a portal plane within which that portal's destination time and weather take over the sky. 0 keeps the local sky.")
    public double atmosphereDominanceBlocks = 2.5D;
    @ConfigDescription("Draw mirror portals from this client's own loaded chunks when the server allows it, so mirror plates are never downloaded.")
    public boolean clientMirror = true;
    @ConfigDescription("Show the portals visible inside a mirror through their own plates when the server streams them, instead of an empty aperture.")
    public boolean clientRecursion = true;
    @ConfigDescription("Show your own reflection in mirrors drawn by this client.")
    public boolean selfReflection = true;
    @ConfigDescription("Show a chat line on this client when a server confirms ClientView. Only this client sees it.")
    public boolean connectionMessage = true;

    public static WormholesClientConfig load(Path configDirectory) {
        WormholesClientConfig loaded = TomlCodec.loadOrCreate(configDirectory.resolve(FILE_NAME).toFile(), WormholesClientConfig.class);
        loaded.normalize();
        return loaded;
    }

    public void normalize() {
        maxPlateMemoryMb = Math.max(MIN_PLATE_MEMORY_MB, Math.min(MAX_PLATE_MEMORY_MB, maxPlateMemoryMb));
        if (!Double.isFinite(hysteresisBlocks) || hysteresisBlocks < 0.0D) {
            hysteresisBlocks = ClientViewSweep.DEFAULT_HYSTERESIS_BLOCKS;
        }
        hysteresisBlocks = Math.min(MAX_HYSTERESIS_BLOCKS, hysteresisBlocks);
        sectionsPerTick = Math.max(0, Math.min(MAX_SECTIONS_PER_TICK, sectionsPerTick));
        if (!Double.isFinite(atmosphereDominanceBlocks) || atmosphereDominanceBlocks < 0.0D) {
            atmosphereDominanceBlocks = 0.0D;
        }
        atmosphereDominanceBlocks = Math.min(MAX_ATMOSPHERE_DOMINANCE_BLOCKS, atmosphereDominanceBlocks);
    }

    public long plateMemoryBytes() {
        return (long) maxPlateMemoryMb * 1024L * 1024L;
    }

    public int plateMemoryMbForHello() {
        return Math.min(65535, maxPlateMemoryMb);
    }

    public int maxFrameBytes() {
        return ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES;
    }
}
