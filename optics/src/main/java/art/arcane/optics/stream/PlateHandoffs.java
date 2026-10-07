package art.arcane.optics.stream;

@FunctionalInterface
public interface PlateHandoffs<B> {
    long publish(PlateOffer<B> offer);

    static <B> PlateHandoffs<B> none() {
        return offer -> 0L;
    }
}
