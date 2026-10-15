package art.arcane.wormholes.clientgametest;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class CommandSeamlessServer implements SeamlessServer {
    private static final int REPLY_TIMEOUT_TICKS = 200;

    private final DriverClient client;

    CommandSeamlessServer(DriverClient client) {
        this.client = client;
    }

    @Override
    public SeamlessScenario.Route build(SeamlessScenario.RouteSpec spec) {
        return RouteCodec.route(client.command("wormholesqa route " + RouteCodec.spec(spec), "route", REPLY_TIMEOUT_TICKS));
    }

    @Override
    public void approach(SeamlessScenario.Route route) {
        client.command("wormholesqa approach " + RouteCodec.approach(route), "approached", REPLY_TIMEOUT_TICKS);
    }

    @Override
    public void remove(SeamlessScenario.Route route) {
        client.command("wormholesqa remove " + route.source() + " " + route.destination(), "removed", REPLY_TIMEOUT_TICKS);
    }

    @Override
    public void lightNetherPortal(BlockPos frame) {
        client.command("wormholesqa light " + RouteCodec.position(frame), "lighting", REPLY_TIMEOUT_TICKS);
    }

    @Override
    public boolean netherPortalLit(BlockPos frame) {
        return Boolean.parseBoolean(client.command("wormholesqa lit " + RouteCodec.position(frame), "lit", REPLY_TIMEOUT_TICKS));
    }

    @Override
    public List<Vec3> netherPortalCenters(BlockPos frame) {
        String[] centers = client.command("wormholesqa centers " + RouteCodec.position(frame), "centers", REPLY_TIMEOUT_TICKS).trim().split(" ");
        List<Vec3> result = new ArrayList<>(centers.length);
        for (String center : centers) {
            String[] values = center.split(",");
            result.add(new Vec3(Double.parseDouble(values[0]), Double.parseDouble(values[1]), Double.parseDouble(values[2])));
        }
        return result;
    }

    @Override
    public SeamlessFallLoop.Loop buildFallLoop() {
        String[] lists = client.command("wormholesqa fallloop", "fallloop", REPLY_TIMEOUT_TICKS).trim().split(" ");
        return new SeamlessFallLoop.Loop(RouteCodec.ids(lists[0]), RouteCodec.ids(lists[1]));
    }

    @Override
    public void startFallLoop() {
        client.command("wormholesqa startfallloop", "fallloopstarted", REPLY_TIMEOUT_TICKS);
    }

    @Override
    public void releaseFallers(SeamlessFallLoop.Loop loop) {
        client.command("wormholesqa releasefallers " + RouteCodec.ids(loop.fallers()), "fallersreleased", REPLY_TIMEOUT_TICKS);
    }

    @Override
    public void removeFallLoop(SeamlessFallLoop.Loop loop) {
        client.command("wormholesqa remove " + String.join(" ", loop.portals().stream().map(UUID::toString).toList()), "removed",
            REPLY_TIMEOUT_TICKS);
    }

    @Override
    public List<UUID> buildStencilResize() {
        return RouteCodec.ids(client.command("wormholesqa stencilresize", "stencilresize", REPLY_TIMEOUT_TICKS).trim());
    }

    @Override
    public void removePortals(List<UUID> portals) {
        client.command("wormholesqa remove " + String.join(" ", portals.stream().map(UUID::toString).toList()), "removed", REPLY_TIMEOUT_TICKS);
    }

    @Override
    public PortalPairScene.Pair buildPortalPair(PortalPairScene.Spec spec) {
        return RouteCodec.pair(client.command("wormholesqa pair " + RouteCodec.pairSpec(spec), "pair", REPLY_TIMEOUT_TICKS));
    }

    @Override
    public void stance(PortalPairScene.Pair pair, PortalPairScene.Side side, PortalPairScene.Stance stance) {
        client.command("wormholesqa stance " + RouteCodec.pair(pair) + " " + side.name() + " " + stance.name(), "stance", REPLY_TIMEOUT_TICKS);
    }

    @Override
    public void fall() {
        client.command("wormholesqa fall", "fall", REPLY_TIMEOUT_TICKS);
    }

    @Override
    public String playerDimension() {
        return client.command("wormholesqa dimension", "dimension", REPLY_TIMEOUT_TICKS).trim();
    }

    @Override
    public boolean portalExists(UUID portal) {
        return Boolean.parseBoolean(client.command("wormholesqa exists " + portal, "exists", REPLY_TIMEOUT_TICKS).trim());
    }

    @Override
    public int playerGunpowder() {
        return Integer.parseInt(client.command("wormholesqa gunpowder", "gunpowder", REPLY_TIMEOUT_TICKS).trim());
    }

    @Override
    public void removePortalPair(PortalPairScene.Pair pair) {
        client.command("wormholesqa removepair " + RouteCodec.pair(pair), "pairremoved", REPLY_TIMEOUT_TICKS);
    }

    @Override
    public boolean chunkEverLoaded(ResourceKey<Level> level, int chunkX, int chunkZ) {
        return Boolean.parseBoolean(client.command("wormholesqa loaded " + RouteCodec.level(level) + " " + chunkX + " " + chunkZ, "loaded",
            REPLY_TIMEOUT_TICKS).trim());
    }

    @Override
    public Vec3 playerPosition() {
        String[] values = client.command("wormholesqa position", "position", REPLY_TIMEOUT_TICKS).trim().split(",");
        return new Vec3(Double.parseDouble(values[0]), Double.parseDouble(values[1]), Double.parseDouble(values[2]));
    }

    @Override
    public void approachFrom(ResourceKey<Level> level, Vec3 position, float yaw) {
        client.command("wormholesqa approachfrom " + RouteCodec.level(level) + " " + position.x + "," + position.y + "," + position.z + " " + yaw,
            "approached", REPLY_TIMEOUT_TICKS);
    }
}
