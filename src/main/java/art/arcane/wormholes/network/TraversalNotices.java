package art.arcane.wormholes.network;

import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.service.WormholesHud;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

final class TraversalNotices {
    void unreachable(Player player, String reason) {
        if (reason == null || reason.isBlank()) {
            sendActionBar(player, Wormholes.text().component(WormholesMessages.PORTAL_DESTINATION_UNREACHABLE));
            return;
        }
        sendActionBar(player, Wormholes.text().component(
                WormholesMessages.PORTAL_DESTINATION_UNREACHABLE_DETAIL,
                WormholesLocalization.args(MessageArgument.untrusted("reason", reason))));
    }

    void cooldown(Player player, long retryAfterMillis) {
        sendActionBar(player, Wormholes.text().component(
                WormholesMessages.PORTAL_TRANSFER_COOLDOWN,
                WormholesLocalization.args(MessageArgument.untrusted("seconds", formatSeconds(retryAfterMillis)))));
    }

    void transferInterrupted(Player player, TextKey messageKey) {
        sendActionBar(player, Wormholes.text().component(messageKey));
    }

    void denied(Player player, String reason, long retryAfterMillis) {
        if (retryAfterMillis <= 0L) {
            sendActionBar(player, Wormholes.text().component(
                    WormholesMessages.PORTAL_TRANSFER_BLOCKED,
                    WormholesLocalization.args(MessageArgument.untrusted("reason", reason))));
            return;
        }
        sendActionBar(player, Wormholes.text().component(
                WormholesMessages.PORTAL_TRANSFER_BLOCKED_RETRY,
                WormholesLocalization.args(
                        MessageArgument.untrusted("reason", reason),
                        MessageArgument.untrusted("seconds", formatSeconds(retryAfterMillis)))));
    }

    void arrivalUnplaced(Player player) {
        sendActionBar(player, Wormholes.text().component(WormholesMessages.PORTAL_ARRIVAL_FAILED));
    }

    void arrivalDenied(Player player) {
        sendActionBar(player, Wormholes.text().component(WormholesMessages.PORTAL_ARRIVAL_DENIED));
    }

    void arrivalReturned(Player player, String sourcePeer) {
        sendActionBar(player, Wormholes.text().component(
                WormholesMessages.PORTAL_ARRIVAL_RETURNED,
                WormholesLocalization.args(MessageArgument.untrusted("server", sourcePeer))));
    }

    private void sendActionBar(Player player, Component message) {
        if (player == null) {
            return;
        }
        Runnable delivery = () -> {
            if (player.isOnline()) {
                WormholesHud.notice(player, message);
            }
        };
        if (FoliaScheduler.runEntity(Wormholes.instance, player, delivery)) {
            return;
        }
        Wormholes.w("[traversal] no scheduler accepted the traveler notice for " + player.getUniqueId());
    }

    private static String formatSeconds(long millis) {
        long tenths = Math.max(1L, (millis + 99L) / 100L);
        return Long.toString(tenths / 10L) + "." + Long.toString(tenths % 10L);
    }
}
