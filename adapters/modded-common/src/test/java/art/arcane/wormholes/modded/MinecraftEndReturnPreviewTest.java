package art.arcane.wormholes.modded;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.modded.mixin.ServerPlayerRespawnInvoker;
import art.arcane.wormholes.modded.mixin.EndRespawnPositionAccessor;
import org.mockito.invocation.InvocationOnMock;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftEndReturnPreviewTest extends MinecraftTestBase {
    @Test
    public void observerPreviewUsesItsValidatedRespawnWithoutConsumingAnAnchorAndKeepsTheRouteStable() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerPlayer observer = mock(ServerPlayer.class);
        ServerLevel fallback = mock(ServerLevel.class);
        ServerLevel target = mock(ServerLevel.class);
        when(runtime.server()).thenReturn(server);
        when(server.findRespawnDimension()).thenReturn(fallback);
        when(server.getLevel(Level.NETHER)).thenReturn(target);
        when(fallback.getRespawnData()).thenReturn(LevelData.RespawnData.of(Level.OVERWORLD, BlockPos.ZERO, 0, 0));
        when(target.dimension()).thenReturn(Level.NETHER);
        when(target.hasChunk(anyInt(), anyInt())).thenReturn(true);
        when(target.getBlockState(any(BlockPos.class))).thenReturn(Blocks.RESPAWN_ANCHOR.defaultBlockState()
            .setValue(RespawnAnchorBlock.CHARGE, 3));
        when(observer.getUUID()).thenReturn(UUID.randomUUID());
        ServerPlayer.RespawnConfig first = new ServerPlayer.RespawnConfig(
            LevelData.RespawnData.of(Level.NETHER, new BlockPos(64, 70, 64), 0, 0), false);
        when(observer.getRespawnConfig()).thenReturn(first);
        MinecraftPortal exit = MinecraftEndPortalTest.portal("minecraft:the_end", 0, 64, 0);
        try (MockedStatic<RespawnAnchorBlock> anchors = mockStatic(RespawnAnchorBlock.class);
             MockedStatic<ServerPlayerRespawnInvoker> invoker = mockStatic(ServerPlayerRespawnInvoker.class, MinecraftEndReturnPreviewTest::invokeRespawn)) {
            anchors.when(() -> RespawnAnchorBlock.canSetSpawn(target, first.respawnData().pos())).thenReturn(true);
            anchors.when(() -> RespawnAnchorBlock.findStandUpPosition(EntityTypes.PLAYER, target, first.respawnData().pos()))
                .thenReturn(Optional.of(new Vec3(65.5, 70, 64.5)));
            MinecraftEndReturnPreview previews = new MinecraftEndReturnPreview(runtime);
            MinecraftPortal destination = previews.destination(observer, exit);
            assertEquals("minecraft:the_nether", destination.getWorldKey());
            assertEquals(new GeometryVector(65.5, 70, 64.5), destination.getOrigin());
            when(server.getTickCount()).thenReturn(20);
            assertSame(destination, previews.destination(observer, exit));
            anchors.verify(() -> RespawnAnchorBlock.findStandUpPosition(EntityTypes.PLAYER, target, first.respawnData().pos()), times(1));
            verify(target, never()).setBlockAndUpdate(any(), any());
            ServerPlayer.RespawnConfig changed = new ServerPlayer.RespawnConfig(
                LevelData.RespawnData.of(Level.NETHER, new BlockPos(80, 70, 80), 0, 0), true);
            when(observer.getRespawnConfig()).thenReturn(changed);
            anchors.when(() -> RespawnAnchorBlock.canSetSpawn(target, changed.respawnData().pos())).thenReturn(true);
            anchors.when(() -> RespawnAnchorBlock.findStandUpPosition(EntityTypes.PLAYER, target, changed.respawnData().pos()))
                .thenReturn(Optional.of(new Vec3(80.5, 70, 80.5)));
            when(server.getTickCount()).thenReturn(40);
            MinecraftPortal replacement = previews.destination(observer, exit);
            assertNotSame(destination, replacement);
            assertEquals(destination.getId(), replacement.getId());
            assertEquals(new GeometryVector(80.5, 70, 80.5), replacement.getOrigin());
            anchors.verify(() -> RespawnAnchorBlock.findStandUpPosition(EntityTypes.PLAYER, target, changed.respawnData().pos()));
            verify(target, never()).setBlockAndUpdate(any(), any());
        }
    }

    private static Object invokeRespawn(InvocationOnMock invocation) throws ReflectiveOperationException {
        Method validation = ServerPlayer.class.getDeclaredMethod("findRespawnAndUseSpawnBlock", ServerLevel.class,
            ServerPlayer.RespawnConfig.class, boolean.class);
        validation.setAccessible(true);
        Optional<?> positions = (Optional<?>) validation.invoke(null, invocation.getArguments());
        if (positions.isEmpty()) {
            return Optional.empty();
        }
        Object position = positions.get();
        Method accessor = position.getClass().getDeclaredMethod("position");
        accessor.setAccessible(true);
        Vec3 point = (Vec3) accessor.invoke(position);
        EndRespawnPositionAccessor result = mock(EndRespawnPositionAccessor.class);
        when(result.wormholes$position()).thenReturn(point);
        return Optional.of(result);
    }

    @Test
    public void failedSharedSpawnResolutionRetriesWithoutChangingTheResolvedRoute() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerPlayer observer = mock(ServerPlayer.class);
        ServerLevel fallback = mock(ServerLevel.class);
        when(runtime.server()).thenReturn(server);
        when(runtime.running()).thenReturn(true);
        when(server.findRespawnDimension()).thenReturn(fallback);
        when(fallback.dimension()).thenReturn(Level.OVERWORLD);
        when(fallback.hasChunk(anyInt(), anyInt())).thenReturn(true);
        when(fallback.getRespawnData()).thenReturn(LevelData.RespawnData.of(Level.OVERWORLD, BlockPos.ZERO, 0, 0));
        when(observer.getUUID()).thenReturn(UUID.randomUUID());
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(server).execute(any(Runnable.class));
        MinecraftPortal exit = MinecraftEndPortalTest.portal("minecraft:the_end", 0, 64, 0);
        try (MockedStatic<PlayerSpawnFinder> spawns = mockStatic(PlayerSpawnFinder.class)) {
            spawns.when(() -> PlayerSpawnFinder.findSpawn(fallback, BlockPos.ZERO)).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("temporary spawn failure")),
                CompletableFuture.completedFuture(new Vec3(0.5, 64, 0.5)));
            MinecraftEndReturnPreview previews = new MinecraftEndReturnPreview(runtime);
            assertNull(previews.destination(observer, exit));
            when(server.getTickCount()).thenReturn(20);
            MinecraftPortal destination = previews.destination(observer, exit);
            assertEquals(new GeometryVector(0.5, 64, 0.5), destination.getOrigin());
            when(fallback.hasChunk(anyInt(), anyInt())).thenReturn(false);
            when(server.getTickCount()).thenReturn(40);
            assertSame(destination, previews.destination(observer, exit));
            spawns.verify(() -> PlayerSpawnFinder.findSpawn(fallback, BlockPos.ZERO), times(2));
        }
    }
}
