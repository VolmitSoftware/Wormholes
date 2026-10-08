package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;
import art.arcane.wormholes.modded.mixin.client.LocalMeshChunkMixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
}
