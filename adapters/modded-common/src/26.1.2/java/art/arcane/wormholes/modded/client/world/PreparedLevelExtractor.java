package art.arcane.wormholes.modded.client.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

public final class PreparedLevelExtractor extends LevelRenderer {
    public PreparedLevelExtractor(Minecraft minecraft) {
        super(minecraft, minecraft.getEntityRenderDispatcher(), minecraft.getBlockEntityRenderDispatcher(), minecraft.renderBuffers(),
            new GameRenderState(), minecraft.gameRenderer.getFeatureRenderDispatcher());
    }

    @Override
    public void setLevel(ClientLevel level) {
    }

    @Override
    public void allChanged() {
    }

    @Override
    public void blockChanged(BlockGetter level, BlockPos position, BlockState previous, BlockState current, int flags) {
    }

    @Override
    public void setBlockDirty(BlockPos position, BlockState previous, BlockState current) {
    }

    @Override
    public void setBlocksDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    @Override
    public void setSectionDirtyWithNeighbors(int x, int y, int z) {
    }

    @Override
    public void setSectionRangeDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    @Override
    public void setSectionDirty(int x, int y, int z) {
    }

    @Override
    public void onSectionBecomingNonEmpty(long section) {
    }

    @Override
    public void destroyBlockProgress(int breaker, BlockPos position, int progress) {
    }
}
