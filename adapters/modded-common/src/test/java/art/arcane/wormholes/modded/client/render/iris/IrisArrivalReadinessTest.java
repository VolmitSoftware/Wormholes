package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.ClientArrivalPreparation;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.mixin.client.IrisPortalPipelineAccess;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

public class IrisArrivalReadinessTest extends MinecraftTestBase {
    @Test
    public void shaderDestructionCancelsPreparedTerrainBeforeAnotherPackLoads() {
        WormholesClient client = mock(WormholesClient.class);
        ClientArrivalPreparation arrivals = mock(ClientArrivalPreparation.class);
        when(client.arrivals()).thenReturn(arrivals);
        try (MockedStatic<WormholesClient> singleton = mockStatic(WormholesClient.class)) {
            singleton.when(WormholesClient::instance).thenReturn(client);
            IrisPipelines.destroyed();
            verify(arrivals).clear();
        }
    }

    @Test
    public void arrivalRequiresMatchingTerrainSettingsAndCurrentShaderGeneration() throws Exception {
        ClientLevel level = mock(ClientLevel.class);
        DimensionType type = mock(DimensionType.class);
        ShaderPack pack = mock(ShaderPack.class);
        PipelineManager manager = mock(PipelineManager.class, withSettings().extraInterfaces(IrisPortalPipelineAccess.class));
        NamespacedId dimension = new NamespacedId("minecraft:overworld");
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.dimensionType()).thenReturn(type);
        when(type.skybox()).thenReturn(DimensionType.Skybox.OVERWORLD);
        when(pack.getDimensionMap()).thenReturn(Map.of(dimension, "world0"));
        when(((IrisPortalPipelineAccess) manager).wormholes$pipelines()).thenReturn(Map.of(dimension, mock(IrisRenderingPipeline.class)));
        ChunkVertexType format = mock(ChunkVertexType.class);
        IrisWorldSettings active = settings(format, 1F);
        Map<String, IrisWorldSettings> settings = settings();
        Map<String, IrisWorldSettings> previous = new HashMap<>(settings);
        try (MockedStatic<IrisPlatformHelpers> platform = platform();
             MockedStatic<Iris> iris = mockStatic(Iris.class);
             MockedStatic<IrisWorldSettings> captured = mockStatic(IrisWorldSettings.class)) {
            iris.when(Iris::getCurrentPack).thenReturn(Optional.of(pack));
            iris.when(Iris::getPipelineManager).thenReturn(manager);
            iris.when(Iris::getCurrentDimension).thenReturn(dimension);
            captured.when(IrisWorldSettings::capture).thenReturn(active);
            settings.put("minecraft:overworld", active);
            assertTrue(IrisPipelines.arrivalReady(level));
            iris.when(Iris::getCurrentDimension).thenReturn(new NamespacedId("minecraft:the_end"));
            assertFalse(IrisPipelines.arrivalReady(level));
            iris.when(Iris::getCurrentDimension).thenReturn(dimension);
            settings.put("minecraft:overworld", settings(format, 0.5F));
            assertFalse(IrisPipelines.arrivalReady(level));
            settings.put("minecraft:overworld", settings(mock(ChunkVertexType.class), 1F));
            assertFalse(IrisPipelines.arrivalReady(level));
            IrisPipelines.destroyed();
            assertFalse(IrisPipelines.arrivalReady(level));
            IrisRenderingPipeline replacement = mock(IrisRenderingPipeline.class);
            when(manager.getPipelineNullable()).thenReturn(replacement);
            when(((IrisPortalPipelineAccess) manager).wormholes$pipelines()).thenReturn(Map.of(dimension, replacement));
            assertTrue(IrisPipelines.arrivalReady(level));
            iris.when(Iris::getCurrentPack).thenReturn(Optional.empty());
            assertTrue(IrisPipelines.arrivalReady(level));
        } finally {
            settings.clear();
            settings.putAll(previous);
        }
    }

    private static IrisWorldSettings settings(ChunkVertexType format, float ambientOcclusion) {
        return new IrisWorldSettings(new Object2IntOpenHashMap<>(), Map.of(), null, null, format, ambientOcclusion,
            false, false, false, false, false, false);
    }

    private static MockedStatic<IrisPlatformHelpers> platform() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(IrisPlatformHelpers.class.getClassLoader());
        try {
            Class<? extends IrisPlatformHelpers> provider = ServiceLoader.load(IrisPlatformHelpers.class)
                .stream().findFirst().orElseThrow().type();
            try (MockedConstruction<? extends IrisPlatformHelpers> services = mockConstruction(provider)) {
                MockedStatic<IrisPlatformHelpers> platform = mockStatic(IrisPlatformHelpers.class);
                platform.when(IrisPlatformHelpers::getInstance).thenReturn(mock(IrisPlatformHelpers.class));
                return platform;
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, IrisWorldSettings> settings() throws Exception {
        Field field = IrisPipelines.class.getDeclaredField("SETTINGS");
        field.setAccessible(true);
        return (Map<String, IrisWorldSettings>) field.get(null);
    }
}
