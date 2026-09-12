package art.arcane.wormholes.nexus;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.hook.DestinationResolver;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.TunnelType;
import art.arcane.wormholes.util.Direction;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.function.ToLongFunction;

/**
 * Chooses a per-traveler destination at traversal time for policies the scheduler cannot bake into
 * the projected tunnel: per-player, return, entry-side, sneak, weighted, and random selection.
 * Every other portal keeps the tunnel it already has.
 */
public final class NexusDestinationResolver implements DestinationResolver {
    public static final int ORDER = 200;

    /** Builds the tunnel for a resolved destination. Production wiring goes through {@link ITunnel#createTunnel}. */
    public interface TunnelFactory {
        ITunnel create(LocalPortal source, NetworkMember member);
    }

    private final NetworkRegistry registry;
    private final ReturnAddresses returns;
    private final TunnelFactory tunnels;
    private final ToLongFunction<LocalPortal> worldTime;
    private final Random random = new Random();

    public NexusDestinationResolver(NetworkRegistry registry, ReturnAddresses returns, TunnelFactory tunnels,
                                    ToLongFunction<LocalPortal> worldTime) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.returns = Objects.requireNonNull(returns, "returns");
        this.tunnels = Objects.requireNonNull(tunnels, "tunnels");
        this.worldTime = Objects.requireNonNull(worldTime, "worldTime");
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public ITunnel resolve(LocalPortal portal, Entity traveler, ITunnel current) {
        NexusPortalExtension state = portal == null ? null : portal.extension(NexusPortalExtension.class);
        if (state == null || traveler == null) {
            return current;
        }
        DestinationPolicy policy = state.policy();
        if (!policy.isActive() || !policy.isPerTraveler()) {
            return current;
        }

        NetworkMember member = policy.mode() == DestinationMode.RETURN
                ? returnMember(traveler)
                : chosenMember(portal, state, policy, traveler);
        if (member == null || member.portalId().equals(portal.getId())) {
            return current;
        }
        ITunnel tunnel = state.cachedTunnel(cacheKey(member), () -> tunnels.create(portal, member));
        return tunnel == null ? current : tunnel;
    }

    private NetworkMember returnMember(Entity traveler) {
        UUID source = returns.sourceFor(traveler.getUniqueId());
        return source == null ? null : new NetworkMember(source, "", "", 0L, null);
    }

    private NetworkMember chosenMember(LocalPortal portal, NexusPortalExtension state, DestinationPolicy policy,
                                       Entity traveler) {
        DestinationEntry entry = policy.choose(worldTime.applyAsLong(portal), traveler.getUniqueId(),
                isFrontSide(portal, traveler.getLocation().toVector()),
                traveler instanceof Player player && player.isSneaking(), random);
        return entry == null ? null : registry.resolveEntry(state.networkId(), entry);
    }

    /**
     * Which side of the frame plane a point sits on. The resolver runs before {@code canUseTunnel},
     * so there is no traversive yet and the side comes from the entity position.
     */
    public static boolean isFrontSide(LocalPortal portal, Vector position) {
        Direction normal = portal.getFrame().getNormal();
        Vector origin = portal.getOrigin();
        double dot = (position.getX() - origin.getX()) * normal.x()
                + (position.getY() - origin.getY()) * normal.y()
                + (position.getZ() - origin.getZ()) * normal.z();
        return dot >= 0.0D;
    }

    private static String cacheKey(NetworkMember member) {
        return member.isLocal() ? member.portalId().toString() : member.serverName() + "/" + member.portalId();
    }

    /** Builds real tunnels through the public {@link ITunnel#createTunnel} factory. */
    public static final class BukkitTunnels implements TunnelFactory {
        @Override
        public ITunnel create(LocalPortal source, NetworkMember member) {
            JSONObject json = new JSONObject();
            json.put("destination", member.portalId().toString());
            if (!member.isLocal()) {
                json.put("type", TunnelType.UNIVERSAL.name());
                json.put("server", member.serverName());
                return ITunnel.createTunnel(json);
            }
            ILocalPortal destination = Wormholes.portalManager == null
                    ? null : Wormholes.portalManager.getLocalPortal(member.portalId());
            if (destination == null || destination.getStructure() == null || destination.getStructure().getWorld() == null) {
                return null;
            }
            boolean sameWorld = destination.getStructure().getWorld().equals(source.getStructure().getWorld());
            json.put("type", sameWorld ? TunnelType.LOCAL.name() : TunnelType.DIMENSIONAL.name());
            return ITunnel.createTunnel(json);
        }
    }
}
