package art.arcane.optics.fidelity;

public record FidelityOptions(double entityVelocityEpsilon,
                              double biomeDominance,
                              boolean bedrockEnabled,
                              boolean bedrockDisplayEntities,
                              boolean bedrockLightingFidelity,
                              int bedrockEntityCap,
                              double acousticsRadius,
                              int acousticsRateCapPerObserver) {
}
