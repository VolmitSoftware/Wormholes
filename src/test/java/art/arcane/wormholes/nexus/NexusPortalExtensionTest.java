package art.arcane.wormholes.nexus;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.nexus.DestinationEntry.TargetKind;
import art.arcane.wormholes.nexus.FrameIo.ComparatorOutput;
import art.arcane.wormholes.nexus.FrameIo.RedstoneAction;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusPortalExtensionTest {
    private World world;

    @BeforeEach
    void installExtension() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new NexusExtensionFactory(null)));
        world = NexusTestSupport.world("extension");
    }

    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
    }

    @Test
    void everyPieceOfNexusStateSurvivesAPortalJsonRoundTrip() {
        UUID networkId = UUID.randomUUID();
        UUID dialedBy = UUID.randomUUID();
        LocalPortal source = NexusTestSupport.portal(world, "source");
        NexusPortalExtension state = source.extension(NexusPortalExtension.class);
        assertNotNull(state);

        state.setNetworkId(networkId);
        state.setAddress("ab12");
        state.setLabel("Market");
        state.setDial(new DialState("CD34", 1_700_000_000_000L, dialedBy, true));
        state.setPolicy(new DestinationPolicy(DestinationMode.WEIGHTED, List.of(
                new DestinationEntry(TargetKind.ADDRESS, "CD34", 5, 1000, 2000, "market")), SelectionRule.SNEAK));
        state.setReciprocal(true);
        state.setFrameIo(new FrameIo(1, -2, 3, RedstoneAction.DIAL_NEXT, ComparatorOutput.TRAVERSALS));

        JSONObject encoded = source.toJSON();
        assertEquals(networkId.toString(), encoded.getString("nexus.networkId"));
        assertEquals("AB12", encoded.getString("nexus.address"));
        assertTrue(encoded.getBoolean("nexus.reciprocal"));

        LocalPortal target = NexusTestSupport.portal(world, "target");
        NexusPortalExtension loaded = target.extension(NexusPortalExtension.class);
        loaded.load(encoded);

        assertEquals(networkId, loaded.networkId());
        assertEquals("AB12", loaded.address());
        assertEquals("Market", loaded.label());
        assertEquals("CD34", loaded.dial().currentAddress());
        assertEquals(1_700_000_000_000L, loaded.dial().dialedAtMillis());
        assertEquals(dialedBy, loaded.dial().dialedBy());
        assertTrue(loaded.dial().sticky());
        assertEquals(state.policy(), loaded.policy());
        assertTrue(loaded.reciprocal());
        assertEquals(state.frameIo(), loaded.frameIo());
    }

    @Test
    void aPortalWithoutNexusStateWritesNoNexusKeys() {
        LocalPortal portal = NexusTestSupport.portal(world, "plain");
        JSONObject encoded = portal.toJSON();

        assertFalse(encoded.has("nexus.networkId"));
        assertFalse(encoded.has("nexus.address"));
        assertFalse(encoded.has("nexus.policy"));
        assertFalse(encoded.has("nexus.reciprocal"));
        assertNull(portal.extension(NexusPortalExtension.class).networkId());
    }

    @Test
    void replicationCarriesExactlyTheFourAgreedSyncKeys() {
        LocalPortal portal = NexusTestSupport.portal(world, "sync");
        NexusPortalExtension state = portal.extension(NexusPortalExtension.class);
        state.setNetworkId(UUID.randomUUID());
        state.setAddress("EF56");
        state.setLabel("Docks");
        state.setDial(new DialState("GH78", 5L, null, false));

        Map<String, String> settings = new LinkedHashMap<>();
        portal.extensions().collectSync(settings);

        assertEquals(java.util.Set.of("nexus.networkId", "nexus.address", "nexus.dial", "nexus.label"), settings.keySet());
        assertEquals("EF56", settings.get("nexus.address"));
        assertEquals("GH78", settings.get("nexus.dial"));
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            assertTrue(entry.getValue().length() < 1024, entry.getKey() + " exceeds the settings-bag value budget");
        }

        LocalPortal mirror = NexusTestSupport.portal(world, "mirror");
        mirror.extensions().applySync(settings);
        NexusPortalExtension mirrored = mirror.extension(NexusPortalExtension.class);

        assertEquals(state.networkId(), mirrored.networkId());
        assertEquals("EF56", mirrored.address());
        assertEquals("Docks", mirrored.label());
        assertEquals("GH78", mirrored.dial().currentAddress());
    }

    @Test
    void destroyingAPortalNotifiesTheLaneListenerOnce() {
        RecordingListener listener = new RecordingListener();
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new NexusExtensionFactory(listener)));
        LocalPortal portal = NexusTestSupport.portal(world, "doomed");
        NexusPortalExtension state = portal.extension(NexusPortalExtension.class);
        state.setNetworkId(UUID.randomUUID());

        portal.extension(NexusPortalExtension.class).onPortalDestroyed();

        assertEquals(1, listener.destroyed);
        assertEquals(portal.getId(), listener.lastPortalId);
    }

    private static final class RecordingListener implements NexusPortalListener {
        private int destroyed;
        private UUID lastPortalId;

        @Override
        public void onPortalDestroyed(LocalPortal portal, NexusPortalExtension extension) {
            destroyed++;
            lastPortalId = portal.getId();
        }
    }
}
