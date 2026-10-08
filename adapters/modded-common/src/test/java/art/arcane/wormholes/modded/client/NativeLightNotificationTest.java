package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.modded.mixin.client.LocalMeshChunkMixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class NativeLightNotificationTest extends MinecraftTestBase {
    @Test
    @SuppressWarnings("unchecked")
    public void lightGeometryIsRebuiltByTheRendererThatOwnsTheLevel() throws Exception {
        Minecraft minecraft = mock(Minecraft.class);
        ClientLevel active = mock(ClientLevel.class);
        ClientLevel resident = mock(ClientLevel.class);
        ClientLevel unrendered = mock(ClientLevel.class);
        minecraft.level = active;
        LevelExtractor activeExtractor = mock(LevelExtractor.class);
        LevelExtractor residentExtractor = mock(LevelExtractor.class);
        extractor(minecraft, activeExtractor);
        LocalMeshChunkMixin callback = mock(LocalMeshChunkMixin.class, CALLS_REAL_METHODS);
        Field owner = LocalMeshChunkMixin.class.getDeclaredField("level");
        owner.setAccessible(true);
        Operation<Void> activeOriginal = mock(Operation.class);
        Operation<Void> residentOriginal = mock(Operation.class);
        Operation<Void> unrenderedOriginal = mock(Operation.class);
        Method update = LocalMeshChunkMixin.class.getDeclaredMethod("wormholesLightGeometry", LevelExtractor.class,
            int.class, int.class, int.class, Operation.class);
        update.setAccessible(true);
        try (MockedStatic<Minecraft> clients = mockStatic(Minecraft.class);
             MockedStatic<ClientWorldLoader> worlds = mockStatic(ClientWorldLoader.class)) {
            clients.when(Minecraft::getInstance).thenReturn(minecraft);
            worlds.when(() -> ClientWorldLoader.residentRenderer(resident)).thenReturn(mock(LevelRenderer.class));
            worlds.when(() -> ClientWorldLoader.withWorldRenderer(eq(resident), any())).thenAnswer(call -> {
                extractor(minecraft, residentExtractor);
                try {
                    call.<Runnable>getArgument(1).run();
                } finally {
                    extractor(minecraft, activeExtractor);
                }
                return null;
            });
            owner.set(callback, active);
            update.invoke(callback, activeExtractor, -5, -4, 7, activeOriginal);
            verify(activeOriginal).call(activeExtractor, -5, -4, 7);
            owner.set(callback, resident);
            update.invoke(callback, activeExtractor, -5, -4, 7, residentOriginal);
            verify(residentOriginal).call(residentExtractor, -5, -4, 7);
            verify(residentOriginal, never()).call(activeExtractor, -5, -4, 7);
            owner.set(callback, unrendered);
            update.invoke(callback, activeExtractor, -5, -4, 7, unrenderedOriginal);
            verify(unrenderedOriginal, never()).call(any(LevelExtractor.class), anyInt(), anyInt(), anyInt());
        }
    }

    private static void extractor(Minecraft minecraft, LevelExtractor extractor) throws ReflectiveOperationException {
        Field field = Minecraft.class.getDeclaredField("levelExtractor");
        field.setAccessible(true);
        field.set(minecraft, extractor);
    }
}
