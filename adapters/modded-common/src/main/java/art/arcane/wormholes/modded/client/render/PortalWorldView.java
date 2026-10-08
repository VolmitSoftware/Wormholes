package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Vec3d;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.Objects;

public record PortalWorldView(Object key, ClientLevel level, ApertureDescriptor geometry, Similarity surface, Similarity toLevel) {
    public PortalWorldView {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(toLevel, "toLevel");
    }

    public Vec3d levelPoint(Vec3d point) {
        return toLevel.point(point);
    }

    public boolean returning() {
        return !surface.equals(Similarity.IDENTITY);
    }
}
