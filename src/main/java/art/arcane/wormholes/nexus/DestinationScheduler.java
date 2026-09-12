package art.arcane.wormholes.nexus;

import art.arcane.wormholes.config.toml.NexusConfig;
import art.arcane.wormholes.portal.LocalPortal;

import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * Global task that keeps schedule-driven portals pointed at the right destination and lets a manual
 * dial expire. Only portals carrying nexus state are visited, so a server without networks pays one
 * empty loop per tick window.
 */
public final class DestinationScheduler {
    private final NetworkRegistry registry;
    private final Dialer dialer;
    private final Supplier<List<LocalPortal>> portals;
    private final ToLongFunction<LocalPortal> worldTime;
    private final Supplier<NexusConfig> config;
    private final Random random = new Random();

    public DestinationScheduler(NetworkRegistry registry, Dialer dialer, Supplier<List<LocalPortal>> portals,
                                ToLongFunction<LocalPortal> worldTime, Supplier<NexusConfig> config) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.dialer = Objects.requireNonNull(dialer, "dialer");
        this.portals = Objects.requireNonNull(portals, "portals");
        this.worldTime = Objects.requireNonNull(worldTime, "worldTime");
        this.config = Objects.requireNonNull(config, "config");
    }

    public void tick(long nowMillis) {
        long holdMillis = Math.max(0, config.get().dialHoldSeconds) * 1000L;
        for (LocalPortal portal : portals.get()) {
            NexusPortalExtension state = portal == null ? null : portal.extension(NexusPortalExtension.class);
            if (state == null || state.networkId() == null) {
                continue;
            }
            if (applyScheduledDestination(portal, state, nowMillis)) {
                continue;
            }
            expireManualDial(portal, state, nowMillis, holdMillis);
        }
    }

    private boolean applyScheduledDestination(LocalPortal portal, NexusPortalExtension state, long nowMillis) {
        DestinationPolicy policy = state.policy();
        if (!policy.isScheduleDriven()) {
            return false;
        }
        DestinationEntry due = policy.choose(worldTime.applyAsLong(portal), null, true, false, random);
        if (due == null) {
            return true;
        }
        if (!NetworkMember.normalizeAddress(due.target()).equals(state.dial().currentAddress())) {
            dialer.applyEntry(portal, due, null, nowMillis);
        }
        return true;
    }

    /**
     * A manual dial that nobody renewed falls back to the network hub. Without a hub the dial simply
     * stops being manual, so an operator's explicit link is never thrown away.
     */
    private void expireManualDial(LocalPortal portal, NexusPortalExtension state, long nowMillis, long holdMillis) {
        DialState dial = state.dial();
        if (!dial.heldPast(nowMillis, holdMillis)) {
            return;
        }
        String hub = hubAddress(state, portal);
        if (hub != null && !hub.equals(dial.currentAddress())) {
            dialer.dial(portal, hub, null, nowMillis, false);
            return;
        }
        state.setDial(new DialState(dial.currentAddress(), 0L, dial.dialedBy(), dial.sticky()));
    }

    private String hubAddress(NexusPortalExtension state, LocalPortal portal) {
        PortalNetwork network = registry.byId(state.networkId());
        if (network == null || network.hubPortalId() == null || network.hubPortalId().equals(portal.getId())) {
            return null;
        }
        NetworkMember hub = network.member(network.hubPortalId());
        return hub == null ? null : hub.address();
    }
}
