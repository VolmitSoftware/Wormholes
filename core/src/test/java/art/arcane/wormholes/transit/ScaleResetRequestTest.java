package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ScaleResetRequestTest {
    @Test
    void selfIsTheDefaultTarget() {
        ScaleResetRequest request = ScaleResetRequest.parse("", "");

        assertEquals(ScaleResetRequest.Target.SELF, request.target());
        assertEquals(ScaleResetRequest.Problem.NONE, request.problem());
    }

    @Test
    void playerNamesAreKept() {
        ScaleResetRequest request = ScaleResetRequest.parse("Steve", "");

        assertEquals(ScaleResetRequest.Target.PLAYER, request.target());
        assertEquals("Steve", request.player());
    }

    @Test
    void allRequiresARadiusWithinTheLimit() {
        assertEquals(ScaleResetRequest.Problem.RADIUS_REQUIRED, ScaleResetRequest.parse("all", "").problem());
        assertEquals(ScaleResetRequest.Problem.RADIUS_REQUIRED, ScaleResetRequest.parse("ALL", "0").problem());
        assertEquals(ScaleResetRequest.Problem.RADIUS_REQUIRED, ScaleResetRequest.parse("all", "257").problem());
        assertEquals(ScaleResetRequest.Problem.RADIUS_REQUIRED, ScaleResetRequest.parse("all", "far").problem());
        ScaleResetRequest all = ScaleResetRequest.parse("all", "16");
        assertEquals(ScaleResetRequest.Target.ALL, all.target());
        assertEquals(16, all.radius());
        assertEquals(ScaleResetRequest.Problem.NONE, all.problem());
        assertEquals(256, ScaleResetRequest.parse("all", "256").radius());
    }
}
