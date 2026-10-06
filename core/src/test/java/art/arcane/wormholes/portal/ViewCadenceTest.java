package art.arcane.wormholes.portal;

import art.arcane.optics.scan.ViewCadence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ViewCadenceTest {
    @Test
    void standardQualityFollowsTheGlobalCadence() {
        NetworkViewQuality standard = NetworkViewQuality.STANDARD;
        ViewCadence cadence = ProjectorViewSettings.viewCadence(new Settings(standard.getDepth(), standard.getHeartbeatTicks(),
            standard.getEntityIntervalTicks(), standard.getUnsubscribeGraceSeconds()));
        assertEquals(new ViewCadence(64, 60, 10, true), cadence);
    }

    @Test
    void everyOtherQualityKeepsItsOwnCadence() {
        for (NetworkViewQuality quality : new NetworkViewQuality[] {NetworkViewQuality.PERFORMANCE, NetworkViewQuality.BALANCED,
            NetworkViewQuality.CINEMATIC}) {
            ViewCadence cadence = ProjectorViewSettings.viewCadence(new Settings(quality.getDepth(), quality.getHeartbeatTicks(),
                quality.getEntityIntervalTicks(), quality.getUnsubscribeGraceSeconds()));
            assertEquals(new ViewCadence(quality.getDepth(), quality.getHeartbeatTicks(), quality.getEntityIntervalTicks(), false), cadence,
                quality.name());
        }
        assertEquals(new ViewCadence(64, 60, 10, false), ProjectorViewSettings.viewCadence(new Settings(64, 60, 10, 31)));
    }

    private record Settings(int getNetworkViewDepth, int getNetworkViewHeartbeatTicks, int getNetworkViewEntityIntervalTicks,
                            int getNetworkViewUnsubscribeGraceSeconds) implements ProjectorViewSettings {
    }
}
