package art.arcane.wormholes.network.view;

import art.arcane.wormholes.render.BukkitProjectorBlocks;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.render.view.OccludedMarker;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public enum BukkitRemoteViewCodec implements RemoteViewCodec<BlockData, EntityData<?>, Equipment> {
    INSTANCE;

    @Override
    public BlockData parseBlock(String state) {
        return Bukkit.createBlockData(state);
    }

    @Override
    public BlockData air() {
        return Material.AIR.createBlockData();
    }

    @Override
    public BlockData occluded() {
        return OccludedMarker.standIn();
    }

    @Override
    public BlockData[] palette(int size) {
        return new BlockData[size];
    }

    @Override
    public boolean blockEntityCandidate(String state) {
        int bracket = state.indexOf('[');
        Material material = Material.matchMaterial(bracket < 0 ? state : state.substring(0, bracket));
        return BukkitProjectorBlocks.defaults().blockEntityCandidate(material);
    }

    @Override
    public List<EntityData<?>> metadata(byte[] data) throws IOException {
        return PacketBlobs.readMetadata(data);
    }

    @Override
    public List<Equipment> equipment(byte[] data) throws IOException {
        return PacketBlobs.readEquipment(data);
    }

    @Override
    public void warning(String message, Throwable error) {
        if (error == null) {
            Wormholes.w(message);
        } else {
            Logger logger = Wormholes.instance == null ? Logger.getLogger("Wormholes") : Wormholes.instance.getLogger();
            logger.log(Level.WARNING, message, error);
        }
    }

    @Override
    public void debug(Supplier<String> message) {
        Wormholes.v(message);
    }
}
