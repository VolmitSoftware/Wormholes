package art.arcane.wormholes.neoforge;

import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.clientview.ClientViewPayload;
import net.minecraft.client.Minecraft;
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
    }

    private void registerPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(ClientViewPayload.TYPE, (payload, context) ->
            client.receive(payload.data(), bytes -> context.reply(new ClientViewPayload(bytes))));
    }

    private static void send(byte[] bytes) {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        ClientPacketDistributor.sendToServer(new ClientViewPayload(bytes));
    }
}
