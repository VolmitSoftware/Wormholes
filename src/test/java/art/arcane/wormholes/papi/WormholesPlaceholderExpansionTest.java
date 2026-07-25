package art.arcane.wormholes.papi;

import art.arcane.volmlib.util.bukkit.papi.PlaceholderSnapshot;
import art.arcane.volmlib.util.bukkit.papi.PlaceholderValues;
import art.arcane.volmlib.util.bukkit.papi.PlayerSnapshotStore;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WormholesPlaceholderExpansionTest {
    private static final UUID TRAVELLER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BYSTANDER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static final List<String> PUBLISHED_KEYS = List.of(
        "available",
        "failures",
        "failures.per-minute",
        "peers.connected",
        "peers.link",
        "portal.available",
        "portal.cross-server",
        "portal.destination",
        "portal.distance",
        "portal.name",
        "portal.state",
        "portals",
        "projections.active",
        "projections.observers",
        "rtp.cooldown",
        "rtp.state",
        "transfers.in-flight");

    private PlaceholderSnapshot<WormholesRuntimeSnapshot> runtime;
    private PlayerSnapshotStore<WormholesPortalSnapshot> portals;
    private WormholesPlaceholderExpansion expansion;

    @BeforeEach
    void setUp() {
        runtime = new PlaceholderSnapshot<>();
        portals = new PlayerSnapshotStore<>();
        expansion = new WormholesPlaceholderExpansion("1.0.0-26.2", WormholesPlaceholders.registry(runtime, portals), Logger.getAnonymousLogger());
    }

    @Test
    void theExpansionIdentifiesItselfWithoutTouchingAnyPluginStatic() {
        assertEquals("wormholes", expansion.getIdentifier());
        assertEquals("Volmit Software", expansion.getAuthor());
        assertEquals("1.0.0-26.2", expansion.getVersion());
        assertEquals("Wormholes", expansion.getRequiredPlugin());
        assertTrue(expansion.persist());
    }

    @Test
    void everyDocumentedKeyIsPublishedForPapiInfo() {
        assertEquals(PUBLISHED_KEYS, expansion.getPlaceholders());
    }

    @Test
    void withNoSnapshotYetEveryKeyIsUnavailableAndNothingReadsAsZero() {
        assertEquals(PlaceholderValues.FALSE, expansion.onRequest(player(TRAVELLER), "available"));
        assertEquals(PlaceholderValues.FALSE, expansion.onRequest(player(TRAVELLER), "portal.available"));

        for (String key : PUBLISHED_KEYS) {
            if (key.equals("available") || key.equals("portal.available")) {
                continue;
            }

            assertEquals(PlaceholderValues.UNAVAILABLE, expansion.onRequest(player(TRAVELLER), key), key);
        }
    }

    @Test
    void aMisspelledKeyReturnsNullSoPapiReEmitsTheLiteral() {
        publishRuntime();
        publishPortal(TRAVELLER);

        assertNull(expansion.onRequest(player(TRAVELLER), "portal.nmae"));
        assertNull(expansion.onRequest(player(TRAVELLER), "peers"));
        assertNull(expansion.onRequest(player(TRAVELLER), "portal_name"));
        assertNull(expansion.onRequest(player(TRAVELLER), "failures.per_minute"));
        assertNull(expansion.onRequest(player(TRAVELLER), "rtp"));
    }

    @Test
    void serverScopedKeysServeThePublishedRuntimeSnapshot() {
        publishRuntime();

        assertEquals(PlaceholderValues.TRUE, expansion.onRequest(player(TRAVELLER), "available"));
        assertEquals("17", expansion.onRequest(player(TRAVELLER), "portals"));
        assertEquals("4", expansion.onRequest(player(TRAVELLER), "projections.active"));
        assertEquals("9", expansion.onRequest(player(TRAVELLER), "projections.observers"));
        assertEquals("1", expansion.onRequest(player(TRAVELLER), "peers.connected"));
        assertEquals(WormholesRuntimeSnapshot.LINK_DEGRADED, expansion.onRequest(player(TRAVELLER), "peers.link"));
        assertEquals("3", expansion.onRequest(player(TRAVELLER), "transfers.in-flight"));
        assertEquals("812", expansion.onRequest(player(TRAVELLER), "failures"));
        assertEquals("6.50", expansion.onRequest(player(TRAVELLER), "failures.per-minute"));
    }

    @Test
    void serverScopedKeysStillAnswerWhenPapiPassesNoPlayer() {
        publishRuntime();

        assertEquals("17", expansion.onRequest(null, "portals"));
        assertEquals(PlaceholderValues.FALSE, expansion.onRequest(null, "portal.available"));
        assertEquals(PlaceholderValues.UNAVAILABLE, expansion.onRequest(null, "portal.name"));
    }

    @Test
    void perPlayerKeysServeOnlyTheSnapshotOfThatPlayer() {
        publishRuntime();
        publishPortal(TRAVELLER);

        assertEquals(PlaceholderValues.TRUE, expansion.onRequest(player(TRAVELLER), "portal.available"));
        assertEquals("Hub Gate", expansion.onRequest(player(TRAVELLER), "portal.name"));
        assertEquals(WormholesPortalSnapshot.STATE_SYNCING, expansion.onRequest(player(TRAVELLER), "portal.state"));
        assertEquals("beta", expansion.onRequest(player(TRAVELLER), "portal.destination"));
        assertEquals("18.25", expansion.onRequest(player(TRAVELLER), "portal.distance"));
        assertEquals(PlaceholderValues.TRUE, expansion.onRequest(player(TRAVELLER), "portal.cross-server"));
        assertEquals(WormholesPortalSnapshot.RTP_WARMING, expansion.onRequest(player(TRAVELLER), "rtp.state"));
        assertEquals("2.50", expansion.onRequest(player(TRAVELLER), "rtp.cooldown"));

        assertEquals(PlaceholderValues.FALSE, expansion.onRequest(player(BYSTANDER), "portal.available"));
        assertEquals(PlaceholderValues.UNAVAILABLE, expansion.onRequest(player(BYSTANDER), "portal.name"));
        assertEquals(PlaceholderValues.UNAVAILABLE, expansion.onRequest(player(BYSTANDER), "rtp.state"));
    }

    @Test
    void walkingAwayFromEveryPortalRetiresThePerPlayerAnswerWithoutTouchingTheServerKeys() {
        publishRuntime();
        publishPortal(TRAVELLER);
        portals.publish(TRAVELLER, null);

        assertEquals(PlaceholderValues.FALSE, expansion.onRequest(player(TRAVELLER), "portal.available"));
        assertEquals(PlaceholderValues.UNAVAILABLE, expansion.onRequest(player(TRAVELLER), "portal.destination"));
        assertEquals("17", expansion.onRequest(player(TRAVELLER), "portals"));
    }

    @Test
    void quittingKeepsTheLastAnswerForTheGraceWindowAndThenDropsIt() {
        publishPortal(TRAVELLER);
        portals.evictAfterGrace(TRAVELLER, 60_000L);

        assertEquals("Hub Gate", expansion.onRequest(player(TRAVELLER), "portal.name"));

        portals.evictAfterGrace(TRAVELLER, 0L);

        assertEquals(PlaceholderValues.UNAVAILABLE, expansion.onRequest(player(TRAVELLER), "portal.name"));
    }

    @Test
    void keysAreCaseInsensitiveBecauseThePapiParameterStringIsNotLowercased() {
        publishRuntime();
        publishPortal(TRAVELLER);

        assertEquals("17", expansion.onRequest(player(TRAVELLER), "Portals"));
        assertEquals("Hub Gate", expansion.onRequest(player(TRAVELLER), "PORTAL.NAME"));
        assertEquals("3", expansion.onRequest(player(TRAVELLER), "Transfers.In-Flight"));
    }

    @Test
    void blankParametersAreRefusedBeforeAnyLookup() {
        publishRuntime();

        assertNull(expansion.onRequest(player(TRAVELLER), null));
        assertNull(expansion.onRequest(player(TRAVELLER), ""));
        assertNull(expansion.onRequest(player(TRAVELLER), "   "));
    }

    @Test
    void noPublishedValueCanEverOpenANewPlaceholder() {
        publishRuntime();
        portals.publish(TRAVELLER, WormholesPortalSnapshot.of("%hub% §aGate", true, false, "%beta%", true, 4.0D, true, true, true, false, false, 0L));

        for (String key : PUBLISHED_KEYS) {
            String value = expansion.onRequest(player(TRAVELLER), key);
            assertFalse(value.contains("%"), key);
            assertFalse(value.contains("§"), key);
        }
    }

    private void publishRuntime() {
        runtime.publish(WormholesRuntimeSnapshot.of(17, 4, 9, true, true, 1, 2, 3, 812L, 6.5D));
    }

    private void publishPortal(UUID playerId) {
        portals.publish(playerId, WormholesPortalSnapshot.of("Hub Gate", true, true, "beta", true, 18.25D, true, true, false, true, false, 2_500L));
    }

    private static OfflinePlayer player(UUID id) {
        return (OfflinePlayer) Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "hashCode" -> id.hashCode();
            case "equals" -> proxy == args[0];
            case "toString" -> "OfflinePlayer[" + id + "]";
            default -> throw new UnsupportedOperationException("placeholder resolution touched " + method.getName());
        });
    }
}
