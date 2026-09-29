package art.arcane.wormholes.render;

public interface BlockProjectionClaim<C> {
    boolean isMaskAir();

    boolean isFullBright();

    boolean isHeld();

    boolean sameBlock(C other);

    boolean sameLightSource(C other);
}
