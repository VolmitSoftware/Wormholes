package art.arcane.wormholes.fabric;

import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.menu.ClientMenuSession;
import art.arcane.wormholes.modded.client.menu.ClientMenus;
import art.arcane.wormholes.modded.client.menu.NativeMenuListener;
import art.arcane.wormholes.modded.client.render.PortalShaders;
import art.arcane.wormholes.modded.clientview.ClientViewPayload;
import art.arcane.wormholes.modded.menu.MenuPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;

public final class WormholesFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        WormholesClient client = WormholesClient.initialize(FabricLoader.getInstance().getConfigDir(), WormholesFabricClient::send);
        ClientPlayNetworking.registerGlobalReceiver(ClientViewPayload.ID, (minecraft, handler, buffer, sender) -> {
            byte[] data = ClientViewPayload.read(buffer).data();
            minecraft.execute(() -> client.receive(data, bytes -> reply(sender, bytes)));
        });
        ClientTickEvents.END_CLIENT_TICK.register(client::tick);
        CoreShaderRegistrationCallback.EVENT.register(context -> PortalShaders.register(context::register));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> client.connected());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> client.disconnected());
        ClientMenuSession menus = ClientMenus.initialize(WormholesFabricClient::sendMenu, () -> ClientPlayNetworking.canSend(MenuPayload.ID));
        menus.listener(new NativeMenuListener(menus));
        ClientPlayNetworking.registerGlobalReceiver(MenuPayload.ID, (minecraft, handler, buffer, sender) -> {
            byte[] data = MenuPayload.read(buffer).data();
            minecraft.execute(() -> ClientMenus.receive(data));
        });
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> ClientMenus.tick());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> ClientMenus.connected());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> ClientMenus.disconnected());
        QaBootstrap.start();
    }

    private static void reply(PacketSender sender, byte[] bytes) {
        sender.sendPacket(ClientViewPayload.ID, buffer(new ClientViewPayload(bytes)));
    }

    private static void send(byte[] bytes) {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        ClientPlayNetworking.send(ClientViewPayload.ID, buffer(new ClientViewPayload(bytes)));
    }

    private static void sendMenu(byte[] bytes) {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        FriendlyByteBuf buffer = PacketByteBufs.create();
        new MenuPayload(bytes).write(buffer);
        ClientPlayNetworking.send(MenuPayload.ID, buffer);
    }

    private static FriendlyByteBuf buffer(ClientViewPayload payload) {
        FriendlyByteBuf buffer = PacketByteBufs.create();
        payload.write(buffer);
        return buffer;
    }
}
