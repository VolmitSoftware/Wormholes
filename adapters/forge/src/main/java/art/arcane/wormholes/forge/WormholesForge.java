package art.arcane.wormholes.forge;

import art.arcane.wormholes.modded.WormholesModRuntime;
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

import java.util.function.Predicate;

@Mod("wormholes")
public final class WormholesForge {
    private final WormholesModRuntime runtime = new WormholesModRuntime();

    public WormholesForge(FMLJavaModLoadingContext context) {
        RegisterCommandsEvent.BUS.addListener(event -> runtime.registerCommands(event.getDispatcher()));
        ServerStartedEvent.BUS.addListener(event -> runtime.start(event.getServer()));
        TickEvent.ServerTickEvent.Post.BUS.addListener(event -> runtime.tick());
        ServerStoppingEvent.BUS.addListener(event -> runtime.stop());
        PlayerInteractEvent.LeftClickBlock.BUS.addListener((Predicate<PlayerInteractEvent.LeftClickBlock>) this::attackBlock);
        PlayerInteractEvent.RightClickBlock.BUS.addListener((Predicate<PlayerInteractEvent.RightClickBlock>) this::useBlock);
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(this::playerDisconnected);
        PlayerInteractEvent.RightClickItem.BUS.addListener((Predicate<PlayerInteractEvent.RightClickItem>) this::useItem);
        BlockEvent.BreakEvent.BUS.addListener((Predicate<BlockEvent.BreakEvent>) this::beforeBreak);
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
