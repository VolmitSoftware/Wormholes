package art.arcane.wormholes.modded.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

final class PreparedLevelExtractor extends LevelExtractor {
    PreparedLevelExtractor(Minecraft minecraft) {
        super(minecraft, new LevelRenderState(), minecraft.levelRenderer);
    }

    @Override
    public void setLevel(ClientLevel level) {
    }

    @Override
    public void allChanged() {
    }

    @Override
    public void blockChanged(BlockPos position, int flags) {
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
}
