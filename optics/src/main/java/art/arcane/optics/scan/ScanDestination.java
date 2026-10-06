package art.arcane.optics.scan;

import art.arcane.optics.aperture.Endpoint;

public interface ScanDestination<P extends Endpoint, V> {
    V localView();
    V destView();
    P dest();
    Endpoint destAnchor();
    double originX();
    double originY();
    double originZ();
    boolean mirrorMode();
    int mirrorRotationQuarterTurns();
}
