package art.arcane.optics.stream;

import art.arcane.optics.plate.ViewPlate;

@FunctionalInterface
public interface ClientViewPlateHandoff<B> {
    long publish(int portalKey, int plateRevision, ViewPlate<B> plate, BrickLightSource light);

    static <B> ClientViewPlateHandoff<B> none() {
        return (portalKey, plateRevision, plate, light) -> 0L;
    }
}
