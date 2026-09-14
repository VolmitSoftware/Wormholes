package art.arcane.wormholes.access;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.access.adapters.ClaimAdapter;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.AccessConfig;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.hook.TraversalAttempt;
import art.arcane.wormholes.hook.TraversalGate;
import art.arcane.wormholes.hook.TraversalPhase;
import art.arcane.wormholes.hook.TraversalVerdict;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.localization.AccessMessages;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalPermissionMode;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AccessGateTest {
    private final ClaimAdapters adapters = new ClaimAdapters(List.of());
    private final AccessGate gate = new AccessGate(adapters);
    private WormholesSettings previousSettings;
    private World world;

    @BeforeEach
    void registerExtension() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new AccessExtensionFactory()));
        previousSettings = Wormholes.settings;
        Wormholes.settings = settings(true);
        world = AccessTestPortals.world("access-gate");
    }

    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
        Wormholes.settings = previousSettings;
    }

    private static WormholesSettings settings(boolean legacyNameNodeEnabled) {
        WormholesSettings created = new WormholesSettings(new MainConfig(), new ProjectionConfig(),
            new RenderConfig(), new NetworkConfig());
        created.getAccess().legacyNameNodeEnabled = legacyNameNodeEnabled;
        return created;
    }

    @Test
    void theGateRunsFirstAmongTheLaneGates() {
        assertEquals(TraversalGate.ORDER_ACCESS, gate.order());
    }

    @Test
    void nonPlayersAndOperatorsAlwaysPass() {
        LocalPortal portal = AccessTestPortals.portal(world);
        AccessPortalExtension access = portal.extension(AccessPortalExtension.class);
        Player operator = AccessTestPortals.player("Admin", true, Set.of());
        access.setRole(operator.getUniqueId(), PortalRole.DENIED);

        assertSame(TraversalVerdict.ALLOW, gate.evaluate(attempt(portal, AccessTestPortals.mob())));
        assertSame(TraversalVerdict.ALLOW, gate.evaluate(attempt(portal, operator)));
    }

    @Test
    void wildcardBypassesRolePermissionsDirectionAndClaims() {
        LocalPortal portal = AccessTestPortals.portal(world);
        Player traveler = AccessTestPortals.player("Wildcard", false, Set.of("*", "wormholes.portal.portal"));
        portal.extension(AccessPortalExtension.class).setRole(traveler.getUniqueId(), PortalRole.DENIED);
        portal.setOutgoingTraversalsEnabled(false);
        portal.setIncomingTraversalsEnabled(false);
        CountingAdapter claim = new CountingAdapter();
        ClaimAdapters claims = new ClaimAdapters(List.of(claim));
        claims.configure("worldguard", true);
        AccessGate claimGate = new AccessGate(claims);
        AccessConfig config = new AccessConfig();
        config.claimCheckOnUse = true;
        claimGate.applySettings(config);

        for (PortalPermissionMode mode : PortalPermissionMode.values()) {
            portal.setPermissionMode(mode);
            assertTrue(PortalAdmission.allows(portal, traveler));
            assertTrue(portal.canDepart(traveler));
            assertTrue(portal.canArrive(traveler));
            assertSame(TraversalVerdict.ALLOW, claimGate.evaluate(attempt(portal, traveler)));
        }
        assertEquals(0, claim.calls);
    }

    @Test
    void frameAdmissionHonorsOwnerRoleAndGroupWithoutAWhitelistPermissionNode() {
        LocalPortal portal = AccessTestPortals.portal(world);
        portal.setPermissionMode(PortalPermissionMode.WHITELIST);
        AccessPortalExtension access = portal.extension(AccessPortalExtension.class);
        Player owner = AccessTestPortals.player("Owner", false, Set.of());
        Player member = AccessTestPortals.player("Member", false, Set.of());
        Player group = AccessTestPortals.player("Group", false, Set.of("group.allowed"));
        Player stranger = AccessTestPortals.player("Stranger", false, Set.of());
        portal.setOwner(owner.getUniqueId());
        access.setRole(member.getUniqueId(), PortalRole.USER);
        access.addGroup("group.allowed");

        for (Player player : List.of(owner, member, group)) {
            assertTrue(portal.canDepart(player));
            assertTrue(portal.canArrive(player));
            assertSame(TraversalVerdict.ALLOW, gate.evaluate(attempt(portal, player)));
        }
        assertFalse(portal.canDepart(stranger));
        assertFalse(portal.canArrive(stranger));
    }

    @Test
    void aDeniedRoleRefusesAndBouncesWithTheRoleReason() {
        LocalPortal portal = AccessTestPortals.portal(world);
        portal.setName("Front Gate");
        Player traveler = AccessTestPortals.player("Traveler", false, Set.of());
        portal.extension(AccessPortalExtension.class).setRole(traveler.getUniqueId(), PortalRole.DENIED);

        TraversalVerdict verdict = gate.evaluate(attempt(portal, traveler));

        TraversalVerdict.Deny deny = assertInstanceOf(TraversalVerdict.Deny.class, verdict);
        assertSame(AccessMessages.DENIED_ROLE, deny.reason());
        assertTrue(deny.bounce());
    }

    @Test
    void oneTrustedRoleMakesThePortalWhitelistOnly() {
        LocalPortal portal = AccessTestPortals.portal(world);
        AccessPortalExtension access = portal.extension(AccessPortalExtension.class);
        Player trusted = AccessTestPortals.player("Trusted", false, Set.of());
        Player stranger = AccessTestPortals.player("Stranger", false, Set.of());
        access.setRole(trusted.getUniqueId(), PortalRole.USER);

        assertSame(TraversalVerdict.ALLOW, gate.evaluate(attempt(portal, trusted)));
        assertInstanceOf(TraversalVerdict.Deny.class, gate.evaluate(attempt(portal, stranger)));
    }

    @Test
    void anAllowedGroupGrantsAccessToAWhitelistOnlyPortal() {
        LocalPortal portal = AccessTestPortals.portal(world);
        AccessPortalExtension access = portal.extension(AccessPortalExtension.class);
        access.setRole(UUID.randomUUID(), PortalRole.USER);
        access.addGroup("group.staff");
        Player staff = AccessTestPortals.player("Staff", false, Set.of("group.staff"));
        Player stranger = AccessTestPortals.player("Stranger", false, Set.of());

        assertSame(TraversalVerdict.ALLOW, gate.evaluate(attempt(portal, staff)));
        assertInstanceOf(TraversalVerdict.Deny.class, gate.evaluate(attempt(portal, stranger)));
    }

    @Test
    void theOwnerPassesWithoutAnyRoleEntry() {
        LocalPortal portal = AccessTestPortals.portal(world);
        Player owner = AccessTestPortals.player("Owner", false, Set.of());
        portal.setOwner(owner.getUniqueId());
        portal.extension(AccessPortalExtension.class).setRole(UUID.randomUUID(), PortalRole.USER);

        assertSame(TraversalVerdict.ALLOW, gate.evaluate(attempt(portal, owner)));
    }

    @Test
    void theStablePermissionKeyNodeIsEvaluatedInThePortalsPermissionMode() {
        LocalPortal portal = AccessTestPortals.portal(world);
        portal.setName("Front Gate");
        Player holder = AccessTestPortals.player("Holder", false, Set.of("wormholes.portal.front_gate"));
        Player stranger = AccessTestPortals.player("Stranger", false, Set.of());

        portal.setPermissionMode(PortalPermissionMode.BLACKLIST);
        assertInstanceOf(TraversalVerdict.Deny.class, gate.evaluate(attempt(portal, holder)));
        assertSame(TraversalVerdict.ALLOW, gate.evaluate(attempt(portal, stranger)));

        portal.setPermissionMode(PortalPermissionMode.WHITELIST);
        assertSame(TraversalVerdict.ALLOW, gate.evaluate(attempt(portal, holder)));
        assertInstanceOf(TraversalVerdict.Deny.class, gate.evaluate(attempt(portal, stranger)));
    }

    /**
     * The rename case from the headline review: the name-derived node is an alias for the stable key, not
     * a second node to hold. On the shipped default both nodes open a renamed whitelist portal; with the
     * alias off only the stable key does.
     */
    @Test
    void aRenamedPortalKeepsItsOriginalNodeAndTheNameNodeIsOnlyAnAlias() {
        LocalPortal portal = AccessTestPortals.portal(world);
        portal.setName("Front Gate");
        portal.setPermissionMode(PortalPermissionMode.WHITELIST);
        portal.extension(AccessPortalExtension.class).permissionKey();
        portal.setName("Back Gate");
        Player original = AccessTestPortals.player("Original", false, Set.of("wormholes.portal.front_gate"));
        Player renamed = AccessTestPortals.player("Renamed", false, Set.of("wormholes.portal.back_gate"));

        assertSame(TraversalVerdict.ALLOW, gate.evaluate(attempt(portal, original)));
        assertSame(TraversalVerdict.ALLOW, gate.evaluate(attempt(portal, renamed)));

        Wormholes.settings = settings(false);

        assertSame(TraversalVerdict.ALLOW, gate.evaluate(attempt(portal, original)));
        assertInstanceOf(TraversalVerdict.Deny.class, gate.evaluate(attempt(portal, renamed)));
    }

    /**
     * Both checks used to be an AND: the frame gate read the name node and the access gate read the
     * stable key, so a renamed whitelist portal needed two nodes where it had needed one.
     */
    @Test
    void aRenamedWhitelistPortalNeedsOnlyOneNode() {
        LocalPortal portal = AccessTestPortals.portal(world);
        portal.setName("Spawn");
        portal.setPermissionMode(PortalPermissionMode.WHITELIST);
        portal.extension(AccessPortalExtension.class).permissionKey();
        portal.setName("Hub");
        Player holder = AccessTestPortals.player("Holder", false, Set.of("wormholes.portal.spawn"));

        assertSame(TraversalVerdict.ALLOW, gate.evaluate(attempt(portal, holder)));
        assertTrue(portal.canDepart(holder));
        assertTrue(portal.canArrive(holder));
    }

    @Test
    void aClaimThatRefusesUseDeniesTravelOnlyWhileTheUseCheckIsOn() {
        CountingAdapter claim = new CountingAdapter();
        ClaimAdapters claims = new ClaimAdapters(List.of(claim));
        claims.configure("worldguard", true);
        AccessGate claimGate = new AccessGate(claims);
        LocalPortal portal = AccessTestPortals.portal(world);
        Player traveler = AccessTestPortals.player("Traveler", false, Set.of());

        assertSame(TraversalVerdict.ALLOW, claimGate.evaluate(attempt(portal, traveler)));
        assertEquals(0, claim.calls);

        AccessConfig useChecks = new AccessConfig();
        useChecks.claimCheckOnUse = true;
        claimGate.applySettings(useChecks);

        TraversalVerdict verdict = claimGate.evaluate(attempt(portal, traveler));

        TraversalVerdict.Deny deny = assertInstanceOf(TraversalVerdict.Deny.class, verdict);
        assertSame(AccessMessages.DENIED_CLAIM, deny.reason());
        assertEquals(1, claim.calls);
    }

    @Test
    void theUseCheckIsCachedPerPortalAndPlayerForFiveSeconds() {
        CountingAdapter claim = new CountingAdapter();
        ClaimAdapters claims = new ClaimAdapters(List.of(claim));
        claims.configure("worldguard", true);
        AccessGate claimGate = new AccessGate(claims);
        AccessConfig useChecks = new AccessConfig();
        useChecks.claimCheckOnUse = true;
        claimGate.applySettings(useChecks);
        LocalPortal portal = AccessTestPortals.portal(world);
        Player traveler = AccessTestPortals.player("Traveler", false, Set.of());
        Player other = AccessTestPortals.player("Other", false, Set.of());

        claimGate.evaluate(attempt(portal, traveler, 1_000L));
        claimGate.evaluate(attempt(portal, traveler, 4_000L));
        assertEquals(1, claim.calls);

        claimGate.evaluate(attempt(portal, other, 4_000L));
        assertEquals(2, claim.calls);

        claimGate.evaluate(attempt(portal, traveler, 7_000L));
        assertEquals(3, claim.calls);
    }

    @Test
    void anUnaskableClaimPluginBricksAnExistingPortalOnlyWhenGrandfatheringIsOff() {
        FailingAdapter broken = new FailingAdapter();
        ClaimAdapters claims = new ClaimAdapters(List.of(broken));
        claims.configure("worldguard", true);
        AccessGate claimGate = new AccessGate(claims);
        LocalPortal portal = AccessTestPortals.portal(world);
        Player traveler = AccessTestPortals.player("Traveler", false, Set.of());

        AccessConfig grandfathered = new AccessConfig();
        grandfathered.claimCheckOnUse = true;
        claimGate.applySettings(grandfathered);

        assertSame(TraversalVerdict.ALLOW, claimGate.evaluate(attempt(portal, traveler, 1_000L)));

        AccessConfig strict = new AccessConfig();
        strict.claimCheckOnUse = true;
        strict.grandfatherExistingPortals = false;
        claimGate.applySettings(strict);

        assertInstanceOf(TraversalVerdict.Deny.class, claimGate.evaluate(attempt(portal, traveler, 1_000L)));
    }

    private static TraversalAttempt attempt(LocalPortal portal, Entity traveler) {
        return attempt(portal, traveler, 1L);
    }

    private static TraversalAttempt attempt(LocalPortal portal, Entity traveler, long nowMillis) {
        return new TraversalAttempt(TraversalPhase.DEPART, portal, traveler, null, null, nowMillis);
    }

    private static final class FailingAdapter implements ClaimAdapter {
        @Override
        public String id() {
            return "worldguard";
        }

        @Override
        public String pluginName() {
            return "WorldGuard";
        }

        @Override
        public boolean perCell() {
            return true;
        }

        @Override
        public void invalidate() {
        }

        @Override
        public PlacementDecision evaluate(PlacementRequest request) {
            return PlacementDecision.failureResult("WorldGuard", new IllegalStateException("API moved"));
        }
    }

    private static final class CountingAdapter implements ClaimAdapter {
        private int calls;

        @Override
        public String id() {
            return "worldguard";
        }

        @Override
        public String pluginName() {
            return "WorldGuard";
        }

        @Override
        public boolean perCell() {
            return true;
        }

        @Override
        public void invalidate() {
        }

        @Override
        public PlacementDecision evaluate(PlacementRequest request) {
            calls++;
            return PlacementDecision.deniedResult("WorldGuard");
        }
    }
}
