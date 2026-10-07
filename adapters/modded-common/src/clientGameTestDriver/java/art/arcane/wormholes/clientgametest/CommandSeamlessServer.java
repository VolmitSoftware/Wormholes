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
    public void removeFallLoop(SeamlessFallLoop.Loop loop) {
        client.command("wormholesqa remove " + String.join(" ", loop.portals().stream().map(UUID::toString).toList()), "removed",
            REPLY_TIMEOUT_TICKS);
    }

    @Override
    public void approachFrom(ResourceKey<Level> level, Vec3 position, float yaw) {
        client.command("wormholesqa approachfrom " + RouteCodec.level(level) + " " + position.x + "," + position.y + "," + position.z + " " + yaw,
            "approached", REPLY_TIMEOUT_TICKS);
    }
}
