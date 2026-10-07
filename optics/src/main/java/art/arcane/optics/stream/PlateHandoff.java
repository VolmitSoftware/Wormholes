package art.arcane.optics.stream;

import java.util.Objects;

import art.arcane.optics.plate.ViewPlate;

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
        return nowNanos - createdNanos > ViewStreamLimits.HANDOFF_EXPIRY_NANOS;
    }

    public ViewStreamMessage.PlateHandle message() {
        return new ViewStreamMessage.PlateHandle(portalKey, plateRevision, handle);
    }
}
