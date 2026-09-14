package art.arcane.wormholes.access;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalPermissionMode;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.RemoteWorld;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalAccessDiagnosticsTest {
    private WormholesSettings previousSettings;

    @BeforeEach
    void prepareAccess() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new AccessExtensionFactory()));
        previousSettings = Wormholes.settings;
        Wormholes.settings = new WormholesSettings(new MainConfig(), new ProjectionConfig(), new RenderConfig(), new NetworkConfig());
    }

    @AfterEach
    void restoreAccess() {
        WormholesHooks.clear();
        Wormholes.settings = previousSettings;
    }

    @Test
    void blacklistDenialShowsActualStableAndNamePermissionResults() {
        LocalPortal portal = AccessTestPortals.portal(AccessTestPortals.world("diagnostics"));
        portal.setName("Original Gate");
        AccessPortalExtension access = portal.extension(AccessPortalExtension.class);
        access.setPermissionKey("stable_gate");
        portal.setName("Renamed Gate");
        Player player = AccessTestPortals.player("Guest", false, Set.of("wormholes.portal.stable_gate"));

        assertFalse(portal.canDepart(player));
        assertEquals("permission_node", PortalAccessDiagnostics.frameReason("DEPART", portal, player));
        String detail = PortalAccessDiagnostics.describe(portal, player);

        assertTrue(detail.contains("portal=" + portal.getId()));
        assertTrue(detail.contains("playerId=" + player.getUniqueId()));
        assertTrue(detail.contains("mode=BLACKLIST"));
        assertTrue(detail.contains("keyNode=wormholes.portal.stable_gate keyGranted=true"));
        assertTrue(detail.contains("nameNode=wormholes.portal.renamed_gate nameGranted=false nameAlias=true"));
        assertFalse(portal.canDepart(player));
    }

    @Test
    void directionAndArrivalClosureReasonsMatchTheirCheckedPhase() {
        LocalPortal portal = AccessTestPortals.portal(AccessTestPortals.world("diagnostics"));
        Player player = AccessTestPortals.player("Guest", false, Set.of());
        portal.setOutgoingTraversalsEnabled(false);
        portal.setIncomingTraversalsEnabled(false);

        assertEquals("outgoing_disabled", PortalAccessDiagnostics.frameReason("DEPART", portal, player));
        assertEquals("incoming_disabled", PortalAccessDiagnostics.frameReason("ARRIVE_PREFLIGHT", portal, player));
        assertEquals("incoming_disabled", PortalAccessDiagnostics.frameReason("ARRIVE_SETTLE", portal, player));
        assertEquals("incoming_disabled", PortalAccessDiagnostics.frameReason("LOCAL_ARRIVE", portal, player));
        assertEquals("portal_closed", PortalAccessDiagnostics.frameReason("ARRIVE", portal, player));
        assertEquals("portal_closed", PortalAccessDiagnostics.frameReason("ARRIVE_AFTER_TELEPORT", portal, player));
    }

    @Test
    void portalNamesCannotInjectLogLines() {
        LocalPortal portal = AccessTestPortals.portal(AccessTestPortals.world("diagnostics"));
        portal.setName("Gate\n\r\t\u0085\u2028\u2029\"\\end");
        Player player = AccessTestPortals.player("Guest", false, Set.of());

        String detail = PortalAccessDiagnostics.describe(portal, player);

        assertTrue(detail.contains("name=\"Gate      '/end\""));
        assertFalse(detail.contains("\n"));
        assertFalse(detail.contains("\r"));
        assertFalse(detail.contains("\t"));
        assertFalse(detail.contains("\u0085"));
        assertFalse(detail.contains("\u2028"));
        assertFalse(detail.contains("\u2029"));
    }

    @Test
    void remotePreflightLeavesPermissionsToTheDestination() {
        RemotePortal portal = new RemotePortal(UUID.randomUUID(), new RemoteWorld("qa-b", "minecraft:overworld"),
            new Vector(0, 64, 0), PortalType.GATEWAY, true, new AxisAlignedBB(new Vector(0, 64, 0), new Vector(2, 67, 0)));
        portal.setName("Destination Gate");
        portal.putMirroredExtensionSetting("access.permissionKey", "stable_remote");
        portal.setMirroredPermissionMode(PortalPermissionMode.WHITELIST);
        Player player = AccessTestPortals.player("Guest", false, Set.of("wormholes.portal.stable_remote"));

        assertTrue(portal.acceptsInboundTraversal(player));
        String detail = PortalAccessDiagnostics.describe(portal, player);

        assertTrue(detail.contains("remote=true peer=\"qa-b\""));
        assertTrue(detail.contains("mode=WHITELIST nameNode=wormholes.portal.destination_gate nameGranted=false"));
        assertFalse(detail.contains("keyNode="));
        portal.setMirroredIncomingTraversalsEnabled(false);
        assertFalse(portal.acceptsInboundTraversal(player));
        assertEquals("incoming_disabled", PortalAccessDiagnostics.frameReason("REMOTE_PREFLIGHT", portal, player));
        Player wildcard = AccessTestPortals.player("Wildcard", false, Set.of("*"));
        assertTrue(portal.acceptsInboundTraversal(wildcard));
    }

    @Test
    void repeatDenialsAreThrottledByPortalPlayerPhaseAndReason() {
        PortalAccessDiagnostics.RejectionThrottle throttle = new PortalAccessDiagnostics.RejectionThrottle();
        UUID portalId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        PortalAccessDiagnostics.RejectionKey key = new PortalAccessDiagnostics.RejectionKey(portalId, playerId, "DEPART", "permission_node");

        assertTrue(throttle.acquire(key, 1_000L));
        assertFalse(throttle.acquire(key, 5_999L));
        assertTrue(throttle.acquire(key, 6_000L));
        assertTrue(throttle.acquire(new PortalAccessDiagnostics.RejectionKey(portalId, playerId, "DEPART", "outgoing_disabled"), 6_000L));
        assertTrue(throttle.acquire(new PortalAccessDiagnostics.RejectionKey(portalId, UUID.randomUUID(), "DEPART", "permission_node"), 6_000L));
        assertTrue(throttle.acquire(new PortalAccessDiagnostics.RejectionKey(UUID.randomUUID(), playerId, "DEPART", "permission_node"), 6_000L));
    }
}
