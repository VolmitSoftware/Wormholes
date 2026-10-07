package art.arcane.optics.internal.fidelity;

@FunctionalInterface
public interface RemoteBiomeLookup {
    /** Client biome id for the destination cell a claim was sampled from, or -1 when unknown. */
    int biomeIdFor(long remoteKey);
}
