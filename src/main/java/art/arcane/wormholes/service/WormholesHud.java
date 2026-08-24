package art.arcane.wormholes.service;

import art.arcane.volmlib.util.hud.HudActionBar;
import art.arcane.volmlib.util.hud.HudPriority;
import art.arcane.volmlib.util.hud.HudSegment;
import art.arcane.volmlib.util.hud.HudSlot;
import art.arcane.volmlib.util.hud.HudTitleClaim;
import art.arcane.volmlib.util.hud.HudTitleService;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.wormholes.Wormholes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WormholesHud {
    private static final String NOTICE_PURPOSE = "wormholes:notice";
    private static final String HOLD_PURPOSE = "wormholes:hold";
    private static final String LOOK_PURPOSE = "wormholes:look";
    private static final String DIRECTION_PURPOSE = "wormholes:direction";
    private static final long RESOLVE_THROTTLE_MILLIS = 250L;
    private static final List<HudSlot> NOTICE_SLOTS = List.of(HudSlot.CENTER, HudSlot.RIGHT);
    private static final List<HudSlot> HOLD_SLOTS = List.of(HudSlot.CENTER, HudSlot.LEFT);

    private static final ConcurrentHashMap<UUID, ThrottledClaim> lookClaims = new ConcurrentHashMap<UUID, ThrottledClaim>();
    private static final ConcurrentHashMap<UUID, ThrottledClaim> directionClaims = new ConcurrentHashMap<UUID, ThrottledClaim>();
    private static volatile HudActionBar bar;
    private static volatile HudTitleService titles;

    private WormholesHud() {
    }

    public static void start(Wormholes plugin) {
        bar = new HudActionBar(plugin);
        titles = new HudTitleService(plugin);
    }

    public static void stop() {
        HudActionBar activeBar = bar;
        HudTitleService activeTitles = titles;
        bar = null;
        titles = null;
        lookClaims.clear();
        directionClaims.clear();
        if (activeTitles != null) {
            activeTitles.shutdown();
        }
        if (activeBar != null) {
            activeBar.shutdown();
        }
    }

    public static void notice(Player player, Component message) {
        publishSegment(player, message, NOTICE_PURPOSE, HudPriority.NOTICE, 2500L, NOTICE_SLOTS);
    }

    public static void hold(Player player, Component message) {
        publishSegment(player, message, HOLD_PURPOSE, HudPriority.MODAL, 1500L, HOLD_SLOTS);
    }

    public static boolean lookSubtitle(Player player, Title title) {
        HudTitleService activeTitles = titles;
        if (activeTitles == null || player == null || title == null) {
            return false;
        }
        ThrottledClaim claim = claimFor(lookClaims, activeTitles, player, LOOK_PURPOSE, HudPriority.AMBIENT);
        if (!claim.resolve(System.currentTimeMillis())) {
            return false;
        }
        WormholesAudience.showTitle(player, title);
        return true;
    }

    public static boolean directionTitle(Player player, Title title) {
        HudTitleService activeTitles = titles;
        if (activeTitles == null || player == null || title == null) {
            return false;
        }
        ThrottledClaim claim = claimFor(directionClaims, activeTitles, player, DIRECTION_PURPOSE, HudPriority.MODAL);
        if (claim.resolve(System.currentTimeMillis())) {
            WormholesAudience.showTitle(player, title);
            return true;
        }
        publishSegment(player, title.subtitle(), DIRECTION_PURPOSE, HudPriority.MODAL, 1500L, HOLD_SLOTS);
        return true;
    }

    public static void releaseDirection(Player player) {
        if (player == null) {
            return;
        }
        ThrottledClaim claim = directionClaims.remove(player.getUniqueId());
        if (claim != null) {
            claim.release();
        }
        HudActionBar activeBar = bar;
        if (activeBar != null) {
            activeBar.clear(player, DIRECTION_PURPOSE);
        }
    }

    public static void clear(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        lookClaims.remove(playerId);
        directionClaims.remove(playerId);
        HudActionBar activeBar = bar;
        if (activeBar != null) {
            activeBar.clearAll(player);
        }
        HudTitleService activeTitles = titles;
        if (activeTitles != null) {
            activeTitles.clear(player);
        }
    }

    private static void publishSegment(Player player, Component message, String purpose, int priority, long ttlMillis, List<HudSlot> slots) {
        HudActionBar activeBar = bar;
        if (activeBar == null || player == null || message == null) {
            return;
        }
        activeBar.publish(player, new HudSegment(
                purpose,
                priority,
                ttlMillis,
                slots,
                ComponentText.component(message).legacy()));
    }

    private static ThrottledClaim claimFor(ConcurrentHashMap<UUID, ThrottledClaim> cache, HudTitleService activeTitles, Player player, String purpose, int priority) {
        return cache.computeIfAbsent(player.getUniqueId(), playerId -> new ThrottledClaim(activeTitles.open(player, purpose, priority, 1500L)));
    }

    private static final class ThrottledClaim {
        private final HudTitleClaim claim;
        private long lastResolveMillis;

        private ThrottledClaim(HudTitleClaim claim) {
            this.claim = claim;
        }

        private synchronized boolean resolve(long nowMillis) {
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
