package art.arcane.wormholes.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandAdminTest {
    @Test
    void freezeSecondsClampsToBoundsAndTreatsZeroOrLessAsUnfreeze() {
        assertEquals(0, CommandAdmin.normalizeFreezeSeconds(0));
        assertEquals(0, CommandAdmin.normalizeFreezeSeconds(-15));
        assertEquals(5, CommandAdmin.normalizeFreezeSeconds(1));
        assertEquals(5, CommandAdmin.normalizeFreezeSeconds(5));
        assertEquals(30, CommandAdmin.normalizeFreezeSeconds(30));
        assertEquals(300, CommandAdmin.normalizeFreezeSeconds(300));
        assertEquals(300, CommandAdmin.normalizeFreezeSeconds(9_000));
    }
}
