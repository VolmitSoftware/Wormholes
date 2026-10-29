package art.arcane.wormholes.proxy.velocity;

import art.arcane.wormholes.proxy.core.ProxyConsole;
import art.arcane.wormholes.proxy.core.ProxyHandoffBroker;
import art.arcane.wormholes.proxy.core.ProxyRegistry;
import art.arcane.wormholes.proxy.protocol.EnrollmentCodec;
import art.arcane.wormholes.proxy.protocol.ProxySecret;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Velocity entry point: enrolls backends, answers rosters, brokers handoffs, serves /whproxy. */
@Plugin(id = "wormholes", name = "Wormholes")
public final class WormholesVelocityPlugin {
    private static final ChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(EnrollmentCodec.CHANNEL);
    private static final long RESERVATION_TTL_MILLIS = 30_000L;
    private static final long QUIET_MILLIS = 90_000L;
    private static final long SWEEP_SECONDS = 30L;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final ProxyRegistry registry = new ProxyRegistry();
    private final ProxyHandoffBroker broker = new ProxyHandoffBroker();
    private byte[] secret;

    @Inject
    public WormholesVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            secret = ProxySecret.of(ProxySecret.loadOrCreate(dataDirectory));
        } catch (IOException unwritable) {
            logger.error("Wormholes proxy could not read " + dataDirectory.resolve(ProxySecret.FILE) + ": " + unwritable.getMessage());
            return;
        }
        logger.info("Wormholes proxy secret in " + dataDirectory.resolve(ProxySecret.FILE) + "; copy it into [network.proxy] secret on every backend");
        proxy.getChannelRegistrar().register(CHANNEL);
        CommandMeta meta = proxy.getCommandManager().metaBuilder("whproxy").plugin(this).build();
        proxy.getCommandManager().register(meta, (SimpleCommand) invocation -> {
            if (!invocation.source().hasPermission(ProxyConsole.PERMISSION)) {
                invocation.source().sendMessage(Component.text("You need " + ProxyConsole.PERMISSION + " to use /whproxy."));
                return;
            }
            String subcommand = invocation.arguments().length == 0 ? "" : invocation.arguments()[0];
            for (String line : ProxyConsole.render(subcommand, registry, broker, System.currentTimeMillis())) {
                invocation.source().sendMessage(Component.text(line));
            }
        });
        proxy.getScheduler().buildTask(this, this::sweep).delay(SWEEP_SECONDS, TimeUnit.SECONDS).repeat(SWEEP_SECONDS, TimeUnit.SECONDS).schedule();
        logger.info("Wormholes proxy ready on channel " + EnrollmentCodec.CHANNEL);
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (secret == null || !(event.getSource() instanceof ServerConnection connection)) {
            return;
        }
        EnrollmentCodec.Frame frame;
        try {
            frame = EnrollmentCodec.decode(event.getData(), secret);
        } catch (IOException e) {
            logger.warn("Dropped proxy frame from " + connection.getServerInfo().getName() + ": " + e.getMessage());
            return;
        }
        long now = System.currentTimeMillis();
        broker.expire(now, RESERVATION_TTL_MILLIS);
        if (frame instanceof EnrollmentCodec.Enroll enroll) {
            if (!registry.enroll(connection.getServerInfo().getName(), enroll.serverName(), enroll.serverCode(), enroll.capabilities(), enroll.portals(), now)) {
                logger.warn("Wormholes proxy refused enroll: " + connection.getServerInfo().getName() + " claims "
                    + enroll.serverName() + ", already held by " + registry.proxyServerFor(enroll.serverName()));
                return;
            }
            connection.sendPluginMessage(CHANNEL,
                EnrollmentCodec.encode(new EnrollmentCodec.Roster(enroll.nonce(), registry.rosterExcluding(enroll.serverName())), secret));
            return;
        }
        if (frame instanceof EnrollmentCodec.Handoff handoff) {
            handoff(connection, handoff);
        }
    }

    private void sweep() {
        registry.sweep(System.currentTimeMillis(), QUIET_MILLIS, this::reachable, (serverName, proxyServer) ->
            logger.info("Wormholes proxy dropped unreachable backend " + serverName + " on " + proxyServer));
    }

    private CompletableFuture<Boolean> reachable(String proxyServer) {
        return proxy.getServer(proxyServer)
            .map(server -> server.ping().handle((ping, error) -> error == null))
            .orElseGet(() -> CompletableFuture.completedFuture(false));
    }

    private void handoff(ServerConnection connection, EnrollmentCodec.Handoff handoff) {
        Player player = connection.getPlayer();
        String sourceServer = connection.getServerInfo().getName();
        String proxyName = registry.proxyServerFor(handoff.targetServer());
        Optional<RegisteredServer> target = proxyName == null ? Optional.empty() : proxy.getServer(proxyName);
        if (target.isEmpty()) {
            receipt(sourceServer, handoff.transferId(), false, "target " + handoff.targetServer() + " is not enrolled");
            return;
        }
        broker.reserve(handoff.transferId(), player.getUniqueId(), sourceServer, handoff.targetServer(), System.currentTimeMillis());
        player.createConnectionRequest(target.get()).connect().whenComplete((result, error) -> {
            UUID transferId = handoff.transferId();
            if (broker.complete(transferId) == null) {
                return;
            }
            boolean ok = error == null && result != null && result.isSuccessful();
            String detail = error != null ? error.getMessage() : result == null ? "no result" : result.getStatus().name();
            receipt(sourceServer, transferId, ok, ok ? "" : detail);
        });
    }

    private void receipt(String sourceServer, UUID transferId, boolean ok, String detail) {
        proxy.getServer(sourceServer).ifPresent(server ->
            server.sendPluginMessage(CHANNEL, EnrollmentCodec.encode(new EnrollmentCodec.Receipt(transferId, ok, detail == null ? "" : detail), secret)));
    }
}
