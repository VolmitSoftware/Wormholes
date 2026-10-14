package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.wormholes.modded.MinecraftTestBase;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IrisMeshShaderDimensionTest extends MinecraftTestBase {
    @Test
    public void streamedNetherChoosesItsDestinationWithoutAResidentClientLevel() {
        ShaderPack pack = pack(Map.of());
        IrisMeshCamera camera = camera("minecraft:the_nether", EnvironmentState.Skybox.NONE);
        assertEquals(new NamespacedId("minecraft:the_nether"), IrisPipelines.resolve(camera, pack));
        IrisViewPipelinePath.Camera parent = new IrisViewPipelinePath.Camera("MIRROR", "overworld-parent", "minecraft:overworld",
            "minecraft:overworld", Similarity.IDENTITY);
        IrisViewPipelinePath path = camera.append(List.of(parent), "minecraft:the_nether");
        assertEquals(List.of(parent), path.cameras().subList(0, 1));
        assertEquals(camera.destination(), path.cameras().getLast().destination());
        assertEquals("minecraft:the_nether", path.dimension());
    }

    @Test
    public void configuredPackDimensionsOverrideSkyboxMapping() {
        NamespacedId custom = new NamespacedId("example:custom");
        ShaderPack pack = pack(Map.of(custom, "world_custom"));
        for (EnvironmentState.Skybox skybox : EnvironmentState.Skybox.values()) {
            assertEquals(custom, IrisPipelines.resolve(camera("example:custom", skybox), pack));
        }
    }

    @Test
    public void unaliasedCustomDestinationsUseTheirOwnSkyboxMapping() {
        ShaderPack pack = pack(Map.of());
        assertEquals(DimensionId.OVERWORLD, IrisPipelines.resolve(camera("example:surface", EnvironmentState.Skybox.OVERWORLD), pack));
        assertEquals(DimensionId.END, IrisPipelines.resolve(camera("example:void", EnvironmentState.Skybox.END), pack));
        assertEquals(new NamespacedId("example:sealed"), IrisPipelines.resolve(camera("example:sealed", EnvironmentState.Skybox.NONE), pack));
    }

    @Test
    public void streamedEnvironmentSuppliesTheSkyboxAndChangingItChangesCameraIdentity() {
        EnvironmentState environment = mock(EnvironmentState.class);
        EnvironmentState.World world = mock(EnvironmentState.World.class);
        EnvironmentState.Sky sky = mock(EnvironmentState.Sky.class);
        when(environment.world()).thenReturn(world);
        when(environment.sky()).thenReturn(sky);
        when(environment.transform()).thenReturn(OpticTransform.IDENTITY);
        when(environment.scale()).thenReturn(1.0F);
        when(world.dimensionKey()).thenReturn("example:custom");
        when(sky.skybox()).thenReturn(EnvironmentState.Skybox.END);
        IrisMeshCamera camera = IrisMeshCamera.of(List.of(aperture()), "minecraft:overworld", environment);
        assertEquals(EnvironmentState.Skybox.END, camera.destinationSkybox());
        assertEquals(DimensionId.END, IrisPipelines.resolve(camera, pack(Map.of())));
        assertNotEquals(camera, camera("example:custom", EnvironmentState.Skybox.OVERWORLD));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void dimensionPreloadResolvesThePocketLevelThroughItsOwnDimensionType() {
        Registry<DimensionType> types = mock(Registry.class);
        when(types.get(any(ResourceKey.class))).thenReturn(Optional.empty());
        type(types, "wormholes:fullbright_pockets", DimensionType.Skybox.NONE);
        type(types, "minecraft:the_nether", DimensionType.Skybox.NONE);
        ShaderPack pack = pack(Map.of());
        assertEquals(new NamespacedId("wormholes:pockets"), IrisPipelines.resolve(level("wormholes:pockets"), types, pack));
        assertEquals(DimensionId.NETHER, IrisPipelines.resolve(level("minecraft:the_nether"), types, pack));
        assertEquals(DimensionId.OVERWORLD, IrisPipelines.resolve(level("example:unknown"), types, pack));
    }

    @SuppressWarnings("unchecked")
    private static void type(Registry<DimensionType> types, String id, DimensionType.Skybox skybox) {
        DimensionType type = mock(DimensionType.class);
        when(type.skybox()).thenReturn(skybox);
        Holder.Reference<DimensionType> holder = mock(Holder.Reference.class);
        when(holder.value()).thenReturn(type);
        when(types.get(ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse(id)))).thenReturn(Optional.of(holder));
    }

    private static ResourceKey<Level> level(String id) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse(id));
    }

    private static ShaderPack pack(Map<NamespacedId, String> aliases) {
        ShaderPack pack = mock(ShaderPack.class);
        when(pack.getDimensionMap()).thenReturn(aliases);
        return pack;
    }

    private static IrisMeshCamera camera(String destination, EnvironmentState.Skybox skybox) {
        return new IrisMeshCamera(List.of(aperture()), "minecraft:overworld", destination, skybox, Similarity.IDENTITY);
    }

    private static ApertureDescriptor aperture() {
        return new ApertureDescriptor(0, 64, 0, Face.N.ordinal(), true, 0, true, 2, 3, new long[]{63L}, ShapeDescriptor.FULL,
            0.0F, 0.0F, 0.0F, 0, 0, 0, 0, 0, 0, 0, 0, 0.0D, 0, 1L, List.of());
    }
}
