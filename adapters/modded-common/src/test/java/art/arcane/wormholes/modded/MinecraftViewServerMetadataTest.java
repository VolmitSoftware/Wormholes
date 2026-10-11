package art.arcane.wormholes.modded;

import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.math.CellKeys;
import art.arcane.wormholes.network.replication.capture.RegionalDiffAccumulator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftViewServerMetadataTest extends MinecraftTestBase {
    @Test
    @SuppressWarnings("unchecked")
    public void metadataOnlyCapturePublishesTheSanitizedSampleWithoutAnInventedBlockChange() throws Exception {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftViewServer service = mock(MinecraftViewServer.class, CALLS_REAL_METHODS);
        MinecraftCaptureAccess access = mock(MinecraftCaptureAccess.class);
        RegionalDiffAccumulator<ServerLevel, BlockState> capture = mock(RegionalDiffAccumulator.class);
        ServerLevel world = mock(ServerLevel.class);
        BlockPos position = new BlockPos(18, 65, -7);
        BlockEntitySample sample = new BlockEntitySample("minecraft:sign", new byte[] {10, 0, 0, 0});
        MinecraftProjectionWorldView view = mock(MinecraftProjectionWorldView.class);
        when(view.sampleBlockEntity(18, 65, -7)).thenReturn(sample);
        when(access.block(world, 18, 65, -7)).thenReturn(Blocks.OAK_SIGN.defaultBlockState());
        when(capture.isRelevant(world, CellKeys.chunkKey(1, -1))).thenReturn(true);
        Class<?> sessionType = Class.forName(MinecraftViewServer.class.getName() + "$Session");
        Object session = mock(sessionType);
        set(session, "level", world);
        set(session, "view", view);
        set(session, "box", BlockBox.spanning(16, 64, -16, 31, 80, -1));
        set(service, "runtime", runtime);
        set(service, "captureAccess", access);
        set(service, "capture", capture);
        set(service, "sessions", Map.of(UUID.randomUUID(), session));

        service.metadataChanged(world, position);

        ArgumentCaptor<byte[]> encoded = ArgumentCaptor.forClass(byte[].class);
        verify(runtime).requireServerThread();
        verify(capture).recordBlockEntityChange(eq(world), eq(18), eq(65), eq(-7), encoded.capture());
        assertEquals(sample, BlockEntitySample.decode(encoded.getValue()));
        verify(capture, never()).recordBlockChange(any(), anyInt(), anyInt(), anyInt(), any(), anyByte());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void irrelevantOrRetiredMetadataDoesNotReadWorldState() throws Exception {
        MinecraftViewServer service = mock(MinecraftViewServer.class, CALLS_REAL_METHODS);
        MinecraftCaptureAccess access = mock(MinecraftCaptureAccess.class);
        RegionalDiffAccumulator<ServerLevel, BlockState> capture = mock(RegionalDiffAccumulator.class);
        ServerLevel world = mock(ServerLevel.class);
        set(service, "runtime", mock(WormholesModRuntime.class));
        set(service, "captureAccess", access);
        set(service, "capture", capture);

        service.metadataChanged(world, new BlockPos(0, 64, 0));
        set(service, "closed", true);
        service.metadataChanged(world, new BlockPos(0, 64, 0));

        verify(access, never()).block(any(), anyInt(), anyInt(), anyInt());
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Class<?> type = target instanceof MinecraftViewServer ? MinecraftViewServer.class : Class.forName(MinecraftViewServer.class.getName() + "$Session");
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
