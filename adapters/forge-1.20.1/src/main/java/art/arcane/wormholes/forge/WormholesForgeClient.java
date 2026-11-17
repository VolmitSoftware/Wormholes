package art.arcane.wormholes.forge;

import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.menu.ClientMenuSession;
import art.arcane.wormholes.modded.client.menu.ClientMenus;
import art.arcane.wormholes.modded.client.menu.NativeMenuListener;
import art.arcane.wormholes.modded.client.render.PortalShaders;
import art.arcane.wormholes.modded.clientview.ClientViewPayload;
import art.arcane.wormholes.modded.menu.MenuPayload;
import net.minecraft.client.Minecraft;
import io.netty.buffer.Unpooled;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.event.EventNetworkChannel;

final class WormholesForgeClient {
    private static volatile EventNetworkChannel menuChannel;

    private WormholesForgeClient() {
    }

    static void initialize(EventNetworkChannel menus) {
        menuChannel = menus;
        WormholesClient client = WormholesClient.initialize(FMLPaths.CONFIGDIR.get(), WormholesForgeClient::send);
        ClientMenuSession menuSession = ClientMenus.initialize(WormholesForgeClient::sendMenu, WormholesForgeClient::menuChannelReady);
        menuSession.listener(new NativeMenuListener(menuSession));
        FMLJavaModLoadingContext.get().getModEventBus().addListener((RegisterShadersEvent event) -> PortalShaders.register(
            (id, format, loaded) -> event.registerShader(new ShaderInstance(event.getResourceProvider(), id, format), loaded)));
        IEventBus bus = MinecraftForge.EVENT_BUS;
        bus.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) {
                client.tick(Minecraft.getInstance());
                ClientMenus.tick();
            }
        });
        bus.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> {
            client.connected();
            ClientMenus.connected();
        });
        bus.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            client.disconnected();
            ClientMenus.disconnected();
        });
        QaBootstrap.start();
    }

    static void receive(ClientViewPayload payload, NetworkEvent.Context context) {
        context.enqueueWork(() -> {
            WormholesClient client = WormholesClient.instance();
            if (client != null) {
                client.receive(payload.data(), bytes -> context.getPacketDispatcher().sendPacket(ClientViewPayload.ID, buffer(bytes)));
            }
        });
    }

    static void receiveMenu(MenuPayload payload) {
        ClientMenus.receive(payload.data());
    }

    private static FriendlyByteBuf buffer(byte[] bytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(bytes.length));
        buffer.writeBytes(bytes);
        return buffer;
    }

    private static void send(byte[] bytes) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }
        connection.send(new ServerboundCustomPayloadPacket(ClientViewPayload.ID, buffer(bytes)));
    }

    private static void sendMenu(byte[] bytes) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null || !menuChannelReady()) {
            return;
        }
        connection.send(new ServerboundCustomPayloadPacket(MenuPayload.ID, buffer(bytes)));
    }

    private static boolean menuChannelReady() {
        EventNetworkChannel current = menuChannel;
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return current != null && connection != null && current.isRemotePresent(connection.getConnection());
    }
}
