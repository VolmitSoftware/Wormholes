package art.arcane.wormholes.clientgametest;

interface SeamlessServer {
    SeamlessScenario.Route build(SeamlessScenario.RouteSpec spec);

    void approach(SeamlessScenario.Route route);

    void remove(SeamlessScenario.Route route);
}
