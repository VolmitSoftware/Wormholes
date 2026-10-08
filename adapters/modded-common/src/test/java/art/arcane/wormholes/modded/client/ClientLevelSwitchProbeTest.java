package art.arcane.wormholes.modded.client;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.modded.mixin.client.ClientWorldCameraAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldCloudAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldExtractorAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldGameRendererAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldLevelRendererAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldLightmapAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldMinecraftAccess;
import art.arcane.wormholes.modded.mixin.client.ParticleEngineAccess;
import com.mojang.blaze3d.pipeline.RenderTarget;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;

import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class ClientLevelSwitchProbeTest extends MinecraftTestBase {
    private static final Vec3 ARRIVAL_EYE = new Vec3(100.5D, 65.62D, 99.2D);

    @Test
    public void crossingInstallsThePrimedDestinationProbeAndSwapsRenderersWithoutReloadingAnyExtractor() throws ReflectiveOperationException {
        ClientLevel overworld = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        ClientLevel nether = ResidentTestFixtures.level(ResidentTestFixtures.NETHER);
        ClientPacketListener connection = ResidentTestFixtures.connection(overworld);
        Minecraft minecraft = mock(Minecraft.class, withSettings().extraInterfaces(ClientWorldMinecraftAccess.class));
        minecraft.level = overworld;
        when(minecraft.getConnection()).thenReturn(connection);
        LocalPlayer player = SeamlessTravelFixtures.player();
        when(player.getId()).thenReturn(42);
        when(player.getEyePosition()).thenReturn(ARRIVAL_EYE);
        minecraft.player = player;
        CloudRenderer cloud = mock(CloudRenderer.class, withSettings().extraInterfaces(ClientWorldCloudAccess.class));
        LevelRenderer overworldRenderer = mock(LevelRenderer.class, withSettings().extraInterfaces(ClientWorldLevelRendererAccess.class));
        when(overworldRenderer.cloudRenderer()).thenReturn(cloud);
        LevelExtractor overworldExtractor = mock(LevelExtractor.class, withSettings().extraInterfaces(ClientWorldExtractorAccess.class));
        engine(minecraft, "levelRenderer", overworldRenderer);
        engine(minecraft, "levelExtractor", overworldExtractor);
        engine(minecraft, "particleEngine", particleEngine());
        Camera camera = mock(Camera.class, withSettings().extraInterfaces(ClientWorldCameraAccess.class));
        when(camera.attributeProbe()).thenReturn(mock(EnvironmentAttributeProbe.class));
        LightmapRenderStateExtractor lightmapExtractor = mock(LightmapRenderStateExtractor.class,
            withSettings().extraInterfaces(ClientWorldLightmapAccess.class));
        GameRenderer gameRenderer = mock(GameRenderer.class, withSettings().extraInterfaces(ClientWorldGameRendererAccess.class));
        when(gameRenderer.mainCamera()).thenReturn(camera);
        when(gameRenderer.gameRenderState()).thenReturn(new GameRenderState());
        when(gameRenderer.mainRenderTarget()).thenReturn(mock(RenderTarget.class));
        when(((ClientWorldGameRendererAccess) gameRenderer).wormholes$lightmap()).thenReturn(mock(Lightmap.class));
        when(((ClientWorldGameRendererAccess) gameRenderer).wormholes$lightmapExtractor()).thenReturn(lightmapExtractor);
        engine(minecraft, "gameRenderer", gameRenderer);
        Class<?> fogContext = Class.forName("art.arcane.wormholes.modded.client.world.FogRendererContext");
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class);
             MockedStatic<?> fog = mockStatic(fogContext);
             MockedConstruction<Lightmap> lightmaps = mockConstruction(Lightmap.class);
             MockedConstruction<EnvironmentAttributeProbe> probes = mockConstruction(EnvironmentAttributeProbe.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            ClientWorldLoader.cleanUp();
            try {
                ResidentLevels residents = new ResidentLevels(message -> { }, 512L << 20);
                ClientWorldLoader.initializeIfNeeded();
                LevelRenderer netherRenderer = mock(LevelRenderer.class, withSettings().extraInterfaces(ClientWorldLevelRendererAccess.class));
                LevelExtractor netherExtractor = mock(LevelExtractor.class, withSettings().extraInterfaces(ClientWorldExtractorAccess.class));
                resident(nether, netherRenderer, netherExtractor);
                clearInvocations(overworldExtractor);

                ClientLevelSwitch.activate(residents, nether, SeamlessTravelFixtures.DESTINATION,
                    new ClientTravelMotion.Carry(180, 10, 178, 9, new Vec3d(0.5, 0, 0.2), new Vec3d(0.5, 0, 0.5)));

                EnvironmentAttributeProbe netherProbe = probes.constructed().getFirst();
                InOrder primed = inOrder(netherProbe, camera);
                primed.verify(netherProbe).reset();
                primed.verify(netherProbe).tick(nether, ARRIVAL_EYE);
                primed.verify((ClientWorldCameraAccess) camera).wormholes$attributeProbe(netherProbe);
                verify((ClientWorldGameRendererAccess) gameRenderer).wormholes$lightmap(lightmaps.constructed().getFirst());
                verify((ClientWorldLightmapAccess) lightmapExtractor).wormholes$needsUpdate(true);
                verify((ClientWorldMinecraftAccess) minecraft).wormholes$levelRenderer(netherRenderer);
                verify((ClientWorldMinecraftAccess) minecraft).wormholes$levelExtractor(netherExtractor);
                verify((ClientWorldExtractorAccess) netherExtractor).wormholes$levelRenderState(gameRenderer.gameRenderState().levelRenderState);
                for (LevelExtractor extractor : new LevelExtractor[]{overworldExtractor, netherExtractor}) {
                    verify(extractor, never()).setLevel(any());
                    verify(extractor, never()).allChanged();
                }
                verify(minecraft, never()).setLevel(any());
                assertSame(nether, minecraft.level);
                verify(nether).addEntity(player);
            } finally {
                ClientWorldLoader.cleanUp();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void resident(ClientLevel level, LevelRenderer renderer, LevelExtractor extractor) throws ReflectiveOperationException {
        Class<?> type = Class.forName(ClientWorldLoader.class.getName() + "$WorldRenderer");
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object world = constructor.newInstance(renderer, extractor, new LevelRenderState(), 0, 0);
        Field worlds = ClientWorldLoader.class.getDeclaredField("WORLD_RENDERER_MAP");
        worlds.setAccessible(true);
        ((Map<ClientLevel, Object>) worlds.get(null)).put(level, world);
    }

    private static ParticleEngine particleEngine() {
        ParticleEngine engine = mock(ParticleEngine.class, withSettings().extraInterfaces(ParticleEngineAccess.class));
        ParticleEngineAccess access = (ParticleEngineAccess) engine;
        when(access.wormholes$particles()).thenReturn(new IdentityHashMap<>());
        when(access.wormholes$emitters()).thenReturn(new ArrayDeque<>());
        when(access.wormholes$pending()).thenReturn(new ArrayDeque<>());
        when(access.wormholes$counts()).thenReturn(new Object2IntOpenHashMap<>());
        return engine;
    }

    private static void engine(Minecraft minecraft, String name, Object engine) throws ReflectiveOperationException {
        Field field = Minecraft.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(minecraft, engine);
    }
}
