package art.arcane.optics.stream;

import art.arcane.optics.plate.ViewPlate;

public record PlateOffer<B>(int portalKey, int plateRevision, ViewPlate<B> plate, BrickLightSource light) {
}
