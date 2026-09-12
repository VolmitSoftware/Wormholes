package art.arcane.wormholes.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class PortalCooldownsTest {
    private static final UUID TRAVELER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_TRAVELER = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PORTAL_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID PORTAL_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    @BeforeEach
    void emptyRegistry() {
        PortalCooldowns.clear();
    }

    @Test
    void aPerPortalStampBlocksOnlyThatPortal() {
        PortalCooldowns.stamp(TRAVELER, PORTAL_A, "", 5000L, 1000L);

        assertEquals(5000L, PortalCooldowns.remainingMillis(TRAVELER, PORTAL_A, "", 1000L));
        assertEquals(1L, PortalCooldowns.remainingMillis(TRAVELER, PORTAL_A, "", 5999L));
        assertEquals(0L, PortalCooldowns.remainingMillis(TRAVELER, PORTAL_A, "", 6000L));
        assertEquals(0L, PortalCooldowns.remainingMillis(TRAVELER, PORTAL_B, "", 1000L));
        assertEquals(0L, PortalCooldowns.remainingMillis(OTHER_TRAVELER, PORTAL_A, "", 1000L));
    }

    @Test
    void aGroupStampBlocksEveryPortalCarryingTheGroup() {
        PortalCooldowns.stamp(TRAVELER, PORTAL_A, "hub", 5000L, 1000L);

        assertEquals(5000L, PortalCooldowns.remainingMillis(TRAVELER, PORTAL_B, "hub", 1000L));
        assertEquals(0L, PortalCooldowns.remainingMillis(TRAVELER, PORTAL_B, "spire", 1000L));
        assertEquals(0L, PortalCooldowns.remainingMillis(OTHER_TRAVELER, PORTAL_A, "hub", 1000L));
    }

    @Test
    void theLongerOfThePortalAndGroupStampWins() {
        PortalCooldowns.stamp(TRAVELER, PORTAL_A, "", 2000L, 1000L);
        PortalCooldowns.stamp(TRAVELER, PORTAL_B, "hub", 9000L, 1000L);

        assertEquals(9000L, PortalCooldowns.remainingMillis(TRAVELER, PORTAL_A, "hub", 1000L));
    }

    @Test
    void aZeroCooldownClearsAnyExistingStamp() {
        PortalCooldowns.stamp(TRAVELER, PORTAL_A, "hub", 5000L, 1000L);
        PortalCooldowns.stamp(TRAVELER, PORTAL_A, "hub", 0L, 1000L);

        assertEquals(0L, PortalCooldowns.remainingMillis(TRAVELER, PORTAL_A, "hub", 1000L));
    }

    @Test
    void quittingClearsEveryStampForThatTraveler() {
        PortalCooldowns.stamp(TRAVELER, PORTAL_A, "hub", 5000L, 1000L);
        PortalCooldowns.stamp(OTHER_TRAVELER, PORTAL_A, "hub", 5000L, 1000L);

        PortalCooldowns.clear(TRAVELER);

        assertEquals(0L, PortalCooldowns.remainingMillis(TRAVELER, PORTAL_A, "hub", 1000L));
        assertEquals(5000L, PortalCooldowns.remainingMillis(OTHER_TRAVELER, PORTAL_A, "hub", 1000L));
    }

    @Test
    void pruningOnlyRunsOnceTheRegistryIsLargeAndDropsExpiredStamps() {
        for (int i = 0; i < 300; i++) {
            PortalCooldowns.stamp(UUID.randomUUID(), PORTAL_A, "", 1000L, 1000L);
        }
        PortalCooldowns.stamp(TRAVELER, PORTAL_A, "", 60_000L, 1000L);
        assertEquals(301, PortalCooldowns.trackedStamps());

        PortalCooldowns.prune(9000L);

        assertEquals(1, PortalCooldowns.trackedStamps());
        assertTrue(PortalCooldowns.remainingMillis(TRAVELER, PORTAL_A, "", 9000L) > 0L);
    }
}
