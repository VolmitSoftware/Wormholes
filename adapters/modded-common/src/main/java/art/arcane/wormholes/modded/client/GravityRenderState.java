package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.GravityFrame;

public interface GravityRenderState {
    GravityFrame wormholes$gravity();

    void wormholes$gravity(GravityFrame frame);
}
