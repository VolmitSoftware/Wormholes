package art.arcane.optics.claim;

public interface ProjectionBlackout<B> {
    boolean isEnabled();
    B data();
}
