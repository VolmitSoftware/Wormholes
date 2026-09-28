package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class TransitionProfileTest {
    @Test
    void encodeRoundTripsThresholdArrivalAndMaskTicks() {
        TransitionProfile profile = new TransitionProfile(" minecraft:entity.enderman.teleport ", "minecraft:block.portal.travel", 12);

        assertEquals("minecraft:entity.enderman.teleport|minecraft:block.portal.travel|12", profile.encode());
        assertEquals(profile, TransitionProfile.decode(profile.encode()));
        assertTrue(profile.overridesMask());
        assertFalse(profile.isNone());
    }

    @Test
    void legacyProfilesWithAnApproachSoundKeepTheirOtherCues() {
        TransitionProfile decoded = TransitionProfile.decode(
            "minecraft:block.beacon.ambient|minecraft:entity.enderman.teleport|minecraft:block.portal.travel|12");

        assertEquals(new TransitionProfile("minecraft:entity.enderman.teleport", "minecraft:block.portal.travel", 12), decoded);
        assertFalse(decoded.encode().contains("beacon"));
    }

    @Test
    void blankMalformedAndOddlyShapedValuesDecodeToNone() {
        assertEquals(TransitionProfile.NONE, TransitionProfile.decode(null));
        assertEquals(TransitionProfile.NONE, TransitionProfile.decode("   "));
        assertEquals(TransitionProfile.NONE, TransitionProfile.decode("a|b"));
        assertEquals(TransitionProfile.NONE, TransitionProfile.decode("a|b|c|d|e"));
        assertEquals(new TransitionProfile("a", "b", -1), TransitionProfile.decode("a|b|nope"));
        assertFalse(TransitionProfile.decode("a|b|nope").overridesMask());
    }
}
