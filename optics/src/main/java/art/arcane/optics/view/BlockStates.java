package art.arcane.optics.view;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.state.StateProperties;

public interface BlockStates<B, M> {
    B air();
    B occluded();
    boolean isOccluded(B block);
    M material(B block);
    String materialName(M material);
    boolean blockEntityCandidate(M material);
    boolean isAir(M material);
    boolean isOccluding(M material);
    boolean requiresTransform(B block);
    B transform(B block, AxisPermutation permutation);
    StateProperties properties(B block);
    B withProperties(B block, StateProperties properties);
}
