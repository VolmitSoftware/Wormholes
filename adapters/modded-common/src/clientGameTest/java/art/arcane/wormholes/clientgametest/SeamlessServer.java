package art.arcane.wormholes.clientgametest;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;

interface SeamlessServer {
    SeamlessScenario.Route build(SeamlessScenario.RouteSpec spec);

    void approach(SeamlessScenario.Route route);

    void remove(SeamlessScenario.Route route);

    void lightNetherPortal(BlockPos frame);

    boolean netherPortalLit(BlockPos frame);

    List<Vec3> netherPortalCenters(BlockPos frame);

    void approachFrom(ResourceKey<Level> level, Vec3 position, float yaw);

    boolean chunkEverLoaded(ResourceKey<Level> level, int chunkX, int chunkZ);

    SeamlessFallLoop.Loop buildFallLoop();

    void removeFallLoop(SeamlessFallLoop.Loop loop);
}
