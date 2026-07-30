package art.arcane.wormholes.service;

import art.arcane.volmlib.util.hud.HudBossBarLane;
import art.arcane.volmlib.util.hud.HudPriority;
import art.arcane.volmlib.util.hud.HudSlotClaim;
import art.arcane.volmlib.util.hud.HudSlotRequest;
import art.arcane.volmlib.util.hud.HudSlotService;
import art.arcane.volmlib.util.hud.HudSurface;
import art.arcane.wormholes.Wormholes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WormholesHud {
    private static final String NOTICE_LANE = "wormholes:notice";
    private static final String HOLD_LANE = "wormholes:hold";
    private static final long NOTICE_STALE_MILLIS = 2500L;
    private static final long HOLD_STALE_MILLIS = 2500L;
    private static final long RESOLVE_THROTTLE_MILLIS = 250L;
    private static final HudSlotRequest NOTICE_REQUEST = new HudSlotRequest("wormholes:notice", HudPriority.NOTICE, 2500L, List.of(HudSurface.ACTION_BAR, HudSurface.BOSS_BAR));
    private static final HudSlotRequest HOLD_REQUEST = new HudSlotRequest("wormholes:hold", HudPriority.MODAL, 1500L, List.of(HudSurface.ACTION_BAR, HudSurface.BOSS_BAR));
    private static final HudSlotRequest LOOK_REQUEST = new HudSlotRequest("wormholes:look", HudPriority.AMBIENT, 1500L, List.of(HudSurface.TITLE));
    private static final HudSlotRequest DIRECTION_REQUEST = new HudSlotRequest("wormholes:direction", HudPriority.MODAL, 1500L, List.of(HudSurface.TITLE, HudSurface.ACTION_BAR));

    private static final ConcurrentHashMap<UUID, ThrottledClaim> noticeClaims = new ConcurrentHashMap<UUID, ThrottledClaim>();
    private static final ConcurrentHashMap<UUID, ThrottledClaim> holdClaims = new ConcurrentHashMap<UUID, ThrottledClaim>();
    private static final ConcurrentHashMap<UUID, ThrottledClaim> lookClaims = new ConcurrentHashMap<UUID, ThrottledClaim>();
    private static final ConcurrentHashMap<UUID, ThrottledClaim> directionClaims = new ConcurrentHashMap<UUID, ThrottledClaim>();
    private static volatile HudSlotService slots;
    private static volatile HudBossBarLane lanes;

    private WormholesHud() {
    }

    public static void start(Wormholes plugin) {
        slots = new HudSlotService(plugin);
        lanes = new HudBossBarLane();
    }

    public static void stop() {
        HudSlotService activeSlots = slots;
        HudBossBarLane activeLanes = lanes;
        slots = null;
        lanes = null;
        noticeClaims.clear();
        holdClaims.clear();
        lookClaims.clear();
        directionClaims.clear();
        if (activeLanes != null) {
            activeLanes.shutdown();
        }
        if (activeSlots != null) {
            activeSlots.shutdown();
        }
    }

    public static void notice(Player player, Component message) {
        sendBarBacked(player, message, noticeClaims, NOTICE_REQUEST, NOTICE_LANE, BarColor.RED, NOTICE_STALE_MILLIS);
    }

    public static void hold(Player player, Component message) {
        sendBarBacked(player, message, holdClaims, HOLD_REQUEST, HOLD_LANE, BarColor.YELLOW, HOLD_STALE_MILLIS);
    }

    public static boolean lookSubtitle(Player player, Title title) {
        HudSlotService activeSlots = slots;
        if (activeSlots == null || player == null || title == null) {
            return false;
        }
        ThrottledClaim claim = claimFor(lookClaims, activeSlots, player, LOOK_REQUEST);
        if (claim.resolve(System.currentTimeMillis()) != HudSurface.TITLE) {
            return false;
        }
        WormholesAudience.showTitle(player, title);
        return true;
    }

    public static boolean directionTitle(Player player, Title title) {
        HudSlotService activeSlots = slots;
        if (activeSlots == null || player == null || title == null) {
            return false;
        }
        ThrottledClaim claim = claimFor(directionClaims, activeSlots, player, DIRECTION_REQUEST);
        HudSurface surface = claim.resolve(System.currentTimeMillis());
        if (surface == HudSurface.TITLE) {
            WormholesAudience.showTitle(player, title);
            return true;
        }
        if (surface == HudSurface.ACTION_BAR) {
            WormholesAudience.sendActionBar(player, title.subtitle());
            return true;
        }
        return false;
    }

    public static void releaseDirection(Player player) {
        if (player == null) {
            return;
        }
        ThrottledClaim claim = directionClaims.remove(player.getUniqueId());
        if (claim != null) {
            claim.release();
        }
    }

    public static void clear(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        noticeClaims.remove(playerId);
        holdClaims.remove(playerId);
        lookClaims.remove(playerId);
        directionClaims.remove(playerId);
        HudSlotService activeSlots = slots;
        if (activeSlots != null) {
            activeSlots.clear(player);
        }
        HudBossBarLane activeLanes = lanes;
        if (activeLanes != null) {
            activeLanes.hideAll(player);
        }
    }

    private static void sendBarBacked(Player player, Component message, ConcurrentHashMap<UUID, ThrottledClaim> cache, HudSlotRequest request, String laneId, BarColor color, long staleMillis) {
        HudSlotService activeSlots = slots;
        HudBossBarLane activeLanes = lanes;
        if (activeSlots == null || activeLanes == null || player == null || message == null) {
            return;
        }
        ThrottledClaim claim = claimFor(cache, activeSlots, player, request);
        HudSurface surface = claim.resolve(System.currentTimeMillis());
        if (surface == HudSurface.ACTION_BAR) {
            WormholesAudience.sendActionBar(player, message);
            return;
        }
        if (surface == HudSurface.BOSS_BAR) {
            activeLanes.show(player, laneId, LegacyComponentSerializer.legacySection().serialize(message), 1.0D, color, BarStyle.SOLID, staleMillis);
        }
    }

    private static ThrottledClaim claimFor(ConcurrentHashMap<UUID, ThrottledClaim> cache, HudSlotService activeSlots, Player player, HudSlotRequest request) {
        return cache.computeIfAbsent(player.getUniqueId(), playerId -> new ThrottledClaim(activeSlots.open(player, request)));
    }

    private static final class ThrottledClaim {
        private final HudSlotClaim claim;
        private long lastResolveMillis;

        private ThrottledClaim(HudSlotClaim claim) {
            this.claim = claim;
        }

        private synchronized HudSurface resolve(long nowMillis) {
            if (lastResolveMillis != 0L && nowMillis - lastResolveMillis < RESOLVE_THROTTLE_MILLIS) {
                return claim.granted();
            }
            lastResolveMillis = nowMillis;
            return claim.resolve();
        }

        private void release() {
            claim.release();
        }
    }
}
