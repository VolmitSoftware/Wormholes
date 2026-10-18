package art.arcane.wormholes.modded.client.world;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.ClientRenderAccess;
import art.arcane.wormholes.modded.client.render.sodium.SodiumSectionDiscovery;
import art.arcane.wormholes.modded.mixin.client.ArrivalExtractorAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldLevelRendererAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldMinecraftAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedLevelAccess;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class ClientArrivalRendererTransactionTest extends MinecraftTestBase {
    @Test
    public void arrivalTerrainBuildsInsideItsOwnRendererScope() throws Exception {
        Minecraft minecraft = mock(Minecraft.class, withSettings().extraInterfaces(ClientWorldMinecraftAccess.class));
        ClientLevel source = mock(ClientLevel.class);
        ClientLevel prepared = mock(ClientLevel.class);
        LevelRenderer incoming = renderer();
        ClientWorldLoader.WorldRenderer preparedWorld = world(incoming);
        Map<ClientLevel, ClientWorldLoader.WorldRenderer> worlds = map("WORLD_RENDERER_MAP");
        Map<ClientLevel, ClientWorldLoader.WorldRenderer> previousWorlds = new IdentityHashMap<>(worlds);
        Field main = ClientWorldLoader.class.getDeclaredField("mainLevel");
        main.setAccessible(true);
        Object previousMain = main.get(null);
        worlds.clear();
        worlds.put(prepared, preparedWorld);
        main.set(null, source);
        try (MockedStatic<Minecraft> singleton = mockStatic(Minecraft.class);
             MockedStatic<SodiumSectionDiscovery> discovery = mockStatic(SodiumSectionDiscovery.class)) {
            singleton.when(Minecraft::getInstance).thenReturn(minecraft);
            ClientWorldLoader.withWorldRenderer(prepared, () -> ClientWorldLoader.prepareArrivalTerrain(prepared, Vec3.ZERO, true));
            discovery.verify(() -> SodiumSectionDiscovery.prepareArrival(incoming, Vec3.ZERO, true));
        } finally {
            worlds.clear();
            worlds.putAll(previousWorlds);
            main.set(null, previousMain);
        }
    }

    @Test
    public void failedHelperInstallationRestoresOriginalEnginesWithoutDisposingThem() throws Exception {
        Minecraft minecraft = mock(Minecraft.class, withSettings().extraInterfaces(ClientWorldMinecraftAccess.class));
        ClientLevel source = mock(ClientLevel.class, withSettings().extraInterfaces(PreparedLevelAccess.class));
        ClientLevel prepared = mock(ClientLevel.class, withSettings().extraInterfaces(PreparedLevelAccess.class));
        LevelRenderer original = renderer();
        LevelRenderer incoming = renderer();
        LevelRenderer sourceExtractor = original;
        LevelRenderer preparedExtractor = incoming;
        ClientWorldLoader.WorldRenderer sourceWorld = world(original);
        ClientWorldLoader.WorldRenderer preparedWorld = world(incoming);
        DimensionRenderHelper sourceHelper = mock(DimensionRenderHelper.class);
        when(((PreparedLevelAccess) source).wormholes$extractor()).thenReturn(sourceExtractor);
        doThrow(new IllegalStateException("lightmap installation failed")).doNothing().when(sourceHelper).install();
        Map<ClientLevel, ClientWorldLoader.WorldRenderer> worlds = map("WORLD_RENDERER_MAP");
        Map<ClientLevel, DimensionRenderHelper> helpers = map("RENDER_HELPER_MAP");
        Map<ClientLevel, ClientWorldLoader.WorldRenderer> previousWorlds = new IdentityHashMap<>(worlds);
        Map<ClientLevel, DimensionRenderHelper> previousHelpers = new IdentityHashMap<>(helpers);
        Field main = ClientWorldLoader.class.getDeclaredField("mainLevel");
        main.setAccessible(true);
        Object previousMain = main.get(null);
        worlds.clear();
        helpers.clear();
        worlds.put(source, sourceWorld);
        worlds.put(prepared, preparedWorld);
        helpers.put(source, sourceHelper);
        main.set(null, source);
        try (MockedStatic<Minecraft> singleton = mockStatic(Minecraft.class);
             MockedStatic<ClientRenderAccess> render = mockStatic(ClientRenderAccess.class);
             MockedStatic<FogRendererContext> fog = mockStatic(FogRendererContext.class)) {
            singleton.when(Minecraft::getInstance).thenReturn(minecraft);
            render.when(() -> ClientRenderAccess.mainTarget(minecraft)).thenReturn(mock(RenderTarget.class));
            assertThrows(IllegalStateException.class, () -> ClientWorldLoader.adoptArrival(prepared, source, Vec3.ZERO));
            assertSame(source, ClientWorldLoader.mainLevel());
            assertSame(sourceWorld, worlds.get(source));
            assertSame(preparedWorld, worlds.get(prepared));
            verify((ClientWorldMinecraftAccess) minecraft).wormholes$levelRenderer(original);
            verify((PreparedLevelAccess) source).wormholes$extractor(sourceExtractor);
            verify((ArrivalExtractorAccess) preparedExtractor).wormholes$arrivalLevel(prepared);
            verify(original, never()).close();
            verify(incoming, never()).close();
        } finally {
            worlds.clear();
            worlds.putAll(previousWorlds);
            helpers.clear();
            helpers.putAll(previousHelpers);
            main.set(null, previousMain);
        }
    }

    private static LevelRenderer renderer() {
        return mock(LevelRenderer.class, withSettings().extraInterfaces(ClientWorldLevelRendererAccess.class, ArrivalExtractorAccess.class));
    }

    private static ClientWorldLoader.WorldRenderer world(LevelRenderer renderer) throws Exception {
        Constructor<ClientWorldLoader.WorldRenderer> constructor = ClientWorldLoader.WorldRenderer.class.getDeclaredConstructor(
            LevelRenderer.class, LevelRenderState.class, int.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(renderer, new LevelRenderState(), 0, 0);
    }

    @SuppressWarnings("unchecked")
    private static <T> Map<ClientLevel, T> map(String name) throws Exception {
        Field field = ClientWorldLoader.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<ClientLevel, T>) field.get(null);
    }
}
