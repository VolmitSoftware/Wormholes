package art.arcane.optics.entity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlayerNamingTest {
    private static final UUID FAKE = UUID.fromString("12345678-1234-5678-90ab-cdef12345678");

    @Test
    void syntheticNamesUseTheHostPrefixAndFitAProfileName() {
        PlayerNaming naming = new PlayerNaming("Neutral", "np");
        String name = naming.syntheticProfileName(FAKE);
        assertEquals("np12345678123456", name);
        assertEquals(PlayerNaming.MAX_NAME_LENGTH, name.length());
        assertEquals("x123456781234567", new PlayerNaming("Neutral", "x").syntheticProfileName(FAKE));
    }

    @Test
    void upsideDownProfilesFlipEveryNameExceptTheFlipNamesThemselves() {
        PlayerNaming naming = new PlayerNaming("Neutral", "np");
        assertEquals(PlayerNames.FLIP_NAME, naming.projectedProfileName("Alex", FAKE, true));
        assertEquals("Neutral", naming.projectedProfileName(PlayerNames.FLIP_NAME, FAKE, true));
        assertEquals("Neutral", naming.projectedProfileName("Grumm", FAKE, true));
        assertTrue(naming.projectedProfileName("Alex", FAKE, false).startsWith("np"));
    }

    @Test
    void labelsFallBackToTheNeutralNameAndClampToSixteenCharacters() {
        PlayerNaming naming = new PlayerNaming("Neutral", "np");
        assertEquals("Neutral", naming.labelText(" "));
        assertEquals("Neutral", naming.labelText(null));
        assertEquals("abcdefghijklmnop", naming.labelText("abcdefghijklmnop-extra"));
    }

    @Test
    void namesThatCannotFormAProfileAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerNaming("", "np"));
        assertThrows(IllegalArgumentException.class, () -> new PlayerNaming("abcdefghijklmnopq", "np"));
        assertThrows(IllegalArgumentException.class, () -> new PlayerNaming("Neutral", "abcdefghijklmnop"));
    }
}
