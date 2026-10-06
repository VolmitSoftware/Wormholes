package art.arcane.optics.scan;

import art.arcane.wormholes.portal.IPortal;

public interface ScanDestination<P extends IPortal, V> {
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
