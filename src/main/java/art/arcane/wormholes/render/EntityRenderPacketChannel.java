package art.arcane.wormholes.render;

import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;

import art.arcane.wormholes.service.WormholesTelemetry;

class EntityRenderPacketChannel {
    private User batchUser;
    private boolean batchDirty;

    EntityRenderPacketChannel() {
        this.batchUser = null;
        this.batchDirty = false;
    }

    void send(Player observer, PacketWrapper<?> packet) {
        WormholesTelemetry.countPacket();
        if (batchUser != null) {
            batchDirty = true;
            batchUser.writePacket(packet);
            return;
        }
        PacketEvents.getAPI().getPlayerManager().sendPacket(observer, packet);
    }

    void begin(Player observer) {
        batchUser = observer == null ? null : PacketEvents.getAPI().getPlayerManager().getUser(observer);
        batchDirty = false;
    }

    void end() {
        User user = batchUser;
        boolean dirty = batchDirty;
        batchUser = null;
        batchDirty = false;
        if (user != null && dirty) {
            user.flushPackets();
        }
    }
}
