package art.arcane.wormholes.fabric;

import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.clientview.ClientViewPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ClientboundPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

public final class WormholesFabric implements ModInitializer {
    private final WormholesModRuntime runtime = new WormholesModRuntime();

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundConfiguration().register(ClientViewPayload.TYPE, ClientViewPayload.CODEC);
        PayloadTypeRegistry.serverboundConfiguration().register(ClientViewPayload.TYPE, ClientViewPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ClientViewPayload.TYPE, ClientViewPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ClientViewPayload.TYPE, ClientViewPayload.CODEC);
        ServerConfigurationNetworking.registerGlobalReceiver(ClientViewPayload.TYPE, (payload, context) ->
            runtime.clientViews().receive(context.packetListener(), payload.data()));
        ServerPlayNetworking.registerGlobalReceiver(ClientViewPayload.TYPE, (payload, context) ->
            runtime.clientViews().receive(context.player().connection, payload.data()));
        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> configureClientView(handler));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> clientViewJoined(handler));
        ClientboundPlayChannelEvents.REGISTER.register((handler, sender, server, channels) -> clientViewChannels(handler, channels));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> runtime.registerCommands(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(runtime::start);
        ServerTickEvents.END_SERVER_TICK.register(server -> runtime.tick());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> runtime.stop());
        AttackBlockCallback.EVENT.register((player, level, hand, position, direction) -> attackBlock(player, hand, position));
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> useBlock(player, hand, hit));
        UseItemCallback.EVENT.register((player, level, hand) -> useItem(player, hand));
        PlayerBlockBreakEvents.BEFORE.register((level, player, position, state, blockEntity) ->
            !(player instanceof ServerPlayer serverPlayer) || !runtime.running() || !runtime.beforeBreak(serverPlayer, position));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> playerDisconnected(handler.player));
    }

    private InteractionResult attackBlock(Player player, InteractionHand hand, BlockPos position) {
        if (hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer serverPlayer) || !runtime.running()) {
            return InteractionResult.PASS;
        }
        return runtime.attackBlock(serverPlayer, position) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    private InteractionResult useBlock(Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(player instanceof ServerPlayer serverPlayer) || !runtime.running()) {
            return InteractionResult.PASS;
        }
        return runtime.useBlock(serverPlayer, hand, hit) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    private InteractionResult useItem(Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer serverPlayer) || !runtime.running()) {
            return InteractionResult.PASS;
        }
        return runtime.useItem(serverPlayer, hand) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    private void configureClientView(ServerConfigurationPacketListenerImpl handler) {
        if (!runtime.running()) {
            return;
        }
        ConfigurationTask task = runtime.clientViews().configurationTask(handler,
            () -> ServerConfigurationNetworking.canSend(handler, ClientViewPayload.TYPE));
        if (task != null) {
            handler.addTask(task);
        }
    }

    private void clientViewJoined(ServerGamePacketListenerImpl handler) {
        if (runtime.running()) {
            runtime.clientViews().joined(handler.player, ServerPlayNetworking.canSend(handler, ClientViewPayload.TYPE));
        }
    }

    private void clientViewChannels(ServerGamePacketListenerImpl handler, List<Identifier> channels) {
        if (runtime.running() && channels.contains(ClientViewPayload.ID)) {
            runtime.clientViews().channelRegistered(handler.player);
        }
    }

    private void playerDisconnected(ServerPlayer player) {
        if (runtime.running()) {
            runtime.playerDisconnected(player);
        }
    }
}
