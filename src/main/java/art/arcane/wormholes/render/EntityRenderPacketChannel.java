package art.arcane.wormholes.render;

import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;

import art.arcane.wormholes.service.WormholesTelemetry;

class EntityRenderPacketChannel {
    private User batchUser;

    EntityRenderPacketChannel() {
        this.batchUser = null;
    }

    void send(Player observer, PacketWrapper<?> packet) {
        WormholesTelemetry.countPacket();
        if (batchUser != null) {
            batchUser.writePacket(packet);
            return;
        }
        PacketEvents.getAPI().getPlayerManager().sendPacket(observer, packet);
    }

    void begin(Player observer) {
        batchUser = observer == null ? null : PacketEvents.getAPI().getPlayerManager().getUser(observer);
    }

    void end() {
        User user = batchUser;
        batchUser = null;
        if (user != null) {
            user.flushPackets();
        }
    }
}
