package art.arcane.wormholes.modded;

import art.arcane.wormholes.access.PortalRole;
import art.arcane.wormholes.localization.AccessMessages;
import art.arcane.wormholes.localization.FidelityMessages;
import art.arcane.wormholes.localization.NexusMessages;
import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.localization.TransitMessages;
import art.arcane.wormholes.menu.access.AccessRoutes;
import art.arcane.wormholes.menu.fidelity.FidelityRoutes;
import art.arcane.wormholes.menu.nexus.NexusRoutes;
import art.arcane.wormholes.menu.portal.MenuExtensionEntry;
import art.arcane.wormholes.menu.rules.RulesRoutes;
import art.arcane.wormholes.menu.transit.TransitRoutes;
import art.arcane.wormholes.nexus.NetworkRegistry;
import art.arcane.wormholes.nexus.PortalNetwork;
import art.arcane.wormholes.rules.RuleDocument;
import art.arcane.wormholes.transit.ScaleRuleSettings;
import art.arcane.wormholes.transit.TransitionProfile;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftExtensionEntriesTest extends MinecraftTestBase {
    private static final UUID PORTAL = UUID.fromString("e2b2c3d4-0000-4000-8000-0000000000aa");
    private static final UUID NETWORK = UUID.fromString("e2b2c3d4-0000-4000-8000-0000000000bb");

    private WormholesModRuntime runtime;
    private MinecraftPortal portal;
    private MinecraftRules rules;
    private NetworkRegistry networks;
    private List<MenuExtensionEntry> entries;

    @Before
    public void setUp() {
        runtime = mock(WormholesModRuntime.class);
        MinecraftPortalRegistry portals = mock(MinecraftPortalRegistry.class);
        MinecraftNexus nexus = mock(MinecraftNexus.class);
        portal = mock(MinecraftPortal.class);
        rules = mock(MinecraftRules.class);
        networks = mock(NetworkRegistry.class);
        when(runtime.portals()).thenReturn(portals);
        when(runtime.rules()).thenReturn(rules);
        when(runtime.nexus()).thenReturn(nexus);
        when(nexus.networks()).thenReturn(networks);
        when(portals.get(PORTAL)).thenReturn(portal);
        when(portal.getRoles()).thenReturn(Map.of());
        when(rules.document(portal)).thenReturn(RuleDocument.EMPTY);
        when(portal.getScaleRule()).thenReturn(ScaleRuleSettings.DEFAULT);
        when(portal.getTransitionProfile()).thenReturn(TransitionProfile.NONE);
        entries = MinecraftExtensionEntries.create(runtime);
    }

    @Test
    public void theFiveBuiltInEntriesKeepTodaysOrderItemsSpritesAndRoutes() {
        assertEquals(List.of("access", "rules", "nexus-network", "transit", "fidelity"), entries.stream().map(MenuExtensionEntry::id).toList());
        assertEquals(List.of("minecraft:name_tag", "minecraft:book", "minecraft:compass", "minecraft:feather", "minecraft:spyglass"),
            entries.stream().map(MenuExtensionEntry::chestItemId).toList());
        assertEquals(List.of("access", "settings", "network", "transit", "projection"), entries.stream().map(MenuExtensionEntry::nativeSprite).toList());
        assertEquals(List.of(AccessMessages.MENU_ENTRY, RulesMessages.MENU_ENTRY, NexusMessages.MENU_ENTRY, TransitMessages.MENU_ENTRY,
            FidelityMessages.MENU_ENTRY), entries.stream().map(MenuExtensionEntry::label).toList());
        assertEquals(List.of(0, 0, 1, 1, 1), entries.stream().map(MenuExtensionEntry::chestHintLines).toList());
        assertEquals(List.of(AccessRoutes.roles(PORTAL), RulesRoutes.profile(PORTAL), NexusRoutes.network(PORTAL), TransitRoutes.main(PORTAL),
            FidelityRoutes.main(PORTAL)), entries.stream().map(entry -> entry.route().apply(PORTAL)).toList());
        assertTrue(entries.stream().allMatch(entry -> entry.visible().test(null, PORTAL)));
    }

    @Test
    public void nothingGlowsOnAnUntouchedPortal() {
        assertTrue(entries.stream().noneMatch(entry -> entry.enchanted().test(null, PORTAL)));
        assertTrue(entries.stream().noneMatch(entry -> entry.enchanted().test(null, UUID.randomUUID())));
    }

    @Test
    public void accessRulesNetworkAndTransitGlowOnceConfigured() {
        when(portal.getRoles()).thenReturn(Map.of(UUID.randomUUID(), PortalRole.USER));
        RuleDocument authored = mock(RuleDocument.class);
        when(rules.document(portal)).thenReturn(authored);
        when(portal.setting("nexus.networkId")).thenReturn(NETWORK.toString());
        when(portal.setting("nexus.address")).thenReturn("ALPHA");
        when(portal.isMembrane()).thenReturn(true);

        assertEquals(List.of(true, true, true, true, false), entries.stream().map(entry -> entry.enchanted().test(null, PORTAL)).toList());
    }

    @Test
    public void aNetworkWithoutAnAddressDoesNotGlowAndTheTileNamesTheNetwork() {
        PortalNetwork network = mock(PortalNetwork.class);
        when(network.name()).thenReturn("Golden");
        when(networks.byId(NETWORK)).thenReturn(network);
        when(portal.setting("nexus.networkId")).thenReturn(NETWORK.toString());
        MenuExtensionEntry entry = entries.get(2);

        assertFalse(entry.enchanted().test(null, PORTAL));
        assertEquals("Golden", entry.arguments().apply(PORTAL).require("name").value());
        assertEquals("", entry.arguments().apply(PORTAL).require("address").value());
        assertTrue(entry.arguments().apply(UUID.randomUUID()).isEmpty());
    }
}
