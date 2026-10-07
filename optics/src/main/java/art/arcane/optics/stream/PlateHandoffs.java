package art.arcane.optics.stream;

import art.arcane.optics.plate.ViewPlate;

@FunctionalInterface
public interface PlateHandoffs<B> {
    long publish(int portalKey, int plateRevision, ViewPlate<B> plate, BrickLightSource light);

    static <B> PlateHandoffs<B> none() {
        return (portalKey, plateRevision, plate, light) -> 0L;
    }
}
