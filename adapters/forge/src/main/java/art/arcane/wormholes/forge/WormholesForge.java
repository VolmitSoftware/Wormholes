package art.arcane.wormholes.forge;

import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.clientview.ClientViewPayload;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraftforge.event.network.GatherLoginConfigurationTasksEvent;
import net.minecraftforge.network.NetworkProtocol;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraft.world.InteractionResult;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.function.BiConsumer;
import java.util.function.Predicate;

@Mod("wormholes")
public final class WormholesForge {
    private final WormholesModRuntime runtime = new WormholesModRuntime();
    private final BiConsumer<ClientViewPayload, CustomPayloadEvent.Context> clientReceiver = clientViewReceiver();

    public WormholesForge(FMLJavaModLoadingContext context) {
        runtime.seamlessEvents(new ForgeSeamlessEvents());
        RegisterCommandsEvent.BUS.addListener(event -> runtime.registerCommands(event.getDispatcher()));
        ServerStartedEvent.BUS.addListener(event -> runtime.start(event.getServer()));
        TickEvent.ServerTickEvent.Post.BUS.addListener(event -> runtime.tick());
        ServerStoppingEvent.BUS.addListener(event -> runtime.stop());
        PlayerInteractEvent.LeftClickBlock.BUS.addListener((Predicate<PlayerInteractEvent.LeftClickBlock>) this::attackBlock);
        PlayerInteractEvent.RightClickBlock.BUS.addListener((Predicate<PlayerInteractEvent.RightClickBlock>) this::useBlock);
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(this::playerDisconnected);
        PlayerInteractEvent.RightClickItem.BUS.addListener((Predicate<PlayerInteractEvent.RightClickItem>) this::useItem);
        BlockEvent.BreakEvent.BUS.addListener((Predicate<BlockEvent.BreakEvent>) this::beforeBreak);
        Channel<CustomPacketPayload> clientView = ChannelBuilder.named(ClientViewPayload.ID).optional().payloadChannel().any()
            .bidirectional().add(ClientViewPayload.TYPE, ClientViewPayload.CODEC, this::clientViewPayload)
            .build();
        runtime.clientViews().packets(payload -> NetworkProtocol.PLAY.buildPacket(PacketFlow.CLIENTBOUND, clientView, payload));
        GatherLoginConfigurationTasksEvent.BUS.addListener(event -> configureClientView(event, clientView));
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(event -> clientViewJoined(event, clientView));
        if (FMLEnvironment.dist == Dist.CLIENT) {
            WormholesForgeClient.initialize(clientView);
        }
    }

    private static BiConsumer<ClientViewPayload, CustomPayloadEvent.Context> clientViewReceiver() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            return WormholesForgeClient.receiver();
        }
        return (payload, payloadContext) -> payloadContext.setPacketHandled(true);
    }

    private void clientViewPayload(ClientViewPayload payload, CustomPayloadEvent.Context context) {
        if (context.isClientSide()) {
            clientReceiver.accept(payload, context);
            return;
        }
        context.setPacketHandled(true);
        ServerPlayer sender = context.getSender();
        if (sender == null) {
            runtime.clientViews().receive(context.getConnection(), payload.data());
            return;
        }
        runtime.clientViews().receivePlay(sender, payload.data());
    }

    private void configureClientView(GatherLoginConfigurationTasksEvent event, Channel<CustomPacketPayload> clientView) {
        Connection connection = event.getConnection();
        if (!runtime.running() || !(connection.getPacketListener() instanceof ServerConfigurationPacketListenerImpl listener)) {
            return;
        }
        ConfigurationTask task = runtime.clientViews().configurationTask(listener, () -> clientView.isRemotePresent(connection));
        if (task != null) {
            event.addTask(task);
        }
    }

    private void clientViewJoined(PlayerEvent.PlayerLoggedInEvent event, Channel<CustomPacketPayload> clientView) {
        if (event.getEntity() instanceof ServerPlayer player && runtime.running()) {
            runtime.clientViews().joined(player, clientView.isRemotePresent(player.connection.getConnection()));
        }
    }

    private boolean attackBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START
            || !(event.getEntity() instanceof ServerPlayer player) || !runtime.running()) {
            return false;
        }
        return runtime.attackBlock(player, event.getPos());
    }

    private boolean useBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !runtime.running()) {
            return false;
        }
        if (!runtime.useBlock(player, event.getHand(), event.getHitVec())) {
            return false;
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        return true;
    }

    private boolean useItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !runtime.running()
            || !runtime.useItem(player, event.getHand())) {
            return false;
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        return true;
    }

    private boolean beforeBreak(BlockEvent.BreakEvent event) {
        return event.getPlayer() instanceof ServerPlayer player && runtime.running() && runtime.beforeBreak(player, event.getPos());
    }

    private void playerDisconnected(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && runtime.running()) {
            runtime.playerDisconnected(player);
        }
    }
}
