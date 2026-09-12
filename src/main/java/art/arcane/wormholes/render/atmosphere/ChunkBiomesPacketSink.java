package art.arcane.wormholes.render.atmosphere;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.bukkit.World;
import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.util.Vector2i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkBiomes;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.service.WormholesTelemetry;

/**
 * Sends chunk-column biome grids through the chunk-biomes packet. The packet replaces every section's
 * biome container, so callers always hand over full columns (local biomes plus overrides).
 */
public final class ChunkBiomesPacketSink implements BiomeSink {
    private static final String FAILURE_REASON = "ATMOSPHERE_BIOME_PACKET_FAILED";

    private volatile Constructor<WrapperPlayServerChunkBiomes.ChunkBiomeData> dataConstructor;
    private volatile boolean unavailable;
    private volatile boolean failureLogged;

    @Override
    public void send(Player observer, World world, List<BiomeClaimSet.ChunkBiomes> chunks) {
        if (unavailable || observer == null || chunks == null || chunks.isEmpty()) {
            return;
        }
        Constructor<WrapperPlayServerChunkBiomes.ChunkBiomeData> constructor = resolveConstructor();
        if (constructor == null) {
            return;
        }
        try {
            Map<Vector2i, WrapperPlayServerChunkBiomes.ChunkBiomeData> payload =
                new LinkedHashMap<Vector2i, WrapperPlayServerChunkBiomes.ChunkBiomeData>(chunks.size() * 2);
            for (BiomeClaimSet.ChunkBiomes chunk : chunks) {
                payload.put(new Vector2i(chunk.chunkX(), chunk.chunkZ()), constructor.newInstance(palettes(chunk)));
            }
            PacketEvents.getAPI().getPlayerManager().sendPacket(observer, new WrapperPlayServerChunkBiomes(payload));
            WormholesTelemetry.countPacket();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            unavailable = true;
            noteFailure(failure);
        }
    }

    static List<DataPalette> palettes(BiomeClaimSet.ChunkBiomes chunk) {
        int[][] sections = chunk.sections();
        List<DataPalette> palettes = new ArrayList<DataPalette>(sections.length);
        for (int[] section : sections) {
            DataPalette palette = DataPalette.createForBiome();
            for (int index = 0; index < section.length; index++) {
                int qx = index & 3;
                int qz = (index >> 2) & 3;
                int qy = (index >> 4) & 3;
                palette.set(qx, qy, qz, section[index]);
            }
            palettes.add(palette);
        }
        return palettes;
    }

    private Constructor<WrapperPlayServerChunkBiomes.ChunkBiomeData> resolveConstructor() {
        Constructor<WrapperPlayServerChunkBiomes.ChunkBiomeData> resolved = dataConstructor;
        if (resolved != null) {
            return resolved;
        }
        try {
            resolved = WrapperPlayServerChunkBiomes.ChunkBiomeData.class.getDeclaredConstructor(List.class);
            resolved.setAccessible(true);
            dataConstructor = resolved;
            return resolved;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            unavailable = true;
            noteFailure(failure);
            return null;
        }
    }

    private void noteFailure(Exception failure) {
        WormholesTelemetry.countFailure(FAILURE_REASON);
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        Wormholes plugin = Wormholes.instance;
        if (plugin != null) {
            plugin.getLogger().log(Level.WARNING, "[atmosphere] chunk biome packet failed; biome tint disabled until reload", failure);
        }
    }
}
