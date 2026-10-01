package art.arcane.wormholes.network.client;

import java.util.Objects;

import art.arcane.wormholes.render.plate.ViewPlate;

public record PlateHandoff<B>(long handle,
                              int portalKey,
                              int plateRevision,
                              ViewPlate<B> plate,
                              B backingState,
                              BrickLightSource light,
                              long createdNanos) {
    public PlateHandoff {
        Objects.requireNonNull(plate, "plate");
        Objects.requireNonNull(backingState, "backingState");
        light = light == null ? BrickLightSource.NONE : light;
    }

    public boolean expired(long nowNanos) {
        return nowNanos - createdNanos > ClientViewProtocol.HANDOFF_EXPIRY_NANOS;
    }

    public ClientViewMessage.PlateHandle message() {
        return new ClientViewMessage.PlateHandle(portalKey, plateRevision, handle);
    }
}
