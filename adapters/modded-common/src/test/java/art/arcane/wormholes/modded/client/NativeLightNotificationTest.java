package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;
import art.arcane.wormholes.modded.client.render.ClientTravelScene;
import art.arcane.wormholes.modded.mixin.client.LocalMeshChunkMixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import art.arcane.wormholes.network.client.TravelMessage;

public class NativeLightNotificationTest extends MinecraftTestBase {
    @Test
    @SuppressWarnings("unchecked")
    public void activeVanillaAndInactiveNotificationsStayInTheirExactWorldWithoutOptionalTerrain() throws Exception {
        Minecraft minecraft = mock(Minecraft.class);
        ClientLevel active = mock(ClientLevel.class);
        ClientLevel inactive = mock(ClientLevel.class);
        minecraft.level = active;
        LocalMeshChunkMixin callback = mock(LocalMeshChunkMixin.class, CALLS_REAL_METHODS);
        Field owner = LocalMeshChunkMixin.class.getDeclaredField("level");
        owner.setAccessible(true);
        LevelExtractor extractor = mock(LevelExtractor.class);
        Operation<Void> activeOriginal = mock(Operation.class);
        Operation<Void> inactiveOriginal = mock(Operation.class);
        Method update = LocalMeshChunkMixin.class.getDeclaredMethod("wormholesLightGeometry", LevelExtractor.class,
            int.class, int.class, int.class, Operation.class);
        update.setAccessible(true);
        long section = SectionPos.asLong(-5, -4, 7);
        try (MockedStatic<Minecraft> clients = mockStatic(Minecraft.class);
             MockedStatic<ClientSodiumTerrain> terrain = mockStatic(ClientSodiumTerrain.class)) {
            clients.when(Minecraft::getInstance).thenReturn(minecraft);
            owner.set(callback, active);
            update.invoke(callback, extractor, -5, -4, 7, activeOriginal);
            verify(activeOriginal).call(extractor, -5, -4, 7);
            terrain.verify(() -> ClientSodiumTerrain.lightChanged(active, section, true));
            owner.set(callback, inactive);
            update.invoke(callback, extractor, -5, -4, 7, inactiveOriginal);
            verify(inactiveOriginal, never()).call(extractor, -5, -4, 7);
            terrain.verify(() -> ClientSodiumTerrain.lightChanged(inactive, section, false));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void lightNotificationRetiresByteProofAndCustomSnapshotWithoutExpandingNativeGeometry() throws Exception {
        ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
        ClientLevel level = mock(ClientLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        ClientTravelScene scene = mock(ClientTravelScene.class);
        SectionPos position = SectionPos.of(-5, -4, 7);
        long section = position.asLong();
        long dependent = SectionPos.asLong(-4, -4, 7);
        when(scene.changedSection(section)).thenReturn(new LongArrayList(new long[]{section, dependent}));
        Field staged = ClientPreparedTravel.class.getDeclaredField("staged");
        staged.setAccessible(true);
        staged.set(travel, level);
        Field displayed = ClientPreparedTravel.class.getDeclaredField("scene");
        displayed.setAccessible(true);
        displayed.set(travel, scene);
        Field retained = ClientPreparedTravel.class.getDeclaredField("payloads");
        retained.setAccessible(true);
        Map<TravelMessage.TravelCoordinate, byte[]> payloads = (Map<TravelMessage.TravelCoordinate, byte[]>) retained.get(travel);
        TravelMessage.TravelCoordinate coordinate = new TravelMessage.TravelCoordinate(-5, 7);
        payloads.put(coordinate, new byte[]{1, 2, 3});
        ClientPortalRenderer renderer = mock(ClientPortalRenderer.class);
        try (MockedStatic<ClientSodiumTerrain> terrain = mockStatic(ClientSodiumTerrain.class);
             MockedStatic<ClientPortalRenderer> portals = mockStatic(ClientPortalRenderer.class)) {
            portals.when(ClientPortalRenderer::instance).thenReturn(renderer);
            travel.lightChanged(level, position);
            assertFalse(payloads.containsKey(coordinate));
            verify(scene).invalidateColumn(-5, 7);
            verify(scene).changedSection(section);
            verify(renderer).invalidateTravel(section);
            verify(renderer).invalidateTravel(dependent);
            terrain.verifyNoInteractions();
        }
    }
}
