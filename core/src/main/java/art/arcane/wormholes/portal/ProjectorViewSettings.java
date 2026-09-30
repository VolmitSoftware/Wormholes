package art.arcane.wormholes.portal;

public interface ProjectorViewSettings {
    int getNetworkViewDepth();

    int getNetworkViewHeartbeatTicks();

    int getNetworkViewEntityIntervalTicks();

    int getNetworkViewUnsubscribeGraceSeconds();
}
