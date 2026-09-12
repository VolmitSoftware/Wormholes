package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.localization.TransitMessages;
import art.arcane.wormholes.portal.LocalPortal;

final class TransitPortalExtensionTest {
    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
    }

    @Test
    void extensionRoundTripsThroughPortalJsonAndSyncsExactlyFourKeys() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new TransitExtensionFactory()));
        World world = TransitTestSupport.world("extension");
        LocalPortal source = TransitTestSupport.portal(world);
        TransitPortalExtension transit = source.extension(TransitPortalExtension.class);
        assertNotNull(transit);
        assertEquals("transit", transit.key());
        assertNull(transit.momentum());
        assertNull(transit.orientation());
        assertFalse(transit.isMembrane());
        assertFalse(transit.isBounce());
        assertEquals(TransitionProfile.NONE, transit.profile());

        transit.setMomentum(new MomentumPolicy(MomentumPolicy.Mode.SCALE, 1.5D, 6.0D, new Vector(0.0D, 0.25D, 0.0D)));
        transit.setOrientation(OrientationPolicy.SNAP);
        transit.setMembrane(true);
        transit.setBounce(true);
        transit.setProfile(new TransitionProfile("minecraft:block.beacon.ambient", "minecraft:entity.enderman.teleport",
            "minecraft:block.portal.travel", 12));

        JSONObject encoded = source.toJSON();
        assertTrue(encoded.has("transit.momentum"));
        assertTrue(encoded.has("transit.orientation"));
        assertTrue(encoded.has("transit.membrane"));
        assertTrue(encoded.has("transit.bounce"));
        assertTrue(encoded.has("transit.profile"));

        TransitPortalExtension copy = new TransitPortalExtension();
        copy.load(encoded);
        assertEquals(MomentumPolicy.Mode.SCALE, copy.momentum().mode());
        assertEquals(1.5D, copy.momentum().factor());
        assertEquals(6.0D, copy.momentum().maxSpeed());
        assertEquals(new Vector(0.0D, 0.25D, 0.0D), copy.momentum().impulse());
        assertEquals(OrientationPolicy.SNAP, copy.orientation());
        assertTrue(copy.isMembrane());
        assertTrue(copy.isBounce());
        assertEquals("minecraft:block.beacon.ambient", copy.profile().approachSound());
        assertEquals("minecraft:entity.enderman.teleport", copy.profile().thresholdEffect());
        assertEquals("minecraft:block.portal.travel", copy.profile().arrivalSound());
        assertEquals(12, copy.profile().maskOverrideTicks());

        Map<String, String> sync = new LinkedHashMap<String, String>();
        source.extensions().collectSync(sync);
        assertEquals(Set.of("transit.momentum", "transit.orientation", "transit.membrane", "transit.profile"), sync.keySet());

        LocalPortal mirrored = TransitTestSupport.portal(world);
        Map<String, String> inbound = new LinkedHashMap<String, String>(sync);
        inbound.put("other.value", "ignored");
        mirrored.extensions().applySync(inbound);
        TransitPortalExtension applied = mirrored.extension(TransitPortalExtension.class);
        assertEquals(MomentumPolicy.Mode.SCALE, applied.momentum().mode());
        assertEquals(OrientationPolicy.SNAP, applied.orientation());
        assertTrue(applied.isMembrane());
        assertFalse(applied.isBounce(), "bounce is not replicated through the settings bag");
        assertEquals(12, applied.profile().maskOverrideTicks());
    }

    @Test
    void portalsWithoutTransitStateSaveNoTransitKeys() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new TransitExtensionFactory()));
        LocalPortal portal = TransitTestSupport.portal(TransitTestSupport.world("untouched"));
        JSONObject encoded = portal.toJSON();
        for (String key : encoded.keySet()) {
            assertFalse(key.startsWith("transit."), key);
        }
        Map<String, String> sync = new LinkedHashMap<String, String>();
        portal.extensions().collectSync(sync);
        assertTrue(sync.isEmpty());
    }

    @Test
    void momentumPolicyEncodingRoundTripsAndRejectsGarbage() {
        MomentumPolicy policy = new MomentumPolicy(MomentumPolicy.Mode.IMPULSE, 1.0D, 0.0D, new Vector(0.5D, -0.25D, 2.0D));
        assertEquals(policy, MomentumPolicy.decode(policy.encode()));
        assertNull(MomentumPolicy.decode("nonsense"));
        assertNull(MomentumPolicy.decode(""));
        assertEquals(MomentumPolicy.Mode.CLAMP, MomentumPolicy.Mode.parse("clamp", MomentumPolicy.Mode.PRESERVE));
        assertEquals(MomentumPolicy.Mode.PRESERVE, MomentumPolicy.Mode.parse("bogus", MomentumPolicy.Mode.PRESERVE));
        assertEquals(OrientationPolicy.MIRROR, OrientationPolicy.parse("mirror", OrientationPolicy.FRAME));
        assertEquals(OrientationPolicy.FRAME, OrientationPolicy.parse(null, OrientationPolicy.FRAME));
    }

    @Test
    void transitMessagesCarryTheLaneKeysWithTheirEnglishText() {
        assertEquals("transit.denied.membrane", TransitMessages.DENIED_MEMBRANE.id());
        assertEquals("{portal} can only be entered from the front.", TransitMessages.DENIED_MEMBRANE.english());
        assertEquals("transit.denied.convoy_size", TransitMessages.DENIED_CONVOY_SIZE.id());
        assertEquals("Your rig has {count} entities; the limit is {value}.", TransitMessages.DENIED_CONVOY_SIZE.english());
        assertEquals("transit.denied.convoy_fit", TransitMessages.DENIED_CONVOY_FIT.id());
        assertEquals("Your rig does not fit through {portal}.", TransitMessages.DENIED_CONVOY_FIT.english());
        assertEquals("transit.denied.convoy_member", TransitMessages.DENIED_CONVOY_MEMBER.id());
        assertEquals("Part of your rig may not use {portal}.", TransitMessages.DENIED_CONVOY_MEMBER.english());
        assertEquals("transit.convoy.waiting", TransitMessages.CONVOY_WAITING.id());
        assertEquals("Bring your whole rig through {portal}.", TransitMessages.CONVOY_WAITING.english());
        assertEquals("transit.convoy.failed", TransitMessages.CONVOY_FAILED.id());
        assertEquals("Convoy transfer failed: {reason}.", TransitMessages.CONVOY_FAILED.english());
        assertEquals("transit.bounced", TransitMessages.BOUNCED.id());
        assertEquals("{portal} pushed you back.", TransitMessages.BOUNCED.english());

        Set<String> ids = new HashSet<String>();
        for (MessageKey key : TransitMessages.keys()) {
            assertTrue(key.id().startsWith("transit."), key.id());
            assertTrue(ids.add(key.id()), "duplicate " + key.id());
        }
        assertTrue(ids.containsAll(List.of("transit.menu.entry", "transit.menu.momentum", "transit.menu.orientation",
            "transit.menu.membrane", "transit.menu.bounce", "transit.menu.profile")));
    }
}
