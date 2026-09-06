package art.arcane.wormholes.network;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.handshaking.client.WrapperHandshakingClientHandshake;

public final class TransferGate extends PacketListenerAbstract {
    private final boolean nativeAcceptingTransfers;

    public TransferGate(boolean nativeAcceptingTransfers) {
        super(PacketListenerPriority.LOW);
        this.nativeAcceptingTransfers = nativeAcceptingTransfers;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Handshaking.Client.HANDSHAKE) {
            return;
        }
        NetworkConfig config = Wormholes.settings == null ? null : Wormholes.settings.getNetwork();
        if (!shouldRewrite(config, nativeAcceptingTransfers)) {
            return;
        }
        WrapperHandshakingClientHandshake handshake = new WrapperHandshakingClientHandshake(event);
        if (handshake.getIntention() != WrapperHandshakingClientHandshake.ConnectionIntention.TRANSFER) {
            return;
        }
        Wormholes.v("[transfer-gate] TRANSFER handshake client=" + event.getSocketAddress()
            + " target=" + singleLine(handshake.getServerAddress()) + ":" + handshake.getServerPort()
            + " protocol=" + handshake.getProtocolVersion() + " rewriting=LOGIN");
        handshake.setIntention(WrapperHandshakingClientHandshake.ConnectionIntention.LOGIN);
        event.markForReEncode(true);
    }

    static boolean shouldRewrite(NetworkConfig config, boolean nativeAcceptingTransfers) {
        return !nativeAcceptingTransfers && config != null && config.enabled && config.autoAcceptTransfers;
    }

    private static String singleLine(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.replace('\0', '|').replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
    }
}
