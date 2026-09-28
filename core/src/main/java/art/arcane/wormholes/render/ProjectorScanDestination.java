package art.arcane.wormholes.render;

import art.arcane.wormholes.portal.IPortal;

public interface ProjectorScanDestination<P extends IPortal, V> {
    V localView();
    V destView();
    P dest();
    IPortal destAnchor();
    double originX();
    double originY();
    double originZ();
    boolean mirrorMode();
    int mirrorRotationQuarterTurns();
}
