package art.arcane.wormholes.render.blockentity;

import java.io.IOException;
import java.util.logging.Level;

import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityType;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.service.WormholesTelemetry;

/** Sends a sanitized sample as a block-entity data packet. */
public final class BlockEntityPacketSink implements ProjectedBlockEntityLayer.PacketSink {
    private static final String FAILURE_REASON = "PROJECTION_BLOCK_ENTITY_PACKET_FAILED";

    private volatile boolean failureLogged;

    @Override
    public void send(Player observer, int x, int y, int z, BlockEntitySample sample) {
        if (observer == null || sample == null) {
            return;
        }
        BlockEntityType type = BlockEntityTypes.getByName(sample.typeKey());
        if (type == null) {
            return;
        }
        try {
            NBTCompound nbt = BlockEntityNbt.decode(sample.nbt());
            PacketEvents.getAPI().getPlayerManager().sendPacket(observer,
                new WrapperPlayServerBlockEntityData(new Vector3i(x, y, z), type, nbt));
            WormholesTelemetry.countPacket();
        } catch (IOException | RuntimeException failure) {
            WormholesTelemetry.countFailure(FAILURE_REASON);
            if (failureLogged) {
                return;
            }
            failureLogged = true;
            Wormholes plugin = Wormholes.instance;
            if (plugin != null) {
                plugin.getLogger().log(Level.WARNING, "[blockentity] block entity packet failed for " + sample.typeKey(), failure);
            }
        }
    }
}
