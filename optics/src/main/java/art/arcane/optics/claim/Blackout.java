package art.arcane.optics.claim;

public interface Blackout<B> {
    boolean isEnabled();
    B data();
}
