package art.arcane.wormholes.nexus;

import art.arcane.wormholes.config.toml.NexusConfig;
import art.arcane.wormholes.portal.LocalPortal;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Points a portal at another member of its network. Dialing is portal state: the swap goes through
 * the portal's ordinary destination path so every viewer sees the same destination.
 */
public final class Dialer {
    /** Applies the resolved member to the portal. Production wiring calls setDestination or linkRemote. */
    public interface TunnelApplier {
        boolean apply(LocalPortal portal, NetworkMember member);
    }

    public enum DialResult {
        DIALED,
        UNKNOWN_ADDRESS,
        NOT_ON_NETWORK,
        DEBOUNCED,
        MANAGED_PORTAL
    }

    private final NetworkRegistry registry;
    private final TunnelApplier applier;
    private final Supplier<NexusConfig> config;

    public Dialer(NetworkRegistry registry, TunnelApplier applier, Supplier<NexusConfig> config) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.applier = Objects.requireNonNull(applier, "applier");
        this.config = Objects.requireNonNull(config, "config");
    }

    public DialResult dial(LocalPortal portal, String address, UUID dialedBy, long nowMillis) {
        return dial(portal, address, dialedBy, nowMillis, true);
    }

    /** Scheduler-driven dials pass {@code debounce = false}; the debounce only guards player cycling. */
    public DialResult dial(LocalPortal portal, String address, UUID dialedBy, long nowMillis, boolean debounce) {
        NexusPortalExtension state = state(portal);
        if (state == null || state.networkId() == null) {
            return DialResult.NOT_ON_NETWORK;
        }
        if (portal.getDimensionalPortalKind().isManagedPortal() || portal.isMirrorMode()) {
            return DialResult.MANAGED_PORTAL;
        }
        PortalNetwork network = registry.byId(state.networkId());
        if (network == null) {
            return DialResult.NOT_ON_NETWORK;
        }
        NetworkMember member = network.memberAt(address);
        if (member == null || member.portalId().equals(portal.getId())) {
            return DialResult.UNKNOWN_ADDRESS;
        }
        if (debounce && state.dial().withinDebounce(nowMillis, config.get().dialDebounceMillis)) {
            return DialResult.DEBOUNCED;
        }
        if (!applier.apply(portal, member)) {
            return DialResult.MANAGED_PORTAL;
        }
        state.setDial(new DialState(member.address(), nowMillis, dialedBy, state.dial().sticky()));
        portal.save();
        return DialResult.DIALED;
    }

    /** Applies one destination-policy entry. Used by the scheduler for entries that are not addresses. */
    public DialResult applyEntry(LocalPortal portal, DestinationEntry entry, UUID dialedBy, long nowMillis) {
        NexusPortalExtension state = state(portal);
        if (state == null) {
            return DialResult.NOT_ON_NETWORK;
        }
        if (entry == null) {
            return DialResult.UNKNOWN_ADDRESS;
        }
        if (entry.kind() == DestinationEntry.TargetKind.ADDRESS) {
            return dial(portal, entry.target(), dialedBy, nowMillis, false);
        }
        if (portal.getDimensionalPortalKind().isManagedPortal() || portal.isMirrorMode()) {
            return DialResult.MANAGED_PORTAL;
        }
        NetworkMember member = registry.resolveEntry(state.networkId(), entry);
        if (member == null || member.portalId().equals(portal.getId())) {
            return DialResult.UNKNOWN_ADDRESS;
        }
        if (!applier.apply(portal, member)) {
            return DialResult.MANAGED_PORTAL;
        }
        state.setDial(new DialState(entry.target(), nowMillis, dialedBy, state.dial().sticky()));
        portal.save();
        return DialResult.DIALED;
    }

    /** Steps to the next or previous address on the portal's network, skipping the portal itself. */
    public DialResult next(LocalPortal portal, int delta, UUID dialedBy, long nowMillis) {
        NexusPortalExtension state = state(portal);
        if (state == null || state.networkId() == null) {
            return DialResult.NOT_ON_NETWORK;
        }
        PortalNetwork network = registry.byId(state.networkId());
        if (network == null) {
            return DialResult.NOT_ON_NETWORK;
        }
        List<NetworkMember> dialable = network.membersByAddress().stream()
                .filter(member -> !member.portalId().equals(portal.getId()))
                .toList();
        if (dialable.isEmpty()) {
            return DialResult.UNKNOWN_ADDRESS;
        }
        int step = delta >= 0 ? 1 : -1;
        int current = indexOfAddress(dialable, state.dial().currentAddress());
        int index = current < 0 ? (step > 0 ? 0 : dialable.size() - 1) : Math.floorMod(current + step, dialable.size());
        return dial(portal, dialable.get(index).address(), dialedBy, nowMillis);
    }

    /**
     * The address a portal is currently dialed to, empty when it carries no nexus state or is not
     * dialed. This is the accessor {@code RulesEnvironment.dialedAddress} reads, so a rule can match
     * on the address a portal is pointed at.
     */
    public static String dialedAddress(LocalPortal portal) {
        NexusPortalExtension state = state(portal);
        return state == null ? "" : state.dial().currentAddress();
    }

    /** The member a portal is currently pointed at, or null when it is not dialed to an address. */
    public NetworkMember currentTarget(LocalPortal portal) {
        NexusPortalExtension state = state(portal);
        if (state == null || state.networkId() == null || !state.dial().isDialed()) {
            return null;
        }
        PortalNetwork network = registry.byId(state.networkId());
        return network == null ? null : network.memberAt(state.dial().currentAddress());
    }

    private static int indexOfAddress(List<NetworkMember> members, String address) {
        if (address == null || address.isEmpty()) {
            return -1;
        }
        for (int index = 0; index < members.size(); index++) {
            if (members.get(index).address().equals(address)) {
                return index;
            }
        }
        return -1;
    }

    private static NexusPortalExtension state(LocalPortal portal) {
        return portal == null ? null : portal.extension(NexusPortalExtension.class);
    }
}
