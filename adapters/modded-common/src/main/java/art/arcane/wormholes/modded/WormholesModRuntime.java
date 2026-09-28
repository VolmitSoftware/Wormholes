package art.arcane.wormholes.modded;

import art.arcane.wormholes.chunk.ChunkLeaseRegistry;
import art.arcane.wormholes.portal.rtp.MinecraftRtpRuntime;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.chunk.presend.ChunkPreSendService;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.CancellationException;

public final class WormholesModRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private final PriorityQueue<PendingTask> pending = new PriorityQueue<>(
        Comparator.comparingLong(PendingTask::dueTick).thenComparingLong(PendingTask::sequence));
    private final MinecraftNexus nexus = new MinecraftNexus(this);
    private final MinecraftPortalEffects effects = new MinecraftPortalEffects(this);
    private final MinecraftOperations operations = new MinecraftOperations(this);
    private final MinecraftRules rules = new MinecraftRules(this);
    private final MinecraftPortalConstruction construction = new MinecraftPortalConstruction(this);
    private final MinecraftPortalTools tools = new MinecraftPortalTools(this);
    private final MinecraftAtlasService atlas = new MinecraftAtlasService(this);
    private final MinecraftDoorService doors = new MinecraftDoorService(this);
    private final MinecraftNetworkService network = new MinecraftNetworkService(this);
    private final MinecraftProjectionService projections = new MinecraftProjectionService(this);
    private final MinecraftChatInput chatInput = new MinecraftChatInput(this);
    private final MinecraftPortalMenus menus = new MinecraftPortalMenus(this);
    private final MinecraftLocalization localization = new MinecraftLocalization(this);
    private final MinecraftRtpRuntime rtp = new MinecraftRtpRuntime(this);
    private final MinecraftAccessService access = new MinecraftAccessService(this);
    private final MinecraftTravelCosts costs = new MinecraftTravelCosts(this);
    private final MinecraftNetworkTools networkTools = new MinecraftNetworkTools(this);
    private MinecraftServer server;
    private ChunkLeaseRegistry<ServerLevel> leases;
    private ChunkPreSendService<ServerLevel, ServerPlayer> preSend;
    private WormholesModConfiguration configuration;
    private MinecraftPortalRegistry portals;
    private final MinecraftWormholesApi api = new MinecraftWormholesApi(this);
    private long tick;
    private long sequence;
    private boolean running;

    public synchronized void start(MinecraftServer server) {
        if (running) {
            throw new IllegalStateException("Wormholes is already running");
        }
        this.server = Objects.requireNonNull(server, "server");
        requireServerThread();
        try {
            configuration = new WormholesModConfiguration(server.getServerDirectory().resolve("config/wormholes"),
                new WormholesModConfiguration.Execution(server::execute, this::requireServerThread));
            FidelitySettings.refresh(configuration.settings());
            tick = 0;
            sequence = 0;
            leases = new ChunkLeaseRegistry<>(new MinecraftChunkLeasePlatform(this),
                new ChunkLeaseRegistry.Options(1000L, 50L, 3));
            preSend = new ChunkPreSendService<>(new MinecraftChunkPreSendPlatform(this), configuration::preSendOptions);
            portals = new MinecraftPortalRegistry(this, new MinecraftPortalRegistry.Options(
                server.getServerDirectory().resolve("config/wormholes/portals"), new PortalAccess()));
            try {
                portals.load();
                doors.load(new MinecraftDoorService.Options(
                    server.getServerDirectory().resolve("config/wormholes"), new PortalAccess()));
                localization.start(server.getServerDirectory().resolve("config/wormholes"), configuration.settings());
            } catch (IOException error) {
                throw new UncheckedIOException("Could not load Wormholes portal data", error);
            }
            nexus.start();
            construction.load();
            rules.start();
            costs.start();
            rtp.start();
            chatInput.start();
            menus.start();
            atlas.start();
            network.start();
            networkTools.start();
            projections.start();
            running = true;
            api.start();
        } catch (RuntimeException error) {
            try {
                releaseServices();
            } catch (RuntimeException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            clearRuntime();
            throw error;
        }
        LOGGER.info("Wormholes runtime started");
    }

    public void tick() {
        synchronized (this) {
            if (!running) {
                return;
            }
            requireServerThread();
            tick++;
        }
        nexus.tick();
        rules.tick();
        construction.tick();
        costs.tick();
        rtp.tick();
        effects.tick();
        portals.tick();
        doors.tick();
        tools.tick();
        atlas.tick();
        menus.tick();
        network.tick();
        projections.tick();
        operations.tick();
        api.tick();
        while (true) {
            PendingTask task;
            synchronized (this) {
                if (!running || pending.isEmpty() || pending.peek().dueTick() > tick) {
                    return;
                }
                task = pending.remove();
            }
            try {
                task.command().run();
            } catch (RuntimeException error) {
                LOGGER.error("Wormholes scheduled operation failed", error);
            }
        }
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        requireServerThread();
        try {
            releaseServices();
        } catch (RuntimeException error) {
            LOGGER.error("Wormholes shutdown failed", error);
        } finally {
            clearRuntime();
        }
        LOGGER.info("Wormholes runtime stopped");
    }

    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("wormholes")
            .then(Commands.literal("status").executes(context -> {
                context.getSource().sendSuccess(() -> Component.literal(
                    "Wormholes runtime: " + (running() ? "running" : "stopped")), false);
                return running() ? 1 : 0;
            }))
            .then(Commands.literal("reload")
                .requires(source -> access.permission(source, "wormholes.admin"))
                .executes(context -> reloadCommand(context.getSource()))));
        operations.registerCommands(dispatcher);
        tools.registerCommands(dispatcher);
        atlas.registerCommands(dispatcher);
        doors.registerCommands(dispatcher);
        localization.registerCommands(dispatcher);
        networkTools.registerCommands(dispatcher);
        access.registerCommands(dispatcher);
        new MinecraftRulesCommands(this).register(dispatcher);
        new MinecraftNexusCommands(this).register(dispatcher);
        dispatcher.register(Commands.literal("wh").redirect(dispatcher.getRoot().getChild("wormholes")));
        dispatcher.register(Commands.literal("wormhole").redirect(dispatcher.getRoot().getChild("wormholes")));
    }

    public boolean attackBlock(ServerPlayer player, BlockPos position) {
        requireServerThread();
        boolean handled = construction.attackBlock(player, position)
            || MinecraftPortalInteractions.wand(this, player, InteractionHand.MAIN_HAND) || tools.attackBlock(player, position);
        if (handled) {
            tools.suppressSwing(player);
        }
        return handled;
    }

    public boolean useBlock(ServerPlayer player, InteractionHand hand, BlockHitResult hit) {
        requireServerThread();
        boolean handled = construction.useBlock(player, hand, hit) || MinecraftPortalInteractions.wand(this, player, hand)
            || MinecraftPortalInteractions.frame(this, player, hand, hit.getBlockPos()) || tools.useBlock(player, hand, hit)
            || doors.useBlock(player, hand, hit) || nexus.useBlock(player, hand, hit) || MinecraftPortalSurfaces.interact(this, player, hand);
        if (handled) {
            tools.suppressSwing(player);
        }
        return handled;
    }

    public boolean useItem(ServerPlayer player, InteractionHand hand) {
        requireServerThread();
        boolean handled = MinecraftPortalInteractions.wand(this, player, hand) || doors.useItem(player, hand)
            || MinecraftPortalSurfaces.interact(this, player, hand);
        if (handled) {
            tools.suppressSwing(player);
        }
        return handled;
    }

    public boolean attackAir(ServerPlayer player, InteractionHand hand) {
        requireServerThread();
        return hand == InteractionHand.MAIN_HAND && tools.attackAir(player);
    }

    public boolean beforeBreak(ServerPlayer player, BlockPos position) {
        requireServerThread();
        return construction.beforeBreak(player, position) || doors.beforeBreak(player, position);
    }

    public MinecraftWormholesApi api() {
        return api;
    }

    public MinecraftNexus nexus() {
        requireServerThread();
        return nexus;
    }

    public MinecraftRules rules() {
        requireServerThread();
        return rules;
    }

    public MinecraftPortalConstruction construction() {
        requireServerThread();
        return construction;
    }

    public MinecraftDoorService doors() {
        requireServerThread();
        return doors;
    }

    public void playerDisconnected(ServerPlayer player) {
        MinecraftClientProfiles.forget(player);
        requireServerThread();
        rules.disconnected(player);
        rtp.disconnected(player);
        costs.disconnected(player);
        network.handoffs().disconnected(player);
        chatInput.disconnected(player);
        menus.playerDisconnected(player);
        projections.playerDisconnected(player);
        tools.playerDisconnected(player);
        doors.playerDisconnected(player);
        atlas.playerDisconnected(player);
        portals.playerDisconnected(player);
    }

    public MinecraftRtpRuntime rtp() {
        requireServerThread();
        return rtp;
    }

    public MinecraftAccessService access() {
        requireServerThread();
        return access;
    }

    MinecraftOperations operations() {
        return operations;
    }

    public MinecraftPortalEffects effects() {
        return effects;
    }

    public MinecraftTravelCosts costs() {
        requireServerThread();
        return costs;
    }

    public MinecraftNetworkService network() {
        requireServerThread();
        return network;
    }

    public MinecraftNetworkTools networkTools() {
        requireServerThread();
        return networkTools;
    }

    public MinecraftLocalization localization() {
        requireServerThread();
        return localization;
    }

    public MinecraftProjectionService projections() {
        requireServerThread();
        return projections;
    }

    public MinecraftChatInput chatInput() {
        requireServerThread();
        return chatInput;
    }

    public MinecraftPortalMenus menus() {
        requireServerThread();
        return menus;
    }

    public MinecraftAtlasService atlas() {
        requireServerThread();
        return atlas;
    }

    public MinecraftPortalRegistry portals() {
        requireServerThread();
        return Objects.requireNonNull(portals, "Wormholes is not running");
    }

    public synchronized boolean schedule(Runnable command, long delayTicks) {
        Objects.requireNonNull(command, "command");
        if (!running) {
            return false;
        }
        pending.add(new PendingTask(Math.addExact(tick, Math.max(1L, delayTicks)), sequence++, command));
        return true;
    }

    public synchronized boolean running() {
        return running;
    }

    public synchronized MinecraftServer server() {
        return Objects.requireNonNull(server, "Wormholes is not running");
    }

    public ChunkLeaseRegistry<ServerLevel> leases() {
        requireServerThread();
        return Objects.requireNonNull(leases, "Wormholes is not running");
    }

    public ChunkPreSendService<ServerLevel, ServerPlayer> preSend() {
        requireServerThread();
        return Objects.requireNonNull(preSend, "Wormholes is not running");
    }

    public WormholesModConfiguration configuration() {
        requireServerThread();
        return Objects.requireNonNull(configuration, "Wormholes is not running");
    }

    public void requireServerThread() {
        if (!server().isSameThread()) {
            throw new IllegalStateException("Wormholes world operations require the server thread");
        }
    }

    private void releaseServices() {
        RuntimeException failure = null;
        for (Runnable cleanup : new Runnable[] {
            api::close,
            operations::close,
            effects::close,
            nexus::close,
            rules::close,
            construction::close,
            MinecraftWindow::closeAll,
            menus::close,
            chatInput::close,
            projections::close,
            networkTools::close,
            rtp::close,
            costs::close,
            network::close,
            doors::close,
            atlas::close,
            tools::close,
            localization::close,
            access::close,
            () -> { if (portals != null) { portals.close(); } },
            () -> { if (configuration != null) { configuration.close(); } },
            () -> { if (leases != null) { leases.shutdown(); } }
        }) {
            try {
                cleanup.run();
            } catch (RuntimeException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void clearRuntime() {
        running = false;
        pending.clear();
        portals = null;
        configuration = null;
        preSend = null;
        leases = null;
        server = null;
    }

    private int reloadCommand(CommandSourceStack source) {
        configuration().reload().whenComplete((settings, error) -> {
            if (error instanceof CancellationException) {
                return;
            }
            if (error != null) {
                LOGGER.error("Wormholes configuration reload failed; retaining current settings", error);
                source.sendFailure(Component.literal("Wormholes configuration reload failed; current settings retained."));
                return;
            }
            FidelitySettings.refresh(settings);
            MinecraftClientProfiles.clear();
            try {
                network.reload();
            } catch (RuntimeException reloadError) {
                LOGGER.error("Wormholes networking reload failed", reloadError);
                source.sendFailure(Component.literal("Configuration loaded, but networking reload failed."));
                return;
            }
            localization.reload(settings).whenComplete((ignored, languageError) -> {
                if (languageError != null) {
                    LOGGER.error("Wormholes language reload failed", languageError);
                    source.sendFailure(Component.literal("Configuration loaded, but language reload failed."));
                } else {
                    source.sendSuccess(() -> Component.literal("Wormholes configuration reloaded."), true);
                }
            });
        });
        return 1;
    }

    private final class PortalAccess implements MinecraftPortalRegistry.Access, MinecraftDoorService.Access {
        @Override
        public boolean administrator(ServerPlayer player) {
            return access.administrator(player);
        }

        @Override
        public boolean permission(ServerPlayer player, String node) {
            return access.permission(player, node);
        }

        @Override
        public boolean nameAlias() {
            return configuration.settings().getAccess().legacyNameNodeEnabled;
        }
    }

    private record PendingTask(long dueTick, long sequence, Runnable command) {
    }
}
