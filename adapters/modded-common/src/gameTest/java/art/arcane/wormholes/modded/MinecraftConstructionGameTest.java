package art.arcane.wormholes.modded;

import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.PortalType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

public final class MinecraftConstructionGameTest {
    private final GameTestHelper helper;
    private final WormholesModRuntime runtime = WormholesGameTests.RUNTIME;
    private final List<UUID> created = new ArrayList<>();
    private final ServerLevel level;
    private MinecraftGameTestPlayer connection;
    private AutoCloseable permissions;
    private final BlockPos origin = new BlockPos(64, 80, 64);

    public MinecraftConstructionGameTest(GameTestHelper helper) {
        this.helper = helper;
        level = helper.getLevel();
    }

    public CompletableFuture<Boolean> run() {
        try {
            connection = MinecraftGameTestPlayer.connect(runtime, level, "construction");
            ServerPlayer player = connection.player();
            player.setGameMode(GameType.CREATIVE);
            player.setPos(Vec3.atCenterOf(origin.offset(0, 0, 5)));
            permissions = runtime.access().register((actor, node) -> actor == player ? MinecraftAccessService.Decision.ALLOW : MinecraftAccessService.Decision.UNSET);
            runes(player);
            return nether(player).thenComposeAsync(ignored -> end(player), runtime.server())
                .thenApplyAsync(ignored -> {
                    LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS construction rune_placement rune_claim_guard rune_build vanilla_ignition nether_pair frame_cleanup end_pair return_path");
                    return true;
                }, runtime.server()).whenCompleteAsync((result, failure) -> close(), runtime.server());
        } catch (Throwable failure) {
            close();
            return CompletableFuture.failedFuture(failure);
        }
    }

    private void runes(ServerPlayer player) throws Exception {
        BlockPos first = origin.offset(0, 0, 8);
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 3; y++) {
                BlockPos cell = first.offset(x, y, 0);
                level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                BlockPos support = cell.south();
                level.setBlock(support, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                player.setItemInHand(InteractionHand.MAIN_HAND, MinecraftPortalItems.of(runtime).rune(PortalType.PORTAL));
                helper.assertTrue(runtime.useBlock(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(support).add(0, 0, -0.5), Direction.NORTH, support, false)), "Rune placement was not consumed");
                helper.assertTrue(level.getBlockState(cell).is(Blocks.PRISMARINE), "Rune item did not place its block");
            }
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, MinecraftPortalItems.of(runtime).wand());
        try (AutoCloseable denied = runtime.access().registerPlacement(placement -> placement.kind() != MinecraftAccessService.PlacementKind.RUNE)) {
            helper.assertTrue(runtime.attackBlock(player, first), "Rune activation was not consumed");
            helper.assertTrue(runtime.portals().at(level, first) == null && level.getBlockState(first).is(Blocks.PRISMARINE), "Denied rune placement consumed blocks");
        }
        helper.assertTrue(runtime.attackBlock(player, first), "Rune activation was not routed");
        MinecraftPortal portal = runtime.portals().at(level, first);
        helper.assertTrue(portal != null && portal.getGeometry().getBlockPositions().size() == 6, "Rune activation did not preserve aperture cells");
        helper.assertTrue(level.getBlockState(first).isAir(), "Rune activation retained physical blocks");
        created.add(portal.getId());
        runtime.portals().remove(portal.getId());
    }

    private CompletableFuture<Boolean> nether(ServerPlayer player) {
        BlockPos first = origin;
        for (int x = -1; x <= 2; x++) {
            for (int y = -1; y <= 3; y++) {
                level.setBlock(first.offset(x, y, 0), x == -1 || x == 2 || y == -1 || y == 3
                    ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL));
        BlockPos floor = first.below();
        player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false));
        return until(() -> runtime.portals().at(level, first) != null, "Nether ignition replacement", 400).thenComposeAsync(ignored -> {
            MinecraftPortal source = runtime.portals().at(level, first);
            MinecraftPortal target = runtime.portals().get(source.getCounterpartId());
            helper.assertTrue(source.getDimensionalKind() == DimensionalPortalKind.NETHER && target != null
                && target.getWorldKey().equals(Level.NETHER.identifier().toString()), "Nether counterpart was not created");
            helper.assertTrue(source.getId().equals(target.getDestinationId()) && target.getId().equals(source.getDestinationId()), "Nether pair is not bidirectional");
            helper.assertTrue(level.getBlockState(first).isAir(), "Native Nether portal blocks were retained");
            created.add(source.getId());
            created.add(target.getId());
            level.setBlock(first.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            return until(() -> runtime.portals().get(source.getId()) == null && runtime.portals().get(target.getId()) == null,
                "damaged frame pair cleanup", 80);
        }, runtime.server());
    }

    private CompletableFuture<Boolean> end(ServerPlayer player) {
        BlockPos first = origin.offset(0, 0, 16);
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                level.setBlock(first.offset(x, 0, z), Blocks.END_PORTAL.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        for (int n = 0; n < 3; n++) {
            frame(first.offset(n, 0, -1), Direction.SOUTH);
            frame(first.offset(n, 0, 3), Direction.NORTH);
            frame(first.offset(-1, 0, n), Direction.EAST);
            frame(first.offset(3, 0, n), Direction.WEST);
        }
        return runtime.construction().vanilla().replace(player, level, first).thenApplyAsync(replaced -> {
            helper.assertTrue(replaced, "End source was not replaced");
            MinecraftPortal source = runtime.portals().at(level, first);
            MinecraftPortal target = runtime.portals().get(source.getCounterpartId());
            helper.assertTrue(source.getDimensionalKind() == DimensionalPortalKind.END_SOURCE && target != null
                && target.getDimensionalKind() == DimensionalPortalKind.END_ARRIVAL, "End pair kinds were lost");
            helper.assertTrue(source.getId().equals(target.getDestinationId()) && source.isIncomingTraversalsEnabled()
                && target.isOutgoingTraversalsEnabled(), "End arrival has no return path");
            created.add(source.getId());
            created.add(target.getId());
            return true;
        }, runtime.server());
    }

    private void frame(BlockPos position, Direction facing) {
        BlockState state = Blocks.END_PORTAL_FRAME.defaultBlockState().setValue(EndPortalFrameBlock.FACING, facing)
            .setValue(EndPortalFrameBlock.HAS_EYE, true);
        level.setBlock(position, state, Block.UPDATE_CLIENTS);
    }

    private CompletableFuture<Boolean> until(BooleanSupplier condition, String description, int remaining) {
        if (condition.getAsBoolean()) {
            return CompletableFuture.completedFuture(true);
        }
        if (remaining <= 0) {
            return CompletableFuture.failedFuture(new AssertionError("Construction timed out: " + description));
        }
        CompletableFuture<Boolean> next = new CompletableFuture<>();
        runtime.schedule(() -> next.complete(true), 1);
        return next.thenCompose(ignored -> until(condition, description, remaining - 1));
    }

    private void close() {
        for (UUID id : created) {
            runtime.portals().remove(id);
        }
        try {
            if (permissions != null) {
                permissions.close();
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Could not remove construction test permissions", failure);
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }
}
