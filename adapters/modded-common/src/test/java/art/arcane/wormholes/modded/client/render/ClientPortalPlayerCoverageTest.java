package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.util.Direction;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import com.mojang.blaze3d.pipeline.TextureTarget;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class ClientPortalPlayerCoverageTest extends MinecraftTestBase {
    @Test
    @SuppressWarnings("unchecked")
    public void realLocalPlayerRequiresCurrentCoverWorldNativeChunkAndCleanSection() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        Minecraft minecraft = mock(Minecraft.class);
        ClientLevel level = mock(ClientLevel.class);
        LocalPlayer player = mock(LocalPlayer.class);
        ClientChunkCache chunks = mock(ClientChunkCache.class);
        LevelChunk chunk = mock(LevelChunk.class);
        BlockPos position = new BlockPos(1103, 80, 0);
        long key = SectionPos.asLong(position);
        ClientTravelScene scene = mock(ClientTravelScene.class);
        ClientPortalGeometry geometry = mock(ClientPortalGeometry.class);
        when(geometry.valid()).thenReturn(true);
        when(geometry.facingDirection()).thenReturn(Direction.S);
        when(geometry.apertureWidth()).thenReturn(1);
        when(geometry.apertureHeight()).thenReturn(1);
        when(geometry.apertureMask()).thenReturn(new long[]{1});
        when(scene.geometry()).thenReturn(geometry);
        when(scene.inLevel(level)).thenReturn(true);
        when(scene.revision(key)).thenReturn(39L);
        when(player.level()).thenReturn(level);
        when(player.blockPosition()).thenReturn(position);
        when(level.getChunkSource()).thenReturn(chunks);
        when(chunks.getChunk(68, 0, ChunkStatus.FULL, false)).thenReturn(chunk);
        set(minecraft, "player", player);
        set(minecraft, "level", level);
        renderer.prepareTravel(scene, new CameraRenderState());
        renderer.transitionTravel(true);
        Object cover = get(renderer, "travel");
        set(cover, "rendered", true);
        set(cover, "target", mock(TextureTarget.class, RETURNS_DEEP_STUBS));
        Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
        Constructor<?> constructor = sectionType.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        Object section = constructor.newInstance(key, 39L);
        Long2ObjectOpenHashMap<Object> sections = (Long2ObjectOpenHashMap<Object>) get(cover, "sections");
        sections.put(key, section);
        LongSet dirty = (LongSet) get(cover, "dirty");
        LongSet building = (LongSet) get(cover, "building");
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            assertTrue(renderer.coversLocalPlayer(player));
            assertFalse(renderer.coversLocalPlayer(mock(Entity.class)));
            when(scene.inLevel(level)).thenReturn(false);
            assertFalse(renderer.coversLocalPlayer(player));
            when(scene.inLevel(level)).thenReturn(true);
            when(chunks.getChunk(68, 0, ChunkStatus.FULL, false)).thenReturn(null);
            assertFalse(renderer.coversLocalPlayer(player));
            when(chunks.getChunk(68, 0, ChunkStatus.FULL, false)).thenReturn(chunk);
            dirty.add(key);
            assertFalse(renderer.coversLocalPlayer(player));
            dirty.clear();
            building.add(key);
            assertFalse(renderer.coversLocalPlayer(player));
            building.clear();
            when(scene.revision(key)).thenReturn(-1L);
            assertFalse(renderer.coversLocalPlayer(player));
            when(scene.revision(key)).thenReturn(40L);
            assertFalse(renderer.coversLocalPlayer(player));
            when(scene.revision(key)).thenReturn(39L);
            set(cover, "rendered", false);
            assertFalse(renderer.coversLocalPlayer(player));
            set(cover, "rendered", true);
            set(cover, "target", null);
            assertFalse(renderer.coversLocalPlayer(player));
        } finally {
            renderer.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void terrainTransfersOnlyItsCurrentCleanNativeBuiltSection() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        Minecraft minecraft = mock(Minecraft.class);
        ClientLevel level = mock(ClientLevel.class);
        ClientChunkCache chunks = mock(ClientChunkCache.class);
        LevelChunk chunk = mock(LevelChunk.class);
        LevelRenderer main = mock(LevelRenderer.class);
        ClientTravelScene scene = mock(ClientTravelScene.class);
        ClientPortalGeometry geometry = mock(ClientPortalGeometry.class);
        long key = SectionPos.asLong(68, 5, 0);
        when(geometry.valid()).thenReturn(true);
        when(geometry.facingDirection()).thenReturn(Direction.S);
        when(geometry.apertureWidth()).thenReturn(1);
        when(geometry.apertureHeight()).thenReturn(1);
        when(geometry.apertureMask()).thenReturn(new long[]{1});
        when(scene.geometry()).thenReturn(geometry);
        when(scene.inLevel(level)).thenReturn(true);
        when(scene.revision(key)).thenReturn(39L);
        when(level.getChunkSource()).thenReturn(chunks);
        when(chunks.getChunk(68, 0, ChunkStatus.FULL, false)).thenReturn(chunk);
        set(minecraft, "level", level);
        set(minecraft, "levelRenderer", main);
        renderer.prepareTravel(scene, new CameraRenderState());
        Object cover = get(renderer, "travel");
        CameraRenderState colorCamera = new CameraRenderState();
        set(cover, "camera", colorCamera);
        set(renderer, "camera", colorCamera);
        set(cover, "target", mock(TextureTarget.class, RETURNS_DEEP_STUBS));
        Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
        Constructor<?> constructor = sectionType.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        Object section = constructor.newInstance(key, 39L);
        Long2ObjectOpenHashMap<Object> sections = (Long2ObjectOpenHashMap<Object>) get(cover, "sections");
        sections.put(key, section);
        Method transfer = ClientPortalRenderer.class.getDeclaredMethod("mainOwnsTravelSection", cover.getClass(), long.class);
        transfer.setAccessible(true);
        LongSet dirty = (LongSet) get(cover, "dirty");
        LongSet building = (LongSet) get(cover, "building");
        BlockPos center = new BlockPos(1096, 88, 8);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            assertFalse(renderer.coversMainSection(key));
            renderer.transitionTravel(true);
            assertTrue(renderer.coversMainSection(key));
            assertFalse((boolean) transfer.invoke(renderer, cover, key));
            when(main.isSectionCompiledAndVisible(center, 0)).thenReturn(true);
            assertTrue((boolean) transfer.invoke(renderer, cover, key));
            CameraRenderState shadowCamera = new CameraRenderState();
            set(cover, "camera", colorCamera);
            set(renderer, "camera", shadowCamera);
            assertFalse((boolean) transfer.invoke(renderer, cover, key));
            set(renderer, "camera", colorCamera);
            assertTrue((boolean) transfer.invoke(renderer, cover, key));
            assertFalse((boolean) transfer.invoke(renderer, mock(cover.getClass()), key));
            dirty.add(key);
            assertFalse((boolean) transfer.invoke(renderer, cover, key));
            dirty.clear();
            building.add(key);
            assertFalse((boolean) transfer.invoke(renderer, cover, key));
            building.clear();
            when(scene.revision(key)).thenReturn(40L);
            assertFalse((boolean) transfer.invoke(renderer, cover, key));
            when(scene.revision(key)).thenReturn(39L);
            when(chunks.getChunk(68, 0, ChunkStatus.FULL, false)).thenReturn(null);
            assertFalse((boolean) transfer.invoke(renderer, cover, key));
            when(chunks.getChunk(68, 0, ChunkStatus.FULL, false)).thenReturn(chunk);
            when(scene.inLevel(level)).thenReturn(false);
            assertFalse((boolean) transfer.invoke(renderer, cover, key));
            when(scene.inLevel(level)).thenReturn(true);
            set(cover, "target", null);
            assertFalse((boolean) transfer.invoke(renderer, cover, key));
        } finally {
            renderer.clear();
        }
    }

    private static Object get(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Class<?> owner = target.getClass();
        while (owner != null) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException failure) {
                owner = owner.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
