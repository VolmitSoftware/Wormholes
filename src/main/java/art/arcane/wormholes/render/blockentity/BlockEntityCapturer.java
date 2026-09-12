package art.arcane.wormholes.render.blockentity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;

import org.bukkit.Chunk;
import org.bukkit.block.BlockState;

import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.render.ProjectionCellKey;

/**
 * Turns a block state into a sanitized {@link BlockEntitySample}: the vanilla snapshot tag when the
 * platform exposes one, otherwise the tag assembled from the Bukkit API. The path that produced the
 * last sample is reported through {@link #activePath()} for the admin telemetry line.
 */
public final class BlockEntityCapturer {
    public static final int MAX_PER_CHUNK = 64;
    private static final String PATH_SNAPSHOT = "snapshot-nbt";
    private static final String PATH_BUKKIT = "bukkit-api";
    private static final String PATH_NONE = "none";

    private static volatile String activePath = PATH_NONE;

    private BlockEntityCapturer() {
    }

    public static String activePath() {
        return activePath;
    }

    public static BlockEntitySample capture(BlockState state) {
        return capture(state, FidelitySettings.blockEntityTypes, FidelitySettings.blockEntityContainers);
    }

    public static BlockEntitySample capture(BlockState state, List<String> whitelist, boolean containers) {
        if (state == null) {
            return null;
        }
        String typeKey = BlockEntityMaterials.typeKey(state.getType());
        if (typeKey == null || !BlockEntityMaterials.allowed(typeKey, whitelist, containers)) {
            return null;
        }
        NBTCompound tag = null;
        byte[] vanilla = WormholesPlatform.blockEntityNbt(state);
        if (vanilla != null) {
            try {
                tag = BlockEntityNbt.decode(vanilla);
                activePath = PATH_SNAPSHOT;
            } catch (IOException | RuntimeException unreadable) {
                WormholesPlatform.disableSnapshotNbt();
            }
        }
        if (tag == null) {
            tag = BlockEntityNbtAssembler.assemble(state);
            if (tag == null) {
                return null;
            }
            activePath = PATH_BUKKIT;
        }
        return BlockEntitySanitizer.sanitize(typeKey, tag, whitelist, containers);
    }

    /** Whitelisted block entities of a loaded chunk keyed by world cell; call on the chunk's region thread. */
    public static Map<Long, BlockEntitySample> captureChunk(Chunk chunk) {
        Map<Long, BlockEntitySample> samples = new HashMap<Long, BlockEntitySample>(8);
        if (chunk == null || !FidelitySettings.blockEntities) {
            return samples;
        }
        BlockState[] states;
        try {
            states = chunk.getTileEntities();
        } catch (RuntimeException unavailable) {
            return samples;
        }
        if (states == null) {
            return samples;
        }
        for (BlockState state : states) {
            if (samples.size() >= MAX_PER_CHUNK) {
                break;
            }
            if (state == null || !BlockEntityMaterials.isCandidate(state.getType())) {
                continue;
            }
            BlockEntitySample sample = capture(state);
            if (sample != null) {
                samples.put(Long.valueOf(ProjectionCellKey.pack(state.getX(), state.getY(), state.getZ())), sample);
            }
        }
        return samples;
    }

    public static byte[] encode(BlockEntitySample sample) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(sample.nbt().length + 32);
        DataOutputStream out = new DataOutputStream(buffer);
        sample.write(out);
        out.flush();
        return buffer.toByteArray();
    }

    public static BlockEntitySample decode(byte[] payload) throws IOException {
        return BlockEntitySample.read(new DataInputStream(new ByteArrayInputStream(payload)));
    }
}
