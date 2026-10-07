package art.arcane.wormholes.clientgametest;

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
}
