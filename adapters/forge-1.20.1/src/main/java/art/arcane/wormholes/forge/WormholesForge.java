package art.arcane.wormholes.forge;

import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.clientview.ClientViewPayload;
import art.arcane.wormholes.modded.menu.MenuPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;

import java.util.random.RandomGenerator;

@Mod("wormholes")
public final class WormholesForge {
    private static final String PROTOCOL = "1";

    private final WormholesModRuntime runtime = new WormholesModRuntime();
    // Raw event channels keep the wire format identical to Fabric and the Bukkit plugin: a SimpleChannel would put a
    // discriminator byte in front of every frame, which no other Wormholes peer reads or writes.
    private final EventNetworkChannel clientView = channel(ClientViewPayload.ID);
    private final EventNetworkChannel menus = channel(MenuPayload.ID);

    public WormholesForge() {
        loadRandomGenerators();
        runtime.seamlessEvents(new ForgeSeamlessEvents());
        clientView.addListener(this::clientViewPayload);
        menus.addListener(this::menuPayload);
        IEventBus bus = MinecraftForge.EVENT_BUS;
        bus.addListener((RegisterCommandsEvent event) -> runtime.registerCommands(event.getDispatcher()));
        bus.addListener((ServerStartedEvent event) -> runtime.start(event.getServer()));
        bus.addListener(this::serverTick);
        bus.addListener((ServerStoppingEvent event) -> runtime.stop());
        bus.addListener(this::attackBlock);
        bus.addListener(this::useBlock);
        bus.addListener(this::useItem);
        bus.addListener(this::beforeBreak);
        bus.addListener(this::playerJoined);
        bus.addListener(this::playerDisconnected);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            WormholesForgeClient.initialize(menus);
        }
    }

    private static EventNetworkChannel channel(ResourceLocation id) {
        return NetworkRegistry.newEventChannel(id, () -> PROTOCOL, NetworkRegistry.acceptMissingOr(PROTOCOL),
            NetworkRegistry.acceptMissingOr(PROTOCOL));
    }

    private static boolean playPayload(NetworkEvent event) {
        return event.getPayload() != null
            && (event instanceof NetworkEvent.ServerCustomPayloadEvent || event instanceof NetworkEvent.ClientCustomPayloadEvent)
            && !(event instanceof NetworkEvent.ServerCustomPayloadLoginEvent || event instanceof NetworkEvent.ClientCustomPayloadLoginEvent);
    }

    private void clientViewPayload(NetworkEvent event) {
        if (!playPayload(event)) {
            return;
        }
        NetworkEvent.Context context = event.getSource().get();
        context.setPacketHandled(true);
        long receivedNanos = System.nanoTime();
        ClientViewPayload payload = ClientViewPayload.read(event.getPayload());
        if (context.getDirection().getReceptionSide().isClient()) {
            WormholesForgeClient.receive(payload, context);
            return;
        }
        ServerPlayer sender = context.getSender();
        if (sender == null) {
            return;
        }
        context.enqueueWork(() -> {
            if (runtime.running() && !sender.hasDisconnected()) {
                runtime.clientViews().receivePlayAt(sender, payload.data(), receivedNanos);
            }
        });
    }

    private void menuPayload(NetworkEvent event) {
        if (!playPayload(event)) {
            return;
        }
        NetworkEvent.Context context = event.getSource().get();
        context.setPacketHandled(true);
        if (context.getDirection().getReceptionSide().isClient()) {
            MenuPayload payload = MenuPayload.read(event.getPayload());
            context.enqueueWork(() -> WormholesForgeClient.receiveMenu(payload));
            return;
        }
        MenuPayload payload = MenuPayload.readServerbound(event.getPayload());
        ServerPlayer sender = context.getSender();
        if (sender != null && runtime.running()) {
            runtime.menuTransport().receive(sender, payload.data());
        }
    }

    private void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            runtime.tick();
        }
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
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    private void useItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !runtime.running()) {
            return;
        }
        if (runtime.useItem(player, event.getHand())) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    private void beforeBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && runtime.running() && runtime.beforeBreak(player, event.getPos())) {
            event.setCanceled(true);
        }
    }

    private void playerJoined(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        runtime.playerJoined(player);
        if (runtime.running()) {
            runtime.clientViews().joined(player, clientView.isRemotePresent(player.connection.connection));
        }
    }

    private void playerDisconnected(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && runtime.running()) {
            runtime.playerDisconnected(player);
        }
    }

    /**
     * RandomGenerator.getDefault() finds its algorithms through the context class loader once and caches the result.
     * Forge's game class loader cannot see the jdk.random providers, so load them first through the system loader.
     */
    private static void loadRandomGenerators() {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(ClassLoader.getSystemClassLoader());
        try {
            RandomGenerator.getDefault();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
