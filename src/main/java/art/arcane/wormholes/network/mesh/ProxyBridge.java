package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.ServerCode;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.proxy.protocol.EnrollmentCodec;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Backend side of the proxy module. Enrolls this server's code whenever a player links the channel,
 * imports every roster entry the proxy answers with (trust + route flagged for proxy transfers),
 * and turns PROXY handoffs into HANDOFF frames the proxy brokers. Active only while
 * {@code [network.proxy] enabled} is true and a shared secret is configured; without the proxy
 * module nothing changes.
 *
 * <p>Bukkit delivers client-sent payloads on this channel to this listener, so nothing here treats
 * arrival as proof of origin. Every inbound frame must carry a valid HMAC tag for the configured
 * secret ({@link EnrollmentCodec#decode}); on top of that a roster must echo the nonce of an enroll
 * this server sent in the last {@value #NONCE_TTL_MILLIS} ms and a receipt must name a handoff this
 * server issued. Both bindings are single use.</p>
 */
public final class ProxyBridge implements PluginMessageListener, Listener {
    public interface Importer {
        void importServer(ServerCode code);
    }

    static final long NONCE_TTL_MILLIS = 15_000L;
    static final long HANDOFF_TTL_MILLIS = 60_000L;
    private static final int MAX_PENDING = 64;
    private static final long JOIN_ENROLL_DELAY_TICKS = 40L;
    private static volatile ProxyBridge active;

    private final String channel;
    private final byte[] secret;
    private final Supplier<ServerCode> localCode;
    private final Supplier<List<String>> portalNames;
    private final LongSupplier capabilities;
    private final Importer importer;
    private final LongSupplier clock;
    private final Map<UUID, Long> pendingNonces = new LinkedHashMap<>();
    private final Map<UUID, Long> pendingHandoffs = new LinkedHashMap<>();
    private final AtomicInteger imported = new AtomicInteger();
    private final AtomicInteger receipts = new AtomicInteger();
    private final AtomicInteger refused = new AtomicInteger();

    public ProxyBridge(byte[] secret, Supplier<ServerCode> localCode, Supplier<List<String>> portalNames, LongSupplier capabilities, Importer importer) {
        this(EnrollmentCodec.CHANNEL, secret, localCode, portalNames, capabilities, importer, System::currentTimeMillis);
    }

    public ProxyBridge(String channel, byte[] secret, Supplier<ServerCode> localCode, Supplier<List<String>> portalNames,
                       LongSupplier capabilities, Importer importer, LongSupplier clock) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("The proxy bridge needs a shared secret");
        }
        this.channel = channel;
        this.secret = secret.clone();
        this.localCode = localCode;
        this.portalNames = portalNames;
        this.capabilities = capabilities;
        this.importer = importer;
        this.clock = clock;
    }

    /** The bridge bound by the mesh subsystem, or null when the proxy module is disabled. */
    public static ProxyBridge active() {
        return active;
    }

    static void setActive(ProxyBridge bridge) {
        active = bridge;
    }

    public String channel() {
        return channel;
    }

    /** True while this bridge is still signing with the configured secret; a changed secret rebinds it. */
    public boolean matchesSecret(byte[] candidate) {
        return candidate != null && MessageDigest.isEqual(secret, candidate);
    }

    public int importedCount() {
        return imported.get();
    }

    public int receiptCount() {
        return receipts.get();
    }

    /** Inbound frames dropped because they were not a signed, solicited answer from the proxy. */
    public int refusedCount() {
        return refused.get();
    }

    public byte[] enrollFrame() {
        ServerCode code = localCode.get();
        UUID nonce = UUID.randomUUID();
        remember(pendingNonces, nonce, NONCE_TTL_MILLIS);
        return EnrollmentCodec.encode(
            new EnrollmentCodec.Enroll(code.serverName(), code.encode(), capabilities.getAsLong(), portalNames.get(), nonce), secret);
    }

    public byte[] handoffFrame(UUID transferId, String targetServer) {
        remember(pendingHandoffs, transferId, HANDOFF_TTL_MILLIS);
        return EnrollmentCodec.encode(new EnrollmentCodec.Handoff(transferId, targetServer), secret);
    }

    public void enroll(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.sendPluginMessage(Wormholes.instance, channel, enrollFrame());
    }

    /** Asks the proxy to move the player to the backend enrolled as {@code targetServer}. */
    public boolean handoff(Player player, String targetServer) {
        UUID transferId = UUID.randomUUID();
        player.sendPluginMessage(Wormholes.instance, channel, handoffFrame(transferId, targetServer));
        Wormholes.v(() -> "[xfer] proxy handoff " + player.getName() + " -> " + targetServer + " transferId=" + transferId);
        return true;
    }

    @Override
    public void onPluginMessageReceived(String receivedChannel, Player player, byte[] message) {
        if (!channel.equals(receivedChannel)) {
            return;
        }
        EnrollmentCodec.Frame frame;
        try {
            frame = EnrollmentCodec.decode(message, secret);
        } catch (IOException e) {
            refused.incrementAndGet();
            Wormholes.v(() -> "[proxy] dropped frame: " + e.getMessage());
            return;
        }
        if (frame instanceof EnrollmentCodec.Roster roster) {
            if (!consume(pendingNonces, roster.nonce(), NONCE_TTL_MILLIS)) {
                refused.incrementAndGet();
                Wormholes.v(() -> "[proxy] dropped roster: nonce " + roster.nonce() + " answers no enroll of ours");
                return;
            }
            String localName = localCode.get().serverName();
            for (String text : roster.serverCodes()) {
                ServerCode code = ServerCode.decode(text);
                if (code == null || code.serverName().equals(localName)) {
                    continue;
                }
                importer.importServer(code);
                imported.incrementAndGet();
            }
            return;
        }
        if (frame instanceof EnrollmentCodec.Receipt receipt) {
            if (!consume(pendingHandoffs, receipt.transferId(), HANDOFF_TTL_MILLIS)) {
                refused.incrementAndGet();
                Wormholes.v(() -> "[proxy] dropped receipt: transferId=" + receipt.transferId() + " is not ours");
                return;
            }
            receipts.incrementAndGet();
            Wormholes.v(() -> "[xfer] proxy receipt transferId=" + receipt.transferId() + " ok=" + receipt.ok() + (receipt.ok() ? "" : " detail=" + receipt.detail()));
        }
    }

    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        if (channel.equals(event.getChannel())) {
            enroll(event.getPlayer());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (Wormholes.instance != null) {
            WormholesPlatform.scheduleEntity(Wormholes.instance, player, () -> enroll(player), () -> { }, JOIN_ENROLL_DELAY_TICKS);
        }
    }

    private void remember(Map<UUID, Long> pending, UUID id, long ttlMillis) {
        long now = clock.getAsLong();
        synchronized (pending) {
            expire(pending, now, ttlMillis);
            while (pending.size() >= MAX_PENDING) {
                pending.remove(pending.keySet().iterator().next());
            }
            pending.put(id, now);
        }
    }

    private boolean consume(Map<UUID, Long> pending, UUID id, long ttlMillis) {
        long now = clock.getAsLong();
        synchronized (pending) {
            expire(pending, now, ttlMillis);
            return pending.remove(id) != null;
        }
    }

    private static void expire(Map<UUID, Long> pending, long nowMillis, long ttlMillis) {
        pending.values().removeIf(issued -> nowMillis - issued.longValue() > ttlMillis);
    }
}
