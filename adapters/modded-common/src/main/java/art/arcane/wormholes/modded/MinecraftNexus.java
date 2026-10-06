package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.toml.NexusConfig;
import art.arcane.wormholes.nexus.AddressAllocator;
import art.arcane.wormholes.nexus.DestinationEntry;
import art.arcane.wormholes.nexus.DestinationMode;
import art.arcane.wormholes.nexus.DestinationPolicy;
import art.arcane.wormholes.nexus.DialState;
import art.arcane.wormholes.nexus.FrameIo;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.nexus.NetworkRegistry;
import art.arcane.wormholes.nexus.NetworkRole;
import art.arcane.wormholes.nexus.PortalNetwork;
import art.arcane.wormholes.nexus.RedstoneIoIndex;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.wormholes.portal.PortalStateCodec;
import art.arcane.wormholes.portal.PortalType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import art.arcane.optics.math.Box;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class MinecraftNexus implements AutoCloseable {
    private static final Map<MinecraftServer, MinecraftNexus> SERVICES = new HashMap<>();

    private final WormholesModRuntime runtime;
    private final MinecraftNexusMenus menus;
    private final RedstoneIoIndex controls = new RedstoneIoIndex();
    private final Map<UUID, State> states = new LinkedHashMap<>();
    private final Map<UUID, ReturnAddress> returns = new HashMap<>();
    private final Map<UUID, ArrayDeque<Long>> departures = new HashMap<>();
    private final Map<UUID, Boolean> powered = new HashMap<>();
    private final Random random = new Random();
    private NetworkRegistry networks;
    private int ticks;

    public MinecraftNexus(WormholesModRuntime runtime) {
        this.runtime = runtime;
        menus = new MinecraftNexusMenus(runtime);
    }

    public void start() {
        runtime.requireServerThread();
        networks = new NetworkRegistry(runtime.server().getServerDirectory().resolve("config/wormholes/atlas/networks"), MinecraftJsonDocuments.INSTANCE);
        networks.load();
        SERVICES.put(runtime.server(), this);
        for (MinecraftPortal portal : runtime.portals().snapshot()) {
            changed(portal);
        }
    }

    public static MinecraftNexus forServer(MinecraftServer server) {
        return SERVICES.get(server);
    }

    public MinecraftNexusMenus menus() {
        return menus;
    }

    public NetworkRegistry networks() {
        return networks;
    }

    public PortalNetwork create(ServerPlayer actor, String name) {
        if (name.isBlank() || networks.byName(name) != null) {
            throw new IllegalArgumentException("Network name must be unique and nonempty");
        }
        if (!administrator(actor) && networks.ownedBy(actor.getUUID()).size() >= config().maxNetworksPerPlayer) {
            throw new IllegalArgumentException("Network ownership limit reached");
        }
        PortalNetwork network = PortalNetwork.create(UUID.randomUUID(), name, actor.getUUID());
        save(network);
        return network;
    }

    public void save(PortalNetwork network) {
        try {
            networks.save(network);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not save portal network " + network.id(), failure);
        }
    }

    public void delete(ServerPlayer actor, PortalNetwork network) {
        requireManage(actor, network);
        try {
            networks.delete(network.id());
            for (NetworkMember member : network.members().values()) {
                MinecraftPortal portal = runtime.portals().get(member.portalId());
                if (portal != null) {
                    portal.setNexusValue("networkId", null);
                    portal.setNexusValue("address", null);
                    portal.setNexusValue("dial", null);
                    runtime.portals().save(portal);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not delete portal network " + network.id(), failure);
        }
    }

    public void join(ServerPlayer actor, PortalNetwork network, MinecraftPortal portal, String requestedAddress) {
        requireManage(actor, network);
        if (!runtime.portals().canManage(actor, portal) || portal.isManaged() || portal.isMirrorMode() || portal.getType() == PortalType.RTP) {
            throw new IllegalArgumentException("Portal cannot join this network");
        }
        PortalNetwork previous = networks.memberOf(portal.getId());
        if (previous != null && !previous.id().equals(network.id())) {
            throw new IllegalArgumentException("Portal already belongs to a network");
        }
        if (network.member(portal.getId()) == null && network.members().size() >= config().maxMembersPerNetwork) {
            throw new IllegalArgumentException("Network member limit reached");
        }
        String address = requestedAddress == null || requestedAddress.isBlank()
            ? AddressAllocator.next(network, config().addressAlphabet, config().addressLength, random)
            : NetworkMember.normalizeAddress(requestedAddress);
        if (!AddressAllocator.isValid(address, config().addressAlphabet)
            || network.usesAddress(address) && !portal.getId().equals(network.portalIdAt(address))) {
            throw new IllegalArgumentException("Address is invalid or already assigned");
        }
        save(network.withMember(portal.getId(), new NetworkMember(portal.getId(), address, portal.getName(), System.currentTimeMillis(), null)));
        portal.setNexusValue("networkId", network.id().toString());
        portal.setNexusValue("address", address);
        portal.setNexusValue("label", portal.getName());
        runtime.portals().save(portal);
    }

    public void leave(ServerPlayer actor, MinecraftPortal portal) {
        PortalNetwork network = networks.memberOf(portal.getId());
        requireManage(actor, network);
        if (!runtime.portals().canManage(actor, portal)) {
            throw new IllegalArgumentException("Portal access denied");
        }
        save(network.withoutMember(portal.getId()));
        portal.setNexusValue("networkId", null);
        portal.setNexusValue("address", null);
        portal.setNexusValue("dial", null);
        runtime.portals().save(portal);
    }

    public boolean dial(ServerPlayer actor, MinecraftPortal portal, String address) {
        PortalNetwork network = networks.memberOf(portal.getId());
        if (network == null || actor != null && (!runtime.portals().canDepart(actor, portal)
            || !network.visibleTo(actor.getUUID(), administrator(actor)))) {
            return false;
        }
        State state = state(portal);
        if (state.dial().withinDebounce(System.currentTimeMillis(), config().dialDebounceMillis)) {
            return false;
        }
        NetworkMember target = network.memberAt(address);
        return apply(portal, target, actor == null ? null : actor.getUUID(), address);
    }

    public boolean next(ServerPlayer actor, MinecraftPortal portal, int direction) {
        PortalNetwork network = networks.memberOf(portal.getId());
        if (network == null) {
            return false;
        }
        List<NetworkMember> candidates = network.membersByAddress().stream().filter(member -> !member.portalId().equals(portal.getId())).toList();
        if (candidates.isEmpty()) {
            return false;
        }
        int current = -1;
        for (int index = 0; index < candidates.size(); index++) {
            if (candidates.get(index).address().equals(state(portal).dial().currentAddress())) {
                current = index;
                break;
            }
        }
        int step = direction >= 0 ? 1 : -1;
        int next = current < 0 ? (step > 0 ? 0 : candidates.size() - 1) : Math.floorMod(current + step, candidates.size());
        return dial(actor, portal, candidates.get(next).address());
    }

    public void pair(ServerPlayer actor, MinecraftPortal first, MinecraftPortal second, boolean paired) {
        requirePortal(actor, first);
        requirePortal(actor, second);
        if (first == second) {
            throw new IllegalArgumentException("Portals must differ");
        }
        if (paired) {
            first.link(second);
            second.link(first);
        } else if (first.getId().equals(second.getDestinationId())) {
            second.unlink();
        }
        first.setNexusValue("reciprocal", paired);
        second.setNexusValue("reciprocal", paired);
        runtime.portals().save(first);
        runtime.portals().save(second);
    }

    public void policy(ServerPlayer actor, MinecraftPortal portal, DestinationPolicy policy) {
        requirePortal(actor, portal);
        portal.setNexusValue("policy", policy.toMap());
        runtime.portals().save(portal);
    }

    public void wire(ServerPlayer actor, MinecraftPortal portal, FrameIo io) {
        requirePortal(actor, portal);
        portal.setNexusValue("frameIo", io.toMap());
        runtime.portals().save(portal);
    }

    public void sticky(ServerPlayer actor, MinecraftPortal portal, boolean enabled) {
        requirePortal(actor, portal);
        portal.setNexusValue("dial", state(portal).dial().withSticky(enabled).toMap());
        runtime.portals().save(portal);
    }

    public DestinationPolicy policy(MinecraftPortal portal) {
        return state(portal).policy();
    }

    public String dialedAddress(MinecraftPortal portal) {
        return state(portal).dial().currentAddress();
    }

    public boolean perTraveler(MinecraftPortal portal) {
        DestinationPolicy policy = state(portal).policy();
        return policy.isActive() && policy.isPerTraveler();
    }

    public NetworkMember destination(MinecraftPortal portal, Entity traveler, PlaneCrossing crossing) {
        State state = state(portal);
        DestinationPolicy policy = state.policy();
        if (policy.isActive() && policy.isPerTraveler()) {
            NetworkMember selected;
            if (policy.mode() == DestinationMode.RETURN) {
                ReturnAddress address = returns.get(traveler.getUUID());
                selected = address == null || System.currentTimeMillis() - address.atMillis() >= 600_000L ? null
                    : new NetworkMember(address.portalId(), "", "", 0, null);
            } else {
                ServerLevel level = runtime.portals().resolveLevel(portal);
                DestinationEntry entry = policy.choose(level.getDefaultClockTime(), traveler.getUUID(), crossing.frontSide(),
                    traveler instanceof ServerPlayer player && player.isShiftKeyDown(), random);
                selected = networks.resolveEntry(state.networkId(), entry);
            }
            if (selected != null && !selected.portalId().equals(portal.getId())) {
                return selected;
            }
        }
        return portal.getDestinationId() == null ? null : new NetworkMember(portal.getDestinationId(), "", "", 0, portal.getDestinationServer());
    }

    public void arrived(Entity entity, MinecraftPortal source) {
        returns.put(entity.getUUID(), new ReturnAddress(source.getId(), System.currentTimeMillis()));
        if (state(source).io().comparator() == FrameIo.ComparatorOutput.TRAVERSALS) {
            ArrayDeque<Long> stamps = departures.computeIfAbsent(source.getId(), ignored -> new ArrayDeque<>(16));
            stamps.addLast(System.currentTimeMillis());
            while (stamps.size() > 16) {
                stamps.removeFirst();
            }
        }
    }

    public void changed(MinecraftPortal portal) {
        if (networks == null) {
            return;
        }
        State state = read(portal);
        states.put(portal.getId(), state);
        controls.remove(portal.getId());
        if (state.io().isWired()) {
            BlockPos control = control(portal, state.io());
            controls.put(portal.getId(), worldId(portal), control.getX(), control.getY(), control.getZ());
        }
    }

    public void removed(MinecraftPortal portal) {
        if (Boolean.TRUE.equals(portal.setting("nexus.reciprocal"))) {
            MinecraftPortal counterpart = runtime.portals().get(portal.getDestinationId());
            if (counterpart != null && portal.getId().equals(counterpart.getDestinationId())) {
                counterpart.unlink();
                counterpart.setNexusValue("reciprocal", false);
                runtime.portals().save(counterpart);
            }
        }
        states.remove(portal.getId());
        controls.remove(portal.getId());
        departures.remove(portal.getId());
        powered.remove(portal.getId());
        if (networks != null) {
            PortalNetwork network = networks.memberOf(portal.getId());
            if (network != null) {
                save(network.withoutMember(portal.getId()));
            }
        }
    }

    public void tick() {
        if (networks == null) {
            return;
        }
        if (++ticks % Math.max(1, config().schedulerIntervalTicks) == 0) {
            long now = System.currentTimeMillis();
            returns.values().removeIf(address -> now - address.atMillis() >= 600_000L);
            for (UUID id : List.copyOf(states.keySet())) {
                MinecraftPortal portal = runtime.portals().get(id);
                if (portal != null) {
                    schedule(portal, now);
                    output(portal, now);
                }
            }
        }
    }

    public void blockChanged(ServerLevel level, BlockPos position) {
        if (!config().redstoneEnabled || controls.size() == 0) {
            return;
        }
        UUID world = UUID.nameUUIDFromBytes(level.dimension().identifier().toString().getBytes(StandardCharsets.UTF_8));
        signal(level, world, position);
        for (Direction direction : Direction.values()) {
            signal(level, world, position.relative(direction));
        }
    }

    private void signal(ServerLevel level, UUID world, BlockPos position) {
        UUID id = controls.portalAt(world, position.getX(), position.getY(), position.getZ());
        if (id == null) {
            return;
        }
        MinecraftPortal portal = runtime.portals().get(id);
        if (portal == null) {
            return;
        }
        BlockState block = level.getBlockState(position);
        boolean signal = level.hasNeighborSignal(position) || block.hasProperty(RedstoneWireBlock.POWER)
            && block.getValue(RedstoneWireBlock.POWER) > 0;
        Boolean previous = powered.put(id, signal);
        if (signal && !Boolean.TRUE.equals(previous)) {
            runtime.schedule(() -> redstone(portal, state(portal).io().action()), 1L);
        }
    }

    public boolean useBlock(ServerPlayer player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND || !player.isShiftKeyDown() || !player.getMainHandItem().isEmpty()) {
            return false;
        }
        MinecraftPortal portal = gesturePortal(player, hit.getLocation().x, hit.getLocation().y, hit.getLocation().z);
        if (portal == null) {
            return false;
        }
        menus.open(player, portal);
        return true;
    }

    public boolean scroll(ServerPlayer player, int nextSlot) {
        if (!player.isShiftKeyDown() || nextSlot < 0 || nextSlot > 8) {
            return false;
        }
        MinecraftPortal portal = gesturePortal(player, player.getX(), player.getY(), player.getZ());
        if (portal == null) {
            return false;
        }
        int forward = Math.floorMod(nextSlot - player.getInventory().getSelectedSlot(), 9);
        next(player, portal, forward <= 4 ? 1 : -1);
        return true;
    }

    private MinecraftPortal gesturePortal(ServerPlayer player, double x, double y, double z) {
        for (UUID id : states.keySet()) {
            MinecraftPortal portal = runtime.portals().get(id);
            if (portal == null || runtime.portals().resolveLevel(portal) != player.level() || runtime.portals().canManage(player, portal)
                || !runtime.portals().canDepart(player, portal)) {
                continue;
            }
            PortalNetwork network = networks.memberOf(id);
            Box area = portal.getGeometry().captureZone(runtime.configuration().settings().getRender().captureZoneRadius);
            if (network != null && network.visibleTo(player.getUUID(), administrator(player)) && area.containsPrimitive(x, y, z)) {
                return portal;
            }
        }
        return null;
    }

    public boolean administrator(ServerPlayer actor) {
        return runtime.access().administrator(actor) || runtime.access().permission(actor, "wormholes.admin.nexus");
    }

    public void requireManage(ServerPlayer actor, PortalNetwork network) {
        if (network == null || !administrator(actor) && !actor.getUUID().equals(network.ownerId())
            && network.role(actor.getUUID()) != NetworkRole.MANAGER) {
            throw new IllegalArgumentException("Network management access denied");
        }
    }

    @Override
    public void close() {
        SERVICES.values().removeIf(service -> service == this);
        for (UUID id : states.keySet()) {
            controls.remove(id);
        }
        states.clear();
        departures.clear();
        powered.clear();
        returns.clear();
        networks = null;
    }

    private boolean apply(MinecraftPortal portal, NetworkMember target, UUID actor, String address) {
        if (target == null || target.portalId().equals(portal.getId()) || portal.isManaged() || portal.isMirrorMode()
            || portal.getType() == PortalType.RTP) {
            return false;
        }
        if (target.isLocal()) {
            MinecraftPortal destination = runtime.portals().get(target.portalId());
            if (destination == null || destination.getType() == PortalType.RTP) {
                return false;
            }
            portal.link(destination);
        } else if (!portal.linkRemote(target.serverName(), target.portalId())) {
            return false;
        }
        portal.setNexusValue("dial", new DialState(address, System.currentTimeMillis(), actor, state(portal).dial().sticky()).toMap());
        runtime.portals().save(portal);
        return true;
    }

    private void schedule(MinecraftPortal portal, long now) {
        State state = state(portal);
        if (state.policy().isScheduleDriven()) {
            ServerLevel level = runtime.portals().resolveLevel(portal);
            if (level == null) {
                return;
            }
            DestinationEntry selected = state.policy().choose(level.getDefaultClockTime(), null, true, false, random);
            if (selected != null && !NetworkMember.normalizeAddress(selected.target()).equals(state.dial().currentAddress())) {
                apply(portal, networks.resolveEntry(state.networkId(), selected), null, selected.target());
            }
        } else if (state.dial().heldPast(now, Math.max(0, config().dialHoldSeconds) * 1000L)) {
            PortalNetwork network = networks.byId(state.networkId());
            NetworkMember hub = network == null ? null : network.member(network.hubPortalId());
            if (hub == null || hub.portalId().equals(portal.getId()) || hub.address().equals(state.dial().currentAddress())) {
                portal.setNexusValue("dial", new DialState(state.dial().currentAddress(), 0L, state.dial().dialedBy(), state.dial().sticky()).toMap());
                runtime.portals().save(portal);
            } else {
                apply(portal, hub, null, hub.address());
            }
        }
    }

    private void output(MinecraftPortal portal, long now) {
        FrameIo io = state(portal).io();
        if (!config().redstoneEnabled || io.comparator() == FrameIo.ComparatorOutput.NONE) {
            return;
        }
        ServerLevel level = runtime.portals().resolveLevel(portal);
        BlockPos position = control(portal, io);
        if (level == null || !level.hasChunkAt(position)) {
            return;
        }
        ArrayDeque<Long> stamps = departures.get(portal.getId());
        while (stamps != null && !stamps.isEmpty() && now - stamps.getFirst() >= 60_000L) {
            stamps.removeFirst();
        }
        int signal = io.comparator() == FrameIo.ComparatorOutput.STATE ? (portal.isOpen() ? 15 : 0)
            : stamps == null ? 0 : Math.min(15, stamps.size());
        BlockState block = level.getBlockState(position);
        if (block.getBlock() instanceof RedstoneWireBlock && block.getValue(RedstoneWireBlock.POWER) != signal) {
            level.setBlock(position, block.setValue(RedstoneWireBlock.POWER, signal), 2);
        }
    }

    private void redstone(MinecraftPortal portal, FrameIo.RedstoneAction action) {
        switch (action) {
            case NONE -> { }
            case DIAL_NEXT -> next(null, portal, 1);
            case DIAL_PREV -> next(null, portal, -1);
            case OPEN, CLOSE -> {
                portal.setIncomingTraversalsEnabled(action == FrameIo.RedstoneAction.OPEN);
                portal.setOutgoingTraversalsEnabled(action == FrameIo.RedstoneAction.OPEN);
                runtime.portals().save(portal);
            }
            case LOCK -> {
                portal.setOutgoingTraversalsEnabled(false);
                runtime.portals().save(portal);
            }
        }
    }

    private void requirePortal(ServerPlayer actor, MinecraftPortal portal) {
        if (!runtime.portals().canManage(actor, portal) || portal.isManaged() || portal.isMirrorMode() || portal.getType() == PortalType.RTP) {
            throw new IllegalArgumentException("Portal management access denied");
        }
    }

    private State state(MinecraftPortal portal) {
        return states.computeIfAbsent(portal.getId(), ignored -> read(portal));
    }

    private static State read(MinecraftPortal portal) {
        String network = portal.setting("nexus.networkId") instanceof String id ? id : "";
        return new State(network.isBlank() ? null : UUID.fromString(network), DialState.fromMap(document(portal, "dial")),
            DestinationPolicy.fromMap(document(portal, "policy")), FrameIo.fromMap(document(portal, "frameIo")));
    }

    private static Map<String, Object> document(MinecraftPortal portal, String key) {
        Object value = portal.setting("nexus." + key);
        return value instanceof Map<?, ?> ? PortalStateCodec.object(Map.of("value", value), "value") : null;
    }

    private static BlockPos control(MinecraftPortal portal, FrameIo io) {
        return BlockPos.containing(portal.getOrigin().x(), portal.getOrigin().y(), portal.getOrigin().z()).offset(io.offsetX(), io.offsetY(), io.offsetZ());
    }

    private static UUID worldId(MinecraftPortal portal) {
        return UUID.nameUUIDFromBytes(portal.getWorldKey().getBytes(StandardCharsets.UTF_8));
    }

    private NexusConfig config() {
        return runtime.configuration().settings().getNexus();
    }

    private record State(UUID networkId, DialState dial, DestinationPolicy policy, FrameIo io) {
    }

    private record ReturnAddress(UUID portalId, long atMillis) {
    }
}
