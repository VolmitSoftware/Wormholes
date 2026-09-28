package art.arcane.wormholes.render;

public interface ProjectionBlackout<B> {
    boolean isEnabled();
    B data();
}
