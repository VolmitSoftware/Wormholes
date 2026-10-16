package art.arcane.wormholes.forge;

import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.menu.ClientMenuSession;
import art.arcane.wormholes.modded.client.menu.ClientMenus;
import art.arcane.wormholes.modded.client.menu.NativeMenuListener;
import art.arcane.wormholes.modded.clientview.ClientViewPayload;
import art.arcane.wormholes.modded.menu.MenuPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
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
    private static volatile Channel<CustomPacketPayload> menuChannel;

    private WormholesForgeClient() {
    }

    static void initialize(Channel<CustomPacketPayload> viewChannel, Channel<CustomPacketPayload> menus) {
        channel = viewChannel;
        menuChannel = menus;
        WormholesClient client = WormholesClient.initialize(FMLPaths.CONFIGDIR.get(), WormholesForgeClient::send);
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> client.tick(Minecraft.getInstance()));
        ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(event -> client.connected());
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> client.disconnected());
        ClientMenuSession menuSession = ClientMenus.initialize(WormholesForgeClient::sendMenu, WormholesForgeClient::menuChannelReady);
        menuSession.listener(new NativeMenuListener(menuSession));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> ClientMenus.tick());
        ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(event -> ClientMenus.connected());
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> ClientMenus.disconnected());
    }

    static BiConsumer<ClientViewPayload, CustomPayloadEvent.Context> receiver() {
        return WormholesForgeClient::receive;
    }

    static void receiveMenu(MenuPayload payload) {
        ClientMenus.receive(payload.data());
    }

    private static void receive(ClientViewPayload payload, CustomPayloadEvent.Context context) {
        context.setPacketHandled(true);
        WormholesClient client = WormholesClient.instance();
        if (client == null) {
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

    private static void sendMenu(byte[] bytes) {
        Channel<CustomPacketPayload> current = menuChannel;
        if (current == null || !menuChannelReady()) {
            return;
        }
        current.send(new MenuPayload(bytes), PacketDistributor.SERVER.noArg());
    }

    private static boolean menuChannelReady() {
        Channel<CustomPacketPayload> current = menuChannel;
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return current != null && connection != null && current.isRemotePresent(connection.getConnection());
    }
}
