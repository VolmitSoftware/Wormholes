package art.arcane.optics.view;

import art.arcane.optics.frame.DirectionMapping;

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
    B transform(B block, DirectionMapping mapping);
}
