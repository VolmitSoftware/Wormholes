package art.arcane.wormholes.fabric;

import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.clientview.ClientViewPayload;
import art.arcane.wormholes.modded.menu.MenuPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.S2CPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

public final class WormholesFabric implements ModInitializer {
    private final WormholesModRuntime runtime = new WormholesModRuntime();

    @Override
    public void onInitialize() {
        runtime.seamlessEvents(new FabricSeamlessEvents());
        runtime.clientViews().packets(payload -> {
            FriendlyByteBuf buffer = PacketByteBufs.create();
            payload.write(buffer);
            return ServerPlayNetworking.createS2CPacket(ClientViewPayload.ID, buffer);
        });
        runtime.menuTransport().packets(payload -> {
            FriendlyByteBuf buffer = PacketByteBufs.create();
            payload.write(buffer);
            return ServerPlayNetworking.createS2CPacket(MenuPayload.ID, buffer);
        });
        ServerPlayNetworking.registerGlobalReceiver(ClientViewPayload.ID, (server, player, handler, buffer, sender) ->
            clientViewReceived(server, player, ClientViewPayload.read(buffer).data(), System.nanoTime()));
        ServerPlayNetworking.registerGlobalReceiver(MenuPayload.ID, (server, player, handler, buffer, sender) ->
            menuReceived(player, MenuPayload.readServerbound(buffer).data()));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> runtime.playerJoined(handler.player));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> clientViewJoined(handler));
        S2CPlayChannelEvents.REGISTER.register((handler, sender, server, channels) -> clientViewChannels(handler, channels));
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

    private void clientViewReceived(MinecraftServer server, ServerPlayer player, byte[] data, long receivedNanos) {
        server.execute(() -> {
            if (runtime.running() && !player.hasDisconnected()) {
                runtime.clientViews().receivePlayAt(player, data, receivedNanos);
            }
        });
    }

    private void menuReceived(ServerPlayer player, byte[] data) {
        if (runtime.running()) {
            runtime.menuTransport().receive(player, data);
        }
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

    private InteractionResultHolder<ItemStack> useItem(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(player instanceof ServerPlayer serverPlayer) || !runtime.running()) {
            return InteractionResultHolder.pass(stack);
        }
        return runtime.useItem(serverPlayer, hand) ? InteractionResultHolder.success(stack) : InteractionResultHolder.pass(stack);
    }

    private void clientViewJoined(ServerGamePacketListenerImpl handler) {
        if (runtime.running()) {
            runtime.clientViews().joined(handler.player, ServerPlayNetworking.canSend(handler, ClientViewPayload.ID));
        }
    }

    private void clientViewChannels(ServerGamePacketListenerImpl handler, List<ResourceLocation> channels) {
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
