package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PocketRulesTest {
    @Test
    void aNewPocketIsQuietSafeAndForgiving() {
        PocketRules defaults = PocketRules.defaults();

        assertFalse(defaults.allowsSpawn());
        assertFalse(defaults.allowsPvp());
        assertTrue(defaults.keepInventory());
        assertFalse(defaults.hasFixedTime());
        assertEquals(PocketRules.BuildPolicy.BUILDERS, defaults.build());
    }

    @Test
    void theBuildPolicyDecidesFromTheRosterRoleAndTheOwnerAlwaysWins() {
        PocketRules everyone = PocketRules.defaults().withBuild(PocketRules.BuildPolicy.EVERYONE);
        PocketRules builders = PocketRules.defaults().withBuild(PocketRules.BuildPolicy.BUILDERS);
        PocketRules ownerOnly = PocketRules.defaults().withBuild(PocketRules.BuildPolicy.OWNER);

        assertTrue(everyone.allowsBuild(PocketRole.VISITOR));
        assertTrue(everyone.allowsBuild(PocketRole.BUILDER));
        assertTrue(everyone.allowsBuild(PocketRole.OWNER));

        assertFalse(builders.allowsBuild(PocketRole.VISITOR));
        assertTrue(builders.allowsBuild(PocketRole.BUILDER));
        assertTrue(builders.allowsBuild(PocketRole.OWNER));

        assertFalse(ownerOnly.allowsBuild(PocketRole.VISITOR));
        assertFalse(ownerOnly.allowsBuild(PocketRole.BUILDER));
        assertTrue(ownerOnly.allowsBuild(PocketRole.OWNER));

        assertThrows(NullPointerException.class, () -> builders.allowsBuild(null));
    }

    @Test
    void everyRuleFlipsIndependentlyAndLeavesTheRestAlone() {
        PocketRules rules = PocketRules.defaults()
            .withMobs(true)
            .withPvp(true)
            .withKeepInventory(false)
            .withFixedTime(18_000L)
            .withBuild(PocketRules.BuildPolicy.OWNER);

        assertTrue(rules.allowsSpawn());
        assertTrue(rules.allowsPvp());
        assertFalse(rules.keepInventory());
        assertTrue(rules.hasFixedTime());
        assertEquals(18_000L, rules.fixedTime());
        assertEquals(PocketRules.BuildPolicy.OWNER, rules.build());
        assertEquals(PocketRules.defaults(), PocketRules.defaults().withMobs(false), "a no-op keeps the instance");
    }

    @Test
    void aFixedTimeIsEitherFollowTheWorldOrATickInsideOneDay() {
        assertFalse(PocketRules.defaults().withFixedTime(PocketRules.FOLLOW_WORLD_TIME).hasFixedTime());
        assertTrue(PocketRules.defaults().withFixedTime(0L).hasFixedTime());
        assertTrue(PocketRules.defaults().withFixedTime(23_999L).hasFixedTime());
        assertThrows(IllegalArgumentException.class, () -> PocketRules.defaults().withFixedTime(24_000L));
        assertThrows(IllegalArgumentException.class, () -> PocketRules.defaults().withFixedTime(-2L));
    }

    @Test
    void buildPoliciesParseFromConfigCaseInsensitivelyAndRejectJunk() {
        assertEquals(PocketRules.BuildPolicy.EVERYONE, PocketRules.BuildPolicy.parse("everyone"));
        assertEquals(PocketRules.BuildPolicy.BUILDERS, PocketRules.BuildPolicy.parse(" Builders "));
        assertEquals(PocketRules.BuildPolicy.OWNER, PocketRules.BuildPolicy.parse("OWNER"));
        assertEquals("builders", PocketRules.BuildPolicy.BUILDERS.configValue());
        assertThrows(IllegalArgumentException.class, () -> PocketRules.BuildPolicy.parse("admins"));
        assertThrows(NullPointerException.class, () -> PocketRules.BuildPolicy.parse(null));
    }

    @Test
    void rolesRankVisitorBelowBuilderBelowOwner() {
        assertTrue(PocketRole.OWNER.atLeast(PocketRole.BUILDER));
        assertTrue(PocketRole.BUILDER.atLeast(PocketRole.BUILDER));
        assertFalse(PocketRole.VISITOR.atLeast(PocketRole.BUILDER));
        assertTrue(PocketRole.VISITOR.atLeast(PocketRole.VISITOR));
    }
}
