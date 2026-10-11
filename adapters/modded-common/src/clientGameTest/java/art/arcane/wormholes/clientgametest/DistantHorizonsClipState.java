package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.clientgametest.mixin.DistantHorizonsClipAccess;

import org.joml.Vector4f;
import org.joml.Vector4fc;

public final class DistantHorizonsClipState {
    private final Vector4f plane;

    public DistantHorizonsClipState() {
        Vector4fc current = DistantHorizonsClipAccess.wormholesTest$plane();
        plane = current == null ? null : new Vector4f(current);
    }

    public boolean restored() {
        Vector4fc current = DistantHorizonsClipAccess.wormholesTest$plane();
        return plane == null ? current == null : current != null && plane.equals(current, 0.0001F);
    }
}
