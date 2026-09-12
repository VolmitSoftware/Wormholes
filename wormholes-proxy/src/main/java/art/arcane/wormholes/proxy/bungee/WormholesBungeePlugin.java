package art.arcane.wormholes.proxy.bungee;

import art.arcane.wormholes.proxy.core.ProxyConsole;
import art.arcane.wormholes.proxy.core.ProxyHandoffBroker;
import art.arcane.wormholes.proxy.core.ProxyRegistry;
import art.arcane.wormholes.proxy.protocol.EnrollmentCodec;
import art.arcane.wormholes.proxy.protocol.ProxySecret;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** BungeeCord entry point: same enrollment, roster and handoff broker as the Velocity plugin. */
public final class WormholesBungeePlugin extends Plugin implements Listener {
    private static final long RESERVATION_TTL_MILLIS = 30_000L;
    private static final long ENROLLMENT_TTL_MILLIS = 300_000L;

    private final ProxyRegistry registry = new ProxyRegistry();
    private final ProxyHandoffBroker broker = new ProxyHandoffBroker();
    private byte[] secret;

    @Override
    public void onEnable() {
        try {
            secret = ProxySecret.of(ProxySecret.loadOrCreate(getDataFolder().toPath()));
        } catch (IOException unwritable) {
            getLogger().severe("WormholesProxy could not read " + new File(getDataFolder(), ProxySecret.FILE) + ": " + unwritable.getMessage());
            return;
        }
        getLogger().info("WormholesProxy secret in " + new File(getDataFolder(), ProxySecret.FILE) + "; copy it into [network.proxy] secret on every backend");
        getProxy().registerChannel(EnrollmentCodec.CHANNEL);
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerCommand(this, new Command("whproxy", ProxyConsole.PERMISSION) {
            @Override
            public void execute(CommandSender sender, String[] args) {
                String subcommand = args.length == 0 ? "" : args[0];
                for (String line : ProxyConsole.render(subcommand, registry, broker, System.currentTimeMillis())) {
                    sender.sendMessage(new TextComponent(line));
                }
            }
        });
        getLogger().info("WormholesProxy ready on channel " + EnrollmentCodec.CHANNEL);
    }

    @Override
    public void onDisable() {
        getProxy().unregisterChannel(EnrollmentCodec.CHANNEL);
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (!EnrollmentCodec.CHANNEL.equals(event.getTag())) {
            return;
        }
        event.setCancelled(true);
        if (secret == null || !(event.getSender() instanceof Server server) || !(event.getReceiver() instanceof ProxiedPlayer player)) {
            return;
        }
        EnrollmentCodec.Frame frame;
        try {
            frame = EnrollmentCodec.decode(event.getData(), secret);
        } catch (IOException e) {
            getLogger().warning("Dropped proxy frame from " + server.getInfo().getName() + ": " + e.getMessage());
            return;
        }
        long now = System.currentTimeMillis();
        broker.expire(now, RESERVATION_TTL_MILLIS);
        registry.expire(now, ENROLLMENT_TTL_MILLIS, (serverName, proxyServer) ->
            getLogger().info("WormholesProxy dropped stale backend " + serverName + " on " + proxyServer));
        if (frame instanceof EnrollmentCodec.Enroll enroll) {
            if (!registry.enroll(server.getInfo().getName(), enroll.serverName(), enroll.serverCode(), enroll.capabilities(), enroll.portals(), now)) {
                getLogger().warning("WormholesProxy refused enroll: " + server.getInfo().getName() + " claims "
                    + enroll.serverName() + ", already held by " + registry.proxyServerFor(enroll.serverName()));
                return;
            }
            server.sendData(EnrollmentCodec.CHANNEL,
                EnrollmentCodec.encode(new EnrollmentCodec.Roster(enroll.nonce(), registry.rosterExcluding(enroll.serverName())), secret));
            return;
        }
        if (frame instanceof EnrollmentCodec.Handoff handoff) {
            handoff(server, player, handoff);
        }
    }

    private void handoff(Server source, ProxiedPlayer player, EnrollmentCodec.Handoff handoff) {
        String sourceServer = source.getInfo().getName();
        String proxyName = registry.proxyServerFor(handoff.targetServer());
        ServerInfo target = proxyName == null ? null : getProxy().getServerInfo(proxyName);
        if (target == null) {
            receipt(sourceServer, handoff.transferId(), false, "target " + handoff.targetServer() + " is not enrolled");
            return;
        }
        broker.reserve(handoff.transferId(), player.getUniqueId(), sourceServer, handoff.targetServer(), System.currentTimeMillis());
        player.connect(target, (success, error) -> {
            UUID transferId = handoff.transferId();
            if (broker.complete(transferId) == null) {
                return;
            }
            boolean ok = error == null && Boolean.TRUE.equals(success);
            receipt(sourceServer, transferId, ok, ok ? "" : error != null ? error.getMessage() : "connect refused");
        });
    }

    private void receipt(String sourceServer, UUID transferId, boolean ok, String detail) {
        ServerInfo source = getProxy().getServerInfo(sourceServer);
        if (source != null) {
            source.sendData(EnrollmentCodec.CHANNEL, EnrollmentCodec.encode(new EnrollmentCodec.Receipt(transferId, ok, detail == null ? "" : detail), secret));
        }
    }
}
