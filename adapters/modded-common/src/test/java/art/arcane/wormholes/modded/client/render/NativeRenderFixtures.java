package art.arcane.wormholes.modded.client.render;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.pipeline.transform.parameter.DHParameters;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import org.joml.Vector3f;

import java.util.Set;

import static org.junit.Assert.assertEquals;

public final class NativeRenderFixtures {
    private NativeRenderFixtures() {
    }

    public static BakedQuad.MaterialInfo eastFaceMaterial() {
        return new BakedQuad.MaterialInfo(null, ChunkSectionLayer.SOLID, null, null, null, -1, Direction.EAST, 0);
    }

    public static DHParameters distantParameters(Patch patch) {
        return new DHParameters(patch, new Object2ObjectOpenHashMap<>(), Set.of());
    }

    public static void assertSkyColor(SkyRenderState sky, Vector3f color) {
        assertEquals(color, sky.skyColor);
    }
}
