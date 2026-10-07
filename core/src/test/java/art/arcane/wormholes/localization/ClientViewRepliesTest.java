package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamSessionState;
import art.arcane.optics.stream.ViewStreamSessionStats;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientViewRepliesTest {
    private static final LocalizationSnapshot ENGLISH = LocalizationSnapshot.create(
        LocalizationCandidate.english(WormholesMessages.catalog(), PluralSelector.oneOther()));
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000a1e7");
    private static final UUID GHOST = UUID.fromString("00000000-0000-0000-0000-0000000000f0");

    @Test
    void statusWithoutSessionsReportsRuntimeAndConfiguredModeThenNone() {
        assertEquals(List.of("ClientView runtime on, configured off, 0 sessions", "No player has a ClientView session."),
            plain(ClientViewReplies.status(true, false, List.of(), playerId -> null)));
    }

    @Test
    void statusListsEverySessionAndFallsBackToTheIdForUnknownPlayers() {
        ViewStreamSessionStats alex = stats(ALEX, ViewStreamSessionState.CLIENT_VIEW,
            ViewStreamCapability.of(ViewStreamCapability.PLATES, ViewStreamCapability.BRICK_CACHE), 3072L, 0L, 0L,
            new ViewStreamMessage.ViewStats(10, 2, 400, 0, 120, 80, 12));
        ViewStreamSessionStats ghost = stats(GHOST, ViewStreamSessionState.PENDING, 0L, 0L, 4L, 2L, null);
        List<String> lines = plain(ClientViewReplies.status(false, true, List.of(alex, ghost),
            playerId -> Map.of(ALEX, "Alex").get(playerId)));

        assertEquals(List.of("ClientView runtime off, configured on, 2 sessions",
            "Alex CLIENT_VIEW | caps plates,brick_cache portals 2 frames 7 sent 3.0 KiB unacked 1 rtt 12ms cells 900"
                + " plate 12MB sweep 120us apply 80us",
            GHOST + " PENDING | caps - portals 2 frames 7 sent 0.0 KiB unacked 1 rtt 12ms cells 900 dropped 4 stale 2"), lines);
    }

    @Test
    void toggleResetAndLookupRepliesUseTheCatalog() {
        assertEquals("ClientView is offered again. Configured: off.",
            plain(ClientViewReplies.enabled(false)));
        assertEquals("ClientView is offered again. Configured: on.",
            plain(ClientViewReplies.enabled(true)));
        assertEquals("ClientView is off. Every session returned to vanilla projection.", plain(ClientViewReplies.disabled()));
        assertEquals("Restarting the ClientView stream for Alex.", plain(ClientViewReplies.reset("Alex", true)));
        assertEquals("Alex has no active ClientView session.", plain(ClientViewReplies.reset("Alex", false)));
        assertEquals("No online player is named Steve.", plain(ClientViewReplies.playerMissing("Steve")));
    }

    @Test
    void everyReplyKeyBelongsToTheClientViewCatalog() {
        List<ClientViewReplies.Reply> replies = new ArrayList<>(ClientViewReplies.status(true, true,
            List.of(stats(ALEX, ViewStreamSessionState.CLIENT_VIEW, 0L, 0L, 0L, 0L, null)), playerId -> "Alex"));
        replies.add(ClientViewReplies.enabled(true));
        replies.add(ClientViewReplies.disabled());
        replies.add(ClientViewReplies.reset("Alex", true));
        replies.add(ClientViewReplies.reset("Alex", false));
        replies.add(ClientViewReplies.playerMissing("Alex"));
        for (ClientViewReplies.Reply reply : replies) {
            assertTrue(ClientViewMessages.keys().contains(reply.key()), reply.key().id());
        }
    }

    private static ViewStreamSessionStats stats(UUID playerId, ViewStreamSessionState state, long caps, long bytes, long dropped, long stale,
                                                ViewStreamMessage.ViewStats view) {
        return new ViewStreamSessionStats(playerId, 1, state, caps, 2, 7L, bytes, 5L, 1, 4L, 12_500L, 900L, 30L, dropped, stale, 0L, 0L, view);
    }

    private static List<String> plain(List<ClientViewReplies.Reply> replies) {
        List<String> lines = new ArrayList<>(replies.size());
        for (ClientViewReplies.Reply reply : replies) {
            lines.add(plain(reply));
        }
        return lines;
    }

    private static String plain(ClientViewReplies.Reply reply) {
        return PlainTextComponentSerializer.plainText().serialize(
            WormholesMessageRenderer.render(ENGLISH.resolve(reply.key(), reply.arguments())));
    }
}
