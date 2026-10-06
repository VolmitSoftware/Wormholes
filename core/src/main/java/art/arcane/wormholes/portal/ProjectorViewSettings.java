package art.arcane.wormholes.portal;

import art.arcane.optics.scan.ViewCadence;

public interface ProjectorViewSettings {
    static ViewCadence viewCadence(ProjectorViewSettings settings) {
        int depth = settings.getNetworkViewDepth();
        int heartbeatTicks = settings.getNetworkViewHeartbeatTicks();
        int entityIntervalTicks = settings.getNetworkViewEntityIntervalTicks();
        boolean standard = NetworkViewQuality.from(depth, heartbeatTicks, entityIntervalTicks,
            settings.getNetworkViewUnsubscribeGraceSeconds()) == NetworkViewQuality.STANDARD;
        return new ViewCadence(depth, heartbeatTicks, entityIntervalTicks, standard);
    }

    int getNetworkViewDepth();

    int getNetworkViewHeartbeatTicks();

    int getNetworkViewEntityIntervalTicks();

    int getNetworkViewUnsubscribeGraceSeconds();
}
