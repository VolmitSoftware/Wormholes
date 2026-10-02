package art.arcane.wormholes.modded.client.render;

import net.minecraft.util.ARGB;
import net.minecraft.world.level.CardinalLighting;

import java.util.Objects;

public final class PortalTerrainLighting implements AutoCloseable {
    private static final ThreadLocal<PortalTerrainMaterials.Lighting> CURRENT = new ThreadLocal<>();
    private static final CardinalLighting UNSHADED = new CardinalLighting(1, 1, 1, 1, 1, 1);
    private final PortalTerrainMaterials.Lighting previous;

    public PortalTerrainLighting(PortalTerrainMaterials.Lighting lighting) {
        previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(lighting, "lighting"));
    }

    public static PortalTerrainMaterials.Lighting current() {
        return CURRENT.get();
    }

    public static int ambientColor(float brightness) {
        PortalTerrainMaterials.Lighting lighting = CURRENT.get();
        return lighting != null && lighting.separateAo() ? ARGB.white(brightness) : ARGB.gray(brightness);
    }

    public static float directionalBrightness(float brightness) {
        PortalTerrainMaterials.Lighting lighting = CURRENT.get();
        return lighting != null && lighting.disableDirectionalShading() ? 1 : brightness;
    }

    public static CardinalLighting cardinalLighting(CardinalLighting lighting) {
        PortalTerrainMaterials.Lighting current = CURRENT.get();
        return current != null && current.disableDirectionalShading() ? UNSHADED : lighting;
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
