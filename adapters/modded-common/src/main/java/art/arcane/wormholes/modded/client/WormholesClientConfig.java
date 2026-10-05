package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.render.client.ClientViewSweep;
import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;
import art.arcane.wormholes.util.project.config.TomlCodec;

import java.nio.file.Path;

@ConfigDoc({
    "Wormholes client settings. ClientView streams destination sections and renders them through the portal opening.",
    "Read once when the game starts."
})
public class WormholesClientConfig {
    public static final String FILE_NAME = "wormholes-client.toml";
    public static final int MIN_PLATE_MEMORY_MB = 16;
    public static final int MAX_PLATE_MEMORY_MB = 4096;
    public static final double MAX_HYSTERESIS_BLOCKS = 4.0D;
    public static final int MAX_SECTIONS_PER_TICK = 65535;
    public static final double MAX_ATMOSPHERE_DOMINANCE_BLOCKS = 16.0D;

    @ConfigDescription("Portal renderer: native uses ClientView; block-packets uses the server's standard block and entity packets. Restart the game after changing it.")
    public String renderer = Renderer.NATIVE.key();
    @ConfigDescription("Shared memory budget in MiB for received portal sections, plates and cached brick content. Updates that would exceed it are refused.")
    public int maxPlateMemoryMb = 256;
    @ConfigDescription("Batch block writes for plate-based ClientView. The dedicated portal renderer does not write projected blocks into local chunks.")
    public boolean bulkWrite = false;
    @ConfigDescription("Edge hysteresis in blocks for plate-based ClientView. The dedicated renderer clips to the portal opening instead.")
    public double hysteresisBlocks = ClientViewSweep.DEFAULT_HYSTERESIS_BLOCKS;
    @ConfigDescription("Maximum chunk sections changed per tick for plate-based ClientView. 0 applies all changes; dedicated rendering streams sections progressively.")
    public int sectionsPerTick = 0;
    @ConfigDescription("Show detailed ClientView metrics on the F3 debug screen alongside the connection status.")
    public boolean showDebugOverlay = false;
    @ConfigDescription("Distance in blocks from a portal plane within which that portal's destination time and weather take over the sky. 0 keeps the local sky.")
    public double atmosphereDominanceBlocks = 2.5D;
    @ConfigDescription("Enable client mirror views when the server allows them.")
    public boolean clientMirror = true;
    @ConfigDescription("Show nested mirrors and portals through their own destinations when the server streams them.")
    public boolean clientRecursion = true;
    @ConfigDescription("Show your own reflection in mirrors drawn by this client.")
    public boolean selfReflection = true;

    public static WormholesClientConfig load(Path configDirectory) {
        WormholesClientConfig loaded = TomlCodec.loadOrCreate(configDirectory.resolve(FILE_NAME).toFile(), WormholesClientConfig.class);
        loaded.normalize();
        return loaded;
    }

    public void normalize() {
        renderer = rendererMode().key();
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

    public Renderer rendererMode() {
        return Renderer.parse(renderer);
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

    public enum Renderer {
        NATIVE("native"),
        BLOCK_PACKETS("block-packets");

        private final String key;

        Renderer(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static Renderer parse(String key) {
            return switch (key) {
                case "native" -> NATIVE;
                case "block-packets" -> BLOCK_PACKETS;
                case null, default -> throw new IllegalArgumentException("renderer must be native or block-packets");
            };
        }
    }

}
