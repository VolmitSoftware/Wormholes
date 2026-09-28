package art.arcane.wormholes.neoforge;

import art.arcane.wormholes.modded.MinecraftDoorRecipes;
import art.arcane.wormholes.modded.MinecraftProxyPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import art.arcane.wormholes.modded.WormholesModRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.minecraft.world.InteractionResult;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod("wormholes")
public final class WormholesNeoForge {
    private final WormholesModRuntime runtime = new WormholesModRuntime();

    public WormholesNeoForge(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").optional()
            .playToClient(MinecraftProxyPayload.TYPE, MinecraftProxyPayload.CODEC, (payload, context) -> { }));
        bus.addListener((RegisterEvent event) -> MinecraftDoorRecipes.serializers().forEach((id, serializer) ->
            event.register(Registries.RECIPE_SERIALIZER, id, () -> serializer)));
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::start);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::stop);
        NeoForge.EVENT_BUS.addListener(this::attackBlock);
        NeoForge.EVENT_BUS.addListener(this::useBlock);
        NeoForge.EVENT_BUS.addListener(this::playerDisconnected);
        NeoForge.EVENT_BUS.addListener(this::useItem);
        NeoForge.EVENT_BUS.addListener(this::beforeBreak);
    }

    private void attackBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START
            || !(event.getEntity() instanceof ServerPlayer player) || !runtime.running()) {
            return;
        }
        if (runtime.attackBlock(player, event.getPos())) {
            event.setCanceled(true);
        }
    }

    private void useBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !runtime.running()) {
            return;
        }
        if (runtime.useBlock(player, event.getHand(), event.getHitVec())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    private void useItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer player && runtime.running() && runtime.useItem(player, event.getHand())) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    private void beforeBreak(BreakBlockEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && runtime.running() && runtime.beforeBreak(player, event.getPos())) {
            event.setNotifyClient(true);
            event.setCanceled(true);
        }
    }

    private void playerDisconnected(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && runtime.running()) {
            runtime.playerDisconnected(player);
        }
    }

    private void registerCommands(RegisterCommandsEvent event) {
        runtime.registerCommands(event.getDispatcher());
    }

    private void start(ServerStartedEvent event) {
        runtime.start(event.getServer());
    }

    private void tick(ServerTickEvent.Post event) {
        runtime.tick();
    }

    private void stop(ServerStoppingEvent event) {
        runtime.stop();
    }
}
