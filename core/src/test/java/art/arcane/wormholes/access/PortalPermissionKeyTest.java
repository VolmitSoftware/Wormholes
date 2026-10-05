package art.arcane.wormholes.access;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PortalPermissionKeyTest {
    @ParameterizedTest
    @MethodSource("names")
    void namesNormalizeSeparatorsAndPreserveAllowedCharacters(String name, String expected) {
        assertEquals(expected, PortalPermissionKey.sanitize(name));
    }

    @ParameterizedTest
    @MethodSource("keys")
    void validKeysAreLowercaseAndAtMostSixtyFourCharacters(String key, boolean expected) {
        assertEquals(expected, PortalPermissionKey.isValid(key));
    }

    @Test
    void nodeIsThePortalPermissionPrefixPlusTheKey() {
        assertEquals("wormholes.portal.hub", PortalPermissionKey.node("hub"));
    }

    private static Stream<Arguments> names() {
        return Stream.of(
            Arguments.of("Front Gate", "front_gate"),
            Arguments.of("Front   Gate", "front_gate"),
            Arguments.of("Mine-1.A_B", "mine-1.a_b"),
            Arguments.of(null, "unnamed"),
            Arguments.of("   ", "unnamed"),
            Arguments.of("!!!", "unnamed"),
            Arguments.of("  hub  ", "hub"),
            Arguments.of(" Portal-01.Main ", "portal-01.main"),
            Arguments.of("Å A 日本 B Å", "a_b"));
    }

    private static Stream<Arguments> keys() {
        return Stream.of(
            Arguments.of("hub", true),
            Arguments.of("mine-1.a_b", true),
            Arguments.of("a".repeat(64), true),
            Arguments.of("a".repeat(65), false),
            Arguments.of("Hub", false),
            Arguments.of("front gate", false),
            Arguments.of("", false),
            Arguments.of(null, false));
    }
}
