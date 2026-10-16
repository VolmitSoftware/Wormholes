package art.arcane.wormholes.neoforge;

import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.menu.ClientMenuSession;
import art.arcane.wormholes.modded.client.menu.ClientMenus;
import art.arcane.wormholes.modded.client.menu.NativeMenuListener;
import art.arcane.wormholes.modded.clientview.ClientViewPayload;
import art.arcane.wormholes.modded.menu.MenuPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = "wormholes", dist = Dist.CLIENT)
public final class WormholesNeoForgeClient {
    private final WormholesClient client;

    public WormholesNeoForgeClient(IEventBus bus) {
        client = WormholesClient.initialize(FMLPaths.CONFIGDIR.get(), WormholesNeoForgeClient::send);
        bus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> client.tick(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> client.connected());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> client.disconnected());
        ClientMenuSession menus = ClientMenus.initialize(WormholesNeoForgeClient::sendMenu, WormholesNeoForgeClient::menuChannelReady);
        menus.listener(new NativeMenuListener(menus));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> ClientMenus.tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> ClientMenus.connected());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> ClientMenus.disconnected());
    }

    private void registerPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(ClientViewPayload.TYPE, (payload, context) ->
            client.receive(payload.data(), bytes -> context.reply(new ClientViewPayload(bytes))));
        event.register(MenuPayload.TYPE, (payload, context) -> ClientMenus.receive(payload.data()));
    }

    private static void send(byte[] bytes) {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        ClientPacketDistributor.sendToServer(new ClientViewPayload(bytes));
    }

    private static void sendMenu(byte[] bytes) {
        if (!menuChannelReady()) {
            return;
        }
        ClientPacketDistributor.sendToServer(new MenuPayload(bytes));
    }

    private static boolean menuChannelReady() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(MenuPayload.TYPE);
    }
}
