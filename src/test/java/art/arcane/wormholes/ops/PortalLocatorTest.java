package art.arcane.wormholes.ops;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalLocatorTest {
    private static final UUID ONE = UUID.fromString("abcd1234-1111-1111-1111-111111111111");
    private static final UUID TWO = UUID.fromString("abcd5678-2222-2222-2222-222222222222");
    private static final UUID THREE = UUID.fromString("ffff0000-3333-3333-3333-333333333333");

    private final List<PortalLocator.Candidate> candidates = List.of(
        new PortalLocator.Candidate(ONE, "Hub"),
        new PortalLocator.Candidate(TWO, "hub"),
        new PortalLocator.Candidate(THREE, "Arena"));

    @Test
    void anExactNameWinsOverACaseInsensitiveOne() {
        PortalLocator.Resolution resolution = PortalLocator.resolve(candidates, "Hub");
        assertTrue(resolution.found());
        assertEquals(ONE, resolution.id());
    }

    @Test
    void aCaseInsensitiveNameResolvesWhenNoExactNameMatches() {
        PortalLocator.Resolution resolution = PortalLocator.resolve(candidates, "ARENA");
        assertEquals(THREE, resolution.id());
    }

    @Test
    void twoNamesThatDifferOnlyInCaseAreAmbiguousWhenTheQueryMatchesNeitherExactly() {
        PortalLocator.Resolution resolution = PortalLocator.resolve(candidates, "HUB");
        assertFalse(resolution.found());
        assertTrue(resolution.ambiguous());
        assertEquals(2, resolution.matches());
    }

    @Test
    void anIdPrefixResolvesAndAShortOrSharedPrefixDoesNot() {
        assertEquals(THREE, PortalLocator.resolve(candidates, "ffff0000").id());
        assertEquals(THREE, PortalLocator.resolve(candidates, "ffff").id());

        PortalLocator.Resolution shared = PortalLocator.resolve(candidates, "abcd");
        assertTrue(shared.ambiguous());

        PortalLocator.Resolution tooShort = PortalLocator.resolve(candidates, "ff");
        assertFalse(tooShort.found());
        assertFalse(tooShort.ambiguous());
    }

    @Test
    void anUnknownOrBlankQueryIsNotFound() {
        assertFalse(PortalLocator.resolve(candidates, "nowhere").found());
        assertFalse(PortalLocator.resolve(candidates, "  ").found());
        assertFalse(PortalLocator.resolve(List.of(), "Hub").found());
    }
}
