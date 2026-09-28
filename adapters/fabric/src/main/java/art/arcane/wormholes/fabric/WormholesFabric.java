package art.arcane.wormholes.fabric;

import art.arcane.wormholes.modded.MinecraftDoorRecipes;
import art.arcane.wormholes.modded.WormholesModRuntime;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;

public final class WormholesFabric implements ModInitializer {
    private final WormholesModRuntime runtime = new WormholesModRuntime();

    @Override
    public void onInitialize() {
        MinecraftDoorRecipes.serializers().forEach((id, serializer) -> Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, id, serializer));
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

    private void playerDisconnected(ServerPlayer player) {
        if (runtime.running()) {
            runtime.playerDisconnected(player);
        }
    }
}
