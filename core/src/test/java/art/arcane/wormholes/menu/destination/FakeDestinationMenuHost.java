package art.arcane.wormholes.menu.destination;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import art.arcane.wormholes.menu.MenuViewer;

final class FakeDestinationMenuHost implements DestinationMenuHost {
    final List<DestinationMenuView> candidates = new ArrayList<>();
    final List<DestinationMenuView> portals = new ArrayList<>();
    final List<String> calls = new ArrayList<>();
    DestinationMenuView localDestination;
    PairOutcome pairOutcome = PairOutcome.PAIRED;

    @Override
    public List<DestinationMenuView> candidates(UUID portalId) {
        return candidates;
    }

    @Override
    public List<DestinationMenuView> portals() {
        return portals;
    }

    @Override
    public Optional<DestinationMenuView> localDestination(UUID portalId) {
        return Optional.ofNullable(localDestination);
    }

    @Override
    public void link(MenuViewer viewer, UUID portalId, DestinationMenuView target) {
        calls.add("link:" + target.name() + (target.remote() ? "@" + target.server() : ""));
    }

    @Override
    public void unlink(MenuViewer viewer, UUID portalId) {
        calls.add("unlink");
    }

    @Override
    public PairOutcome pair(MenuViewer viewer, UUID portalId, UUID targetId) {
        calls.add("pair:" + targetId);
        return pairOutcome;
    }
}
