package art.arcane.wormholes.modded.client.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The renderer a client level holds while no real {@link LevelRenderer} is bound to it. Every level-facing call is
 * dropped, so one shared instance serves all such levels and is never closed.
 */
public final class PreparedLevelExtractor extends LevelRenderer {
    private static PreparedLevelExtractor shared;

    private PreparedLevelExtractor(Minecraft minecraft) {
        super(minecraft, minecraft.getEntityRenderDispatcher(), minecraft.getBlockEntityRenderDispatcher(), minecraft.renderBuffers());
    }

    /** The shared detached renderer; created on the render thread on first use. */
    public static PreparedLevelExtractor shared() {
        if (shared == null) {
            shared = new PreparedLevelExtractor(Minecraft.getInstance());
        }
        return shared;
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
    }

    @Override
    public void setLevel(ClientLevel level) {
    }

    @Override
    public void graphicsChanged() {
    }

    @Override
    public void allChanged() {
    }

    @Override
    public void resize(int width, int height) {
    }

    @Override
    public void tick() {
    }

    @Override
    public void needsUpdate() {
    }

    @Override
    public void close() {
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
    public void setSectionDirty(int x, int y, int z) {
    }

    @Override
    public void playStreamingMusic(SoundEvent sound, BlockPos position) {
    }

    @Override
    public void addParticle(ParticleOptions options, boolean force, double x, double y, double z, double dx, double dy, double dz) {
    }

    @Override
    public void addParticle(ParticleOptions options, boolean force, boolean decreased, double x, double y, double z, double dx, double dy,
                            double dz) {
    }

    @Override
    public void globalLevelEvent(int event, BlockPos position, int data) {
    }

    @Override
    public void levelEvent(int event, BlockPos position, int data) {
    }

    @Override
    public void destroyBlockProgress(int breaker, BlockPos position, int progress) {
    }
}
