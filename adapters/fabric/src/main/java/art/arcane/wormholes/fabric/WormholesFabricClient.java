package art.arcane.wormholes.fabric;

import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.clientview.ClientViewPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

public final class WormholesFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        WormholesClient client = WormholesClient.initialize(FabricLoader.getInstance().getConfigDir(), WormholesFabricClient::send);
        ClientConfigurationNetworking.registerGlobalReceiver(ClientViewPayload.TYPE, (payload, context) ->
            client.receive(payload.data(), bytes -> context.responseSender().sendPacket(new ClientViewPayload(bytes))));
        ClientPlayNetworking.registerGlobalReceiver(ClientViewPayload.TYPE, (payload, context) ->
            client.receive(payload.data(), bytes -> context.responseSender().sendPacket(new ClientViewPayload(bytes))));
        ClientTickEvents.END_CLIENT_TICK.register(client::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> client.connected());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> client.disconnected());
    }

    private static void send(byte[] bytes) {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        ClientPlayNetworking.send(new ClientViewPayload(bytes));
    }
}
