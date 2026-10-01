package art.arcane.wormholes.render.client.session;

import art.arcane.wormholes.network.client.BrickLightSource;
import art.arcane.wormholes.render.plate.ViewPlate;

@FunctionalInterface
public interface ClientViewPlateHandoff<B> {
    long publish(int portalKey, int plateRevision, ViewPlate<B> plate, BrickLightSource light);

    static <B> ClientViewPlateHandoff<B> none() {
        return (portalKey, plateRevision, plate, light) -> 0L;
    }
}
