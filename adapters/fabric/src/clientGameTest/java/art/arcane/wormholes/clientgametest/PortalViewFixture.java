package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class PortalViewFixture {
    private static final int TIMEOUT_TICKS = 1200;

    private PortalViewFixture() {
    }

    static boolean drawsView(Minecraft minecraft, String destination) {
        WormholesClient wormholes = WormholesClient.instance();
        if (wormholes == null || minecraft.level == null) {
            return false;
        }
        for (PortalView view : wormholes.portalViews().current()) {
            if (view.source() == minecraft.level && view.kind() == PortalView.Kind.ARM
                && view.destination().dimension().identifier().toString().equals(destination)) {
                return true;
            }
        }
        return false;
    }

    static void cross(FabricSeamlessClient client) {
        int departure = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player.level()));
        client.holdForward();
        try {
            client.waitFor(minecraft -> System.identityHashCode(minecraft.player.level()) != departure, TIMEOUT_TICKS);
        } finally {
            client.releaseForward();
        }
    }

    static void netherFrame(ServerLevel level, BlockPos min) {
        fill(level, min.offset(-8, -1, -8), 18, 1, 18, Blocks.STONE.defaultBlockState());
        fill(level, min.offset(-8, 0, -8), 18, 8, 18, Blocks.AIR.defaultBlockState());
        for (int x = -1; x <= 2; x++) {
            level.setBlockAndUpdate(min.offset(x, -1, 0), Blocks.OBSIDIAN.defaultBlockState());
            level.setBlockAndUpdate(min.offset(x, 3, 0), Blocks.OBSIDIAN.defaultBlockState());
        }
        for (int y = 0; y < 3; y++) {
            level.setBlockAndUpdate(min.offset(-1, y, 0), Blocks.OBSIDIAN.defaultBlockState());
            level.setBlockAndUpdate(min.offset(2, y, 0), Blocks.OBSIDIAN.defaultBlockState());
        }
    }

    static void fill(ServerLevel level, BlockPos origin, int width, int height, int depth, BlockState state) {
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    level.setBlockAndUpdate(origin.offset(x, y, z), state);
                }
            }
        }
    }
}
