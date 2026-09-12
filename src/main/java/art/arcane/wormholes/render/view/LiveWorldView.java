package art.arcane.wormholes.render.view;

import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.render.blockentity.BlockEntityCapturer;
import art.arcane.wormholes.render.blockentity.BlockEntityMaterials;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;

public final class LiveWorldView implements ProjectionWorldView {
    private final World world;
    private final BlockData sharedAir;

    public LiveWorldView(World world) {
        this.world = world;
        this.sharedAir = Material.AIR.createBlockData();
    }

    @Override
    public World getWorld() {
        return world;
    }

    @Override
    public int getMinHeight() {
        return world.getMinHeight();
    }

    @Override
    public int getMaxHeight() {
        return world.getMaxHeight();
    }

    @Override
    public BlockData sampleBlockData(int x, int y, int z) {
        if (y < world.getMinHeight() || y > world.getMaxHeight() - 1) {
            return null;
        }
        BlockData data = world.getBlockData(x, y, z);
        return ProjectionWorldView.isAir(data.getMaterial()) ? sharedAir : data;
    }

    @Override
    public Material sampleMaterial(int x, int y, int z) {
        if (y < world.getMinHeight() || y > world.getMaxHeight() - 1) {
            return null;
        }
        return world.getType(x, y, z);
    }

    @Override
    public String sampleBiome(int x, int y, int z) {
        if (y < world.getMinHeight() || y > world.getMaxHeight() - 1) {
            return null;
        }
        return WormholesPlatform.keyString(world.getBiome(x, y, z).getKey());
    }

    @Override
    public BlockEntitySample sampleBlockEntity(int x, int y, int z) {
        if (y < world.getMinHeight() || y > world.getMaxHeight() - 1) {
            return null;
        }
        Block block = world.getBlockAt(x, y, z);
        if (!BlockEntityMaterials.isCandidate(block.getType())) {
            return null;
        }
        BlockState state;
        try {
            state = WormholesPlatform.blockState(block, true);
        } catch (RuntimeException unavailable) {
            return null;
        }
        return BlockEntityCapturer.capture(state);
    }

    @Override
    public int getLight(int x, int y, int z) {
        if (y < world.getMinHeight() || y > world.getMaxHeight() - 1) {
            return LIGHT_UNAVAILABLE;
        }
        Block block = world.getBlockAt(x, y, z);
        return ProjectionWorldView.packLight(block.getLightFromSky(), block.getLightFromBlocks());
    }

    @Override
    public int getSkyDarken() {
        return ProjectionWorldView.computeSkyDarken(world.getTime(), world.hasStorm(), world.isThundering());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LiveWorldView view)) {
            return false;
        }
        return world.equals(view.world);
    }

    @Override
    public int hashCode() {
        return world.hashCode();
    }
}
