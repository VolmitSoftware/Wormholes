package art.arcane.wormholes.modded.gravity;

import art.arcane.optics.crossing.GravityFrame;

public interface GravityHolder {
    GravityFrame wormholesGravity();

    void wormholesGravity(GravityFrame frame);
}
