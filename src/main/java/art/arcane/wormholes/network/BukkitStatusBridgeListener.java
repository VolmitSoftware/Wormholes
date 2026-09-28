package art.arcane.wormholes.network;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.handshaking.client.WrapperHandshakingClientHandshake;
import com.github.retrooper.packetevents.wrapper.status.server.WrapperStatusServerResponse;
import com.google.gson.JsonObject;

public final class BukkitStatusBridgeListener extends PacketListenerAbstract {
    private final MinecraftStatusBridge bridge;

    public BukkitStatusBridgeListener(MinecraftStatusBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Handshaking.Client.HANDSHAKE) {
            return;
        }
        WrapperHandshakingClientHandshake handshake = new WrapperHandshakingClientHandshake(event);
        if (handshake.getIntention() == WrapperHandshakingClientHandshake.ConnectionIntention.STATUS) {
            bridge.receiveHandshake(event.getChannel(), handshake.getServerAddress());
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Status.Server.RESPONSE) {
            return;
        }
        String response = bridge.takeResponse(event.getChannel());
        if (response == null) {
            return;
        }
        WrapperStatusServerResponse wrapper = new WrapperStatusServerResponse(event);
        JsonObject component = wrapper.getComponent();
        component.remove("favicon");
        component.remove("description");
        component.remove("players");
        component.addProperty("wormholes", response);
        wrapper.setComponent(component);
        event.markForReEncode(true);
    }
}
