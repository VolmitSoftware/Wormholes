package art.arcane.wormholes.modded.client;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClientTravelSectionStateTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void captureKeepsImmutableOldGeometryLightAndEntityInputsAcrossNativeReplacement() {
        ClientLevel level = mock(ClientLevel.class);
        ClientChunkCache cache = mock(ClientChunkCache.class);
        LevelChunk chunk = mock(LevelChunk.class);
        LevelChunkSection section = mock(LevelChunkSection.class);
        LevelLightEngine engine = mock(LevelLightEngine.class);
        LayerLightEventListener skyListener = mock(LayerLightEventListener.class);
        LayerLightEventListener blockListener = mock(LayerLightEventListener.class);
        AtomicInteger content = new AtomicInteger(21);
        when(level.getChunkSource()).thenReturn(cache);
        when(cache.getChunk(0, 0, ChunkStatus.FULL, false)).thenReturn(chunk);
        when(chunk.getSectionsCount()).thenReturn(16);
        when(chunk.getSectionIndex(80)).thenReturn(5);
        when(chunk.getSection(5)).thenReturn(section);
        when(section.getSerializedSize()).thenReturn(4);
        doAnswer(invocation -> {
            FriendlyByteBuf buffer = invocation.getArgument(0);
            buffer.writeInt(content.get());
            return null;
        }).when(section).write(any(FriendlyByteBuf.class));
        when(level.getLightEngine()).thenReturn(engine);
        when(engine.getLayerListener(LightLayer.SKY)).thenReturn(skyListener);
        when(engine.getLayerListener(LightLayer.BLOCK)).thenReturn(blockListener);
        DataLayer sky = new DataLayer(15);
        when(skyListener.getDataLayerData(any())).thenReturn(sky);
        CompoundTag tag = new CompoundTag();
        tag.putInt("value", 1);
        BlockEntity entity = mock(BlockEntity.class);
        when(entity.getUpdateTag(any())).thenReturn(tag);
        BlockPos position = new BlockPos(0, 80, 0);
        when(chunk.getBlockEntities()).thenReturn(Map.of(position, entity));
        ClientTravelSectionState old = ClientTravelSectionState.capture(level, 0, 5, 0);
        assertTrue(old.same(ClientTravelSectionState.capture(level, 0, 5, 0)));
        content.set(22);
        assertFalse(old.same(ClientTravelSectionState.capture(level, 0, 5, 0)));
        content.set(21);
        sky.set(0, 0, 0, 9);
        assertFalse(old.same(ClientTravelSectionState.capture(level, 0, 5, 0)));
        assertEquals(255, old.sky()[0] & 255);
        tag.putInt("value", 2);
        assertEquals(1, old.entities().get(position).getInt("value").orElseThrow().intValue());
    }

    @Test
    public void identicalSectionBytesKeepContentIdentityButBlockAndBiomeChangesDoNot() {
        ClientTravelSectionState old = new ClientTravelSectionState(new byte[]{1, 2, 3}, null, null, Map.of());
        assertTrue(old.same(new ClientTravelSectionState(new byte[]{1, 2, 3}, null, null, Map.of())));
        assertFalse(old.same(new ClientTravelSectionState(new byte[]{4, 2, 3}, null, null, Map.of())));
        assertFalse(old.same(new ClientTravelSectionState(new byte[]{1, 2, 4}, null, null, Map.of())));
        assertFalse(old.same(new ClientTravelSectionState(null, null, null, Map.of())));
    }

    @Test
    public void skyBlockLightAndBlockEntityDataChangesInvalidateContent() {
        CompoundTag first = new CompoundTag();
        first.putInt("value", 1);
        CompoundTag second = new CompoundTag();
        second.putInt("value", 2);
        ClientTravelSectionState old = new ClientTravelSectionState(new byte[]{1}, new byte[]{15}, new byte[]{0}, Map.of(BlockPos.ZERO, first));
        assertTrue(old.same(new ClientTravelSectionState(new byte[]{1}, new byte[]{15}, new byte[]{0}, Map.of(BlockPos.ZERO, first.copy()))));
        assertFalse(old.same(new ClientTravelSectionState(new byte[]{1}, new byte[]{14}, new byte[]{0}, Map.of(BlockPos.ZERO, first))));
        assertFalse(old.same(new ClientTravelSectionState(new byte[]{1}, new byte[]{15}, new byte[]{1}, Map.of(BlockPos.ZERO, first))));
        assertFalse(old.same(new ClientTravelSectionState(new byte[]{1}, new byte[]{15}, new byte[]{0}, Map.of(BlockPos.ZERO, second))));
    }
}
