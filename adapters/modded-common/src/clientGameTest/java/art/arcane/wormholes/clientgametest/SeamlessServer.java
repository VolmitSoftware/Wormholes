package art.arcane.wormholes.clientgametest;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

interface SeamlessServer {
    SeamlessScenario.Route build(SeamlessScenario.RouteSpec spec);

    void approach(SeamlessScenario.Route route);

    void remove(SeamlessScenario.Route route);

    void lightNetherPortal(BlockPos frame);

    boolean netherPortalLit(BlockPos frame);

    List<Vec3> netherPortalCenters(BlockPos frame);

    void approachFrom(ResourceKey<Level> level, Vec3 position, float yaw);

    Vec3 playerPosition();

    boolean chunkEverLoaded(ResourceKey<Level> level, int chunkX, int chunkZ);

    SeamlessFallLoop.Loop buildFallLoop();

    void startFallLoop();

    void releaseFallers(SeamlessFallLoop.Loop loop);

    void removeFallLoop(SeamlessFallLoop.Loop loop);

    List<UUID> buildStencilResize();

    void removePortals(List<UUID> portals);

    PortalPairScene.Pair buildPortalPair(PortalPairScene.Spec spec);

    void stance(PortalPairScene.Pair pair, PortalPairScene.Side side, PortalPairScene.Stance stance);

    void fall();

    String playerDimension();

    boolean portalExists(UUID portal);

    int playerGunpowder();

    void removePortalPair(PortalPairScene.Pair pair);
}
