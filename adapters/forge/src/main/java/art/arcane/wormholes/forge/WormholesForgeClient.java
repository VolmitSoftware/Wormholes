package art.arcane.wormholes.forge;

import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.clientview.ClientViewPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.BiConsumer;

final class WormholesForgeClient {
    private static volatile Channel<CustomPacketPayload> channel;

    private WormholesForgeClient() {
    }

    static void initialize(Channel<CustomPacketPayload> viewChannel) {
        channel = viewChannel;
        WormholesClient client = WormholesClient.initialize(FMLPaths.CONFIGDIR.get(), WormholesForgeClient::send);
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> client.tick(Minecraft.getInstance()));
        ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(event -> client.connected());
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> client.disconnected());
    }

    static BiConsumer<ClientViewPayload, CustomPayloadEvent.Context> receiver() {
        return WormholesForgeClient::receive;
    }

    private static void receive(ClientViewPayload payload, CustomPayloadEvent.Context context) {
        context.setPacketHandled(true);
        WormholesClient client = WormholesClient.instance();
        if (client == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener listener = minecraft.getConnection();
        if (listener != null && !minecraft.packetProcessor().isSameThread()) {
            minecraft.packetProcessor().scheduleIfPossible(listener, new ClientboundCustomPayloadPacket(payload));
            return;
        }
        client.receive(payload.data(), bytes -> channel.reply(new ClientViewPayload(bytes), context));
    }

    private static void send(byte[] bytes) {
        Channel<CustomPacketPayload> current = channel;
        if (current == null || Minecraft.getInstance().getConnection() == null) {
            return;
        }
        current.send(new ClientViewPayload(bytes), PacketDistributor.SERVER.noArg());
    }
}
