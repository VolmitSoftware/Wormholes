package art.arcane.optics.claim;

public interface Claim<C> {
    boolean isMaskAir();

    boolean isFullBright();

    boolean isHeld();

    boolean sameBlock(C other);

    boolean sameLightSource(C other);
}
