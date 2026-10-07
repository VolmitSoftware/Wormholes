package art.arcane.wormholes.modded.seamless;

import art.arcane.wormholes.modded.mixin.RemoteTrackedEntityAccess;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.plate.ChunkLease;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.wormholes.modded.MinecraftChunkLeasePlatform;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftProjectionWorldView;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.clientview.MinecraftPortalEnvironment;
import art.arcane.wormholes.modded.mixin.ProjectionEntityMapAccess;
import art.arcane.wormholes.modded.mixin.SeamlessChunkMapAccess;
import art.arcane.wormholes.modded.mixin.ServerConnectionAccess;
import art.arcane.wormholes.network.client.RemoteViewOptions;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.render.client.session.ClientViewTravel;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RemoteRoutes implements AutoCloseable {
    public static final int VIEWER_INTERVAL_TICKS = 10;
    public static final int MIN_CORE_RADIUS = 2;
    private static final int LINGER_TICKS = RouteStream.FORGET_HYSTERESIS_TICKS;
    private static final int MAX_LEASES_PER_TICK = 16;
    private static final int MAX_NEAR_ROUTES = 16;
    private static final double FULL_RADIUS_BLOCKS = 8.0D;
    private static final double PARTIAL_RADIUS_BLOCKS = 24.0D;
    private static final Comparator<Candidate> NEAREST = Comparator.comparingDouble(Candidate::distance);

    private final WormholesModRuntime runtime;
    private final Map<UUID, PlayerRoutes> players = new HashMap<>();
    private final Map<ServerLevel, List<RemoteRoute>> resident = new IdentityHashMap<>();
    private final Map<ServerLevel, Long2IntOpenHashMap> arrivals = new IdentityHashMap<>();
    private final LongArrayList dirty = new LongArrayList();
    private final IntOpenHashSet seen = new IntOpenHashSet();

    public RemoteRoutes(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public static List<Candidate> rank(List<Candidate> candidates, int limit) {
        List<Candidate> ranked = new ArrayList<>(candidates);
        ranked.sort(NEAREST);
        return ranked.size() <= limit ? ranked : new ArrayList<>(ranked.subList(0, limit));
    }

    public static int fullRadius(int requestedViewDistance, int serverViewDistance) {
        int view = Math.clamp(requestedViewDistance, 2, Math.max(2, serverViewDistance));
        return Math.min(RouteWindow.MAX_RADIUS, view);
    }

    public static int radius(int fullRadius, double distance) {
        if (distance <= FULL_RADIUS_BLOCKS) {
            return fullRadius;
        }
        if (distance <= PARTIAL_RADIUS_BLOCKS) {
            return Math.max(1, (fullRadius * 2 + 2) / 3);
        }
        return Math.max(1, (fullRadius + 2) / 3);
    }

    public static int coreRadius(int fullRadius) {
        return Math.min(fullRadius, Math.max(MIN_CORE_RADIUS, (fullRadius + 2) / 3));
    }

    public static RouteWindow window(Vec3d anchor, int radius) {
        return new RouteWindow((int) Math.floor(anchor.x()) >> 4, (int) Math.floor(anchor.z()) >> 4, radius);
    }

    public static boolean covers(RemoteRoute route, int chunkX, int chunkZ, boolean border) {
        return route.opened() && route.stream().delivered(ChunkPos.pack(chunkX, chunkZ))
            && (border ? route.window().border(chunkX, chunkZ) : route.window().contains(chunkX, chunkZ));
    }

    public static Optional<TravelMessage.TravelWorld> travelWorld(ServerLevel level) {
        Optional<ResourceKey<DimensionType>> type = level.dimensionTypeRegistration().unwrapKey();
        if (type.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TravelMessage.TravelWorld(level.dimension().identifier().toString(), type.get().identifier().toString(),
            BiomeManager.obfuscateSeed(level.getSeed()), level.isDebug(), level.isFlat(), level.getSeaLevel(), level.getMinY(), level.getHeight()));
    }

    public static TravelMessage.RemoteLevelOpen openReturn(ServerLevel origin, MinecraftPortal back, int handle, int radius) {
        TravelMessage.TravelWorld world = travelWorld(origin).orElse(null);
        if (world == null) {
            return null;
        }
        Vec3d anchor = back.getOrigin();
        RouteWindow window = window(anchor, radius);
        return new TravelMessage.RemoteLevelOpen(handle, world, MinecraftPortalEnvironment.capture(origin, anchor, OpticTransform.IDENTITY,
            origin.isFlat()), window.radius(), new TravelMessage.TravelCoordinate(window.centerX(), window.centerZ()));
    }

    public void update(ServerPlayer player, ClientViewTravel<?> travel, List<Candidate> candidates, long tick) {
        runtime.requireServerThread();
        PlayerRoutes state = state(player, travel);
        RemoteViewOptions config = runtime.configuration().remoteView();
        int full = fullRadius(player.requestedViewDistance(), runtime.server().getPlayerList().getViewDistance());
        List<Candidate> nearby = new ArrayList<>(candidates.size());
        List<Candidate> far = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            if (candidate.level() == player.level()
                && window(candidate.destination().getOrigin(), radius(full, candidate.distance())).within(player.getChunkTrackingView())) {
                nearby.add(candidate);
            } else {
                far.add(candidate);
            }
        }
        List<Candidate> ranked = rank(far, config.routes());
        ranked.addAll(rank(nearby, MAX_NEAR_ROUTES));
        expire(state, ranked, tick);
        for (int index = 0; index < ranked.size(); index++) {
            Candidate candidate = ranked.get(index);
            Vec3d anchor = candidate.destination().getOrigin();
            int centerX = (int) Math.floor(anchor.x()) >> 4;
            int centerZ = (int) Math.floor(anchor.z()) >> 4;
            int radius = radius(full, candidate.distance());
            RemoteRoute route = state.find(player.getUUID(), candidate);
            if (route == null) {
                RouteWindow window = new RouteWindow(centerX, centerZ, radius);
                open(state, player, candidate, window, candidate.level() == player.level() && window.within(player.getChunkTrackingView()));
                continue;
            }
            if (!route.window().matches(centerX, centerZ, radius)) {
                route.stream().window(new RouteWindow(centerX, centerZ, radius), tick);
                releaseOutside(route);
                route.viewersDirty();
            }
            boolean near = candidate.level() == player.level() && route.window().within(player.getChunkTrackingView());
            if (near && route.resident()) {
                demote(state, route);
            } else if (!near && !route.resident()) {
                promote(state, route);
            }
        }
        straddle(player, ranked);
        Budget budget = new Budget(config.chunksPerTick(), config.bytesPerTick());
        for (int index = 0; index < state.routes.size(); index++) {
            RemoteRoute route = state.routes.get(index);
            if (route.resident()) {
                stream(state, route, budget, tick);
            }
        }
    }

    public RemoteRoute route(UUID player, UUID source) {
        PlayerRoutes state = players.get(player);
        if (state == null) {
            return null;
        }
        for (int index = 0; index < state.routes.size(); index++) {
            RemoteRoute route = state.routes.get(index);
            if (route.sourceId().equals(source) && route.lingerUntil() == Long.MAX_VALUE) {
                return route;
            }
        }
        return null;
    }

    public RoutedSends sends(UUID player) {
        PlayerRoutes state = players.get(player);
        return state == null ? null : state.sends;
    }

    public boolean residentLevel(ServerLevel level) {
        List<RemoteRoute> routes = resident.get(level);
        return routes != null && !routes.isEmpty();
    }

    public List<RemoteRoute> covering(ServerLevel level, int chunkX, int chunkZ, boolean border) {
        List<RemoteRoute> routes = resident.get(level);
        if (routes == null || routes.isEmpty()) {
            return List.of();
        }
        List<RemoteRoute> hits = null;
        for (int index = 0; index < routes.size(); index++) {
            RemoteRoute route = routes.get(index);
            if (!covers(route, chunkX, chunkZ, border)) {
                continue;
            }
            if (hits == null) {
                hits = new ArrayList<>(2);
            }
            hits.add(route);
        }
        return hits == null ? List.of() : hits;
    }

    public void broadcast(ResourceKey<Level> dimension, Packet<? super ClientGamePacketListener> packet) {
        if (resident.isEmpty()) {
            return;
        }
        for (Map.Entry<ServerLevel, List<RemoteRoute>> entry : resident.entrySet()) {
            if (entry.getKey().dimension() != dimension) {
                continue;
            }
            List<RemoteRoute> routes = entry.getValue();
            for (int index = 0; index < routes.size(); index++) {
                RemoteRoute route = routes.get(index);
                if (route.opened() && route.viewer() != null) {
                    route.viewer().send(packet);
                }
            }
        }
    }

    public boolean ack(UUID player, TravelMessage.RemoteViewAck ack) {
        PlayerRoutes state = players.get(player);
        if (state == null) {
            return false;
        }
        for (int index = 0; index < state.routes.size(); index++) {
            RemoteRoute route = state.routes.get(index);
            if (route.resident() && route.handle() == ack.levelHandle()) {
                route.stream().ack(ack.chunksPerTickHint());
                return true;
            }
        }
        return false;
    }

    public boolean reopen(UUID player, int handle) {
        PlayerRoutes state = players.get(player);
        if (state == null) {
            return false;
        }
        for (int index = 0; index < state.routes.size(); index++) {
            RemoteRoute route = state.routes.get(index);
            if (route.resident() && route.handle() == handle && route.opened()) {
                releaseArrival(route);
                unpairAll(route);
                route.stream().clear();
                route.opened(false);
                route.viewersDirty();
                return true;
            }
        }
        return false;
    }

    public void tickViewers(ServerLevel level, Int2ObjectMap<?> entityMap, long tick) {
        List<RemoteRoute> routes = resident.get(level);
        if (routes == null || routes.isEmpty()) {
            return;
        }
        for (int index = 0; index < routes.size(); index++) {
            RemoteRoute route = routes.get(index);
            if (!route.opened() || !route.viewersDue(tick, VIEWER_INTERVAL_TICKS)) {
                continue;
            }
            ServerPlayer player = runtime.server().getPlayerList().getPlayer(route.playerId());
            PlayerRoutes state = players.get(route.playerId());
            if (player == null || state == null) {
                continue;
            }
            evaluate(level, entityMap, route, player, state.sends);
            route.viewersEvaluated(tick);
        }
    }

    public void forget(UUID player, boolean notify) {
        PlayerRoutes state = players.remove(player);
        if (state == null) {
            return;
        }
        ServerPlayer online = runtime.server().getPlayerList().getPlayer(player);
        if (online != null) {
            StraddleTracker.clear(online);
        }
        for (int index = 0; index < state.routes.size(); index++) {
            retire(state, state.routes.get(index), notify);
        }
        state.routes.clear();
    }

    @Override
    public void close() {
        for (UUID player : List.copyOf(players.keySet())) {
            forget(player, false);
        }
        for (ServerLevel level : resident.keySet()) {
            if (level instanceof ResidentRoutesHolder holder) {
                holder.wormholesResidentRoutes(false);
            }
        }
        resident.clear();
        arrivals.clear();
    }

    public boolean handOver(ServerPlayer player, HandOver handOver, long tick) {
        runtime.requireServerThread();
        SeamlessMove.Events events = runtime.seamlessEvents();
        RemoteRoute forward = handOver.forward();
        if (forward != null && forward.resident()) {
            RouteWindow delivered = forward.window().withRadius(Math.max(1, forward.stream().deliveredRadius()));
            MinecraftChunkLeasePlatform.holdHandover(forward.level(), delivered.centerX(), delivered.centerZ(), delivered.radius());
            ChunkTrackingView view = delivered.view();
            player.setChunkTrackingView(view);
            hold(player, forward.level(), view);
            leaveDeparted(player, handOver, forward.level(), view, events);
            watchDelivered(player, handOver, forward.level(), view, events);
        }
        List<RemoteTrackedEntityAccess> departed = trackedBy(handOver.origin(), player);
        if (forward != null) {
            adoptForward(player, forward, events);
            release(forward);
        }
        RemoteRoute returned = forward != null && forward.resident() && handOver.back() != null
            ? adoptReturn(player, handOver, tick) : null;
        for (int index = 0; index < departed.size(); index++) {
            RemoteTrackedEntityAccess tracked = departed.get(index);
            Entity entity = tracked.wormholesTrackedEntity();
            if (returned != null && entity != player) {
                tracked.wormholesSeenBy().add(returned.viewer());
                returned.paired().add(entity.getId());
            } else {
                entity.stopSeenByPlayer(player);
            }
            if (entity != player) {
                events.entityUntracked(player, entity);
            }
        }
        return returned != null;
    }

    public void abandon(ServerPlayer player, ServerLevel origin, boolean levelChanged) {
        runtime.requireServerThread();
        forget(player.getUUID(), true);
        ((HeldChunkSender) player.connection.chunkSender).wormholesHeldChunks().clear();
        if (!levelChanged) {
            return;
        }
        player.setChunkTrackingView(ChunkTrackingView.EMPTY);
        List<RemoteTrackedEntityAccess> departed = trackedBy(origin, player);
        for (int index = 0; index < departed.size(); index++) {
            departed.get(index).wormholesTrackedEntity().stopSeenByPlayer(player);
        }
    }

    public List<RemoteRoute> routes(UUID player) {
        PlayerRoutes state = players.get(player);
        return state == null ? List.of() : state.routes;
    }

    RemoteRoute adopt(ServerPlayer player, RemoteRoute.Key key, ServerLevel level, Vec3d anchor, RouteWindow window, int handle) {
        PlayerRoutes state = players.get(player.getUUID());
        if (state == null) {
            return null;
        }
        RemoteRoute route = new RemoteRoute(key, level, anchor, window, handle);
        route.viewer(new RemoteViewerConnection(player, route, state.sends));
        route.opened(true);
        route.changesVersion(runtime.projections().changes().currentVersion());
        state.handles.set(handle);
        state.routes.add(route);
        index(route);
        holdArrival(route);
        return route;
    }

    void release(RemoteRoute route) {
        PlayerRoutes state = players.get(route.playerId());
        if (state == null) {
            return;
        }
        if (state.routes.remove(route)) {
            if (route.resident()) {
                unindex(route);
                releaseArrival(route);
                state.handles.clear(route.handle());
            }
            route.close();
        }
    }

    void closeHandle(UUID player, int handle) {
        PlayerRoutes state = players.get(player);
        if (state != null && handle > 0) {
            state.sends.control(new TravelMessage.RemoteLevelClose(handle));
        }
    }

    static StraddleTracker.Endpoint endpoint(MinecraftPortal portal) {
        return new StraddleTracker.Endpoint(portal.getGeometry(), portal.getFrame(), portal.getOrigin());
    }

    private PlayerRoutes state(ServerPlayer player, ClientViewTravel<?> travel) {
        PlayerRoutes state = players.get(player.getUUID());
        if (state != null && state.travel == travel) {
            return state;
        }
        if (state != null) {
            forget(player.getUUID(), false);
        }
        RoutedSends sends = new RoutedSends(RoutedPackets.outbound(((ServerConnectionAccess) player.connection).wormholesConnection(),
            runtime.server().registryAccess()), travel::sendTravel);
        PlayerRoutes created = new PlayerRoutes(travel, sends);
        players.put(player.getUUID(), created);
        return created;
    }

    private void straddle(ServerPlayer player, List<Candidate> ranked) {
        Box stretched = StraddleTracker.stretched(box(player.getBoundingBox()), runtime.portals().observedVelocity(player),
            new Vec3d(player.xo - player.getX(), player.yo - player.getY(), player.zo - player.getZ()));
        for (int index = 0; index < ranked.size(); index++) {
            Candidate candidate = ranked.get(index);
            if (!StraddleTracker.qualifies(stretched, candidate.source().getGeometry())) {
                continue;
            }
            StraddleTracker.track(player, endpoint(candidate.source()), endpoint(candidate.destination()), candidate.level(),
                new Vec3d(player.getX(), player.getEyeY(), player.getZ()));
            return;
        }
        StraddleTracker.clear(player);
    }

    private static Box box(AABB box) {
        return new Box(box.minX, box.maxX, box.minY, box.maxY, box.minZ, box.maxZ);
    }

    private void expire(PlayerRoutes state, List<Candidate> ranked, long tick) {
        Iterator<RemoteRoute> iterator = state.routes.iterator();
        while (iterator.hasNext()) {
            RemoteRoute route = iterator.next();
            if (ranked(ranked, route)) {
                route.lingerUntil(Long.MAX_VALUE);
                continue;
            }
            if (route.lingerUntil() == Long.MAX_VALUE) {
                route.lingerUntil(tick + LINGER_TICKS);
            }
            if (tick >= route.lingerUntil()) {
                retire(state, route, true);
                iterator.remove();
            }
        }
    }

    private static boolean ranked(List<Candidate> ranked, RemoteRoute route) {
        for (int index = 0; index < ranked.size(); index++) {
            Candidate candidate = ranked.get(index);
            if (candidate.source().getId().equals(route.sourceId()) && candidate.destination().getId().equals(route.destinationId())
                && candidate.level() == route.level()) {
                return true;
            }
        }
        return false;
    }

    private void open(PlayerRoutes state, ServerPlayer player, Candidate candidate, RouteWindow window, boolean near) {
        int handle = near ? 0 : state.handles.nextClearBit(1);
        if (handle > TravelMessage.MAX_LEVEL_HANDLE) {
            return;
        }
        RemoteRoute route = new RemoteRoute(candidate.key(player.getUUID()), candidate.level(), candidate.destination().getOrigin(), window, handle);
        route.viewer(new RemoteViewerConnection(player, route, state.sends));
        state.routes.add(route);
        if (handle > 0) {
            state.handles.set(handle);
            index(route);
        }
    }

    private void promote(PlayerRoutes state, RemoteRoute route) {
        int handle = state.handles.nextClearBit(1);
        if (handle > TravelMessage.MAX_LEVEL_HANDLE) {
            return;
        }
        state.handles.set(handle);
        route.stream().clear();
        route.opened(false);
        route.handle(handle);
        route.viewersDirty();
        index(route);
    }

    private void demote(PlayerRoutes state, RemoteRoute route) {
        if (route.opened()) {
            state.sends.control(new TravelMessage.RemoteLevelClose(route.handle()));
            releaseArrival(route);
        }
        unpairAll(route);
        unindex(route);
        releaseLeases(route);
        state.handles.clear(route.handle());
        route.stream().clear();
        route.opened(false);
        route.handle(0);
    }

    private void retire(PlayerRoutes state, RemoteRoute route, boolean notify) {
        if (route.resident()) {
            if (notify && route.opened()) {
                state.sends.control(new TravelMessage.RemoteLevelClose(route.handle()));
            }
            if (route.opened()) {
                releaseArrival(route);
            }
            unpairAll(route);
            unindex(route);
            state.handles.clear(route.handle());
        }
        route.close();
    }

    private void stream(PlayerRoutes state, RemoteRoute route, Budget budget, long tick) {
        ServerLevel level = route.level();
        RouteWindow window = route.window();
        if (!route.opened()) {
            state.sends.control(new TravelMessage.RemoteLevelOpen(route.handle(), travelWorld(level).orElseThrow(),
                MinecraftPortalEnvironment.capture(level, route.anchor(), OpticTransform.IDENTITY, level.isFlat()), window.radius(),
                new TravelMessage.TravelCoordinate(window.centerX(), window.centerZ())));
            state.sends.send(route, new ClientboundSetChunkCacheCenterPacket(window.centerX(), window.centerZ()));
            route.opened(true);
            route.changesVersion(runtime.projections().changes().currentVersion());
            holdArrival(route);
        }
        LongList forgotten = route.stream().forgets(tick);
        for (int index = 0; index < forgotten.size(); index++) {
            long key = forgotten.getLong(index);
            state.sends.send(route, new ClientboundForgetLevelChunkPacket(ChunkPos.unpack(key)));
            ChunkLease lease = route.leases().remove(key);
            if (lease != null) {
                lease.close();
            }
        }
        retain(route, window);
        changes(route, window);
        if (budget.chunks <= 0 || budget.bytes <= 0) {
            return;
        }
        LongList plan = route.stream().plan(route.stream().chunkBudget(budget.chunks));
        for (int index = 0; index < plan.size() && budget.chunks > 0 && budget.bytes > 0; index++) {
            long key = plan.getLong(index);
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) {
                continue;
            }
            int bytes = state.sends.send(route, new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
            if (bytes == RoutedSends.FAILED) {
                route.stream().failed(key);
                continue;
            }
            route.stream().markDelivered(key);
            route.stream().live(key, ticking(level, key));
            route.viewersDirty();
            budget.chunks--;
            budget.bytes -= Math.max(1, bytes);
        }
    }

    private void retain(RemoteRoute route, RouteWindow window) {
        int requests = 0;
        LongList keys = window.keys();
        UUID worldId = null;
        for (int index = 0; index < keys.size() && requests < MAX_LEASES_PER_TICK; index++) {
            long key = keys.getLong(index);
            if (route.leases().containsKey(key)) {
                continue;
            }
            if (worldId == null) {
                worldId = MinecraftProjectionWorldView.worldId(route.level());
            }
            route.leases().put(key, runtime.leases().retain(route.level(), worldId, ChunkPos.getX(key), ChunkPos.getZ(key)));
            requests++;
        }
    }

    private void changes(RemoteRoute route, RouteWindow window) {
        WorldChangeTracker changes = runtime.projections().changes();
        long version = changes.currentVersion();
        if (version == route.changesVersion()) {
            return;
        }
        dirty.clear();
        boolean tracked = changes.collectDirtySince(MinecraftProjectionWorldView.worldId(route.level()), window.minX(), window.minZ(),
            window.maxX(), window.maxZ(), route.changesVersion(), dirty);
        route.changesVersion(version);
        if (!tracked) {
            for (long key : route.stream().deliveredKeys().toLongArray()) {
                route.stream().changed(key);
            }
            return;
        }
        for (int index = 0; index < dirty.size(); index++) {
            long cell = dirty.getLong(index);
            long key = ChunkPos.pack(CellKeys.chunkX(cell), CellKeys.chunkZ(cell));
            route.stream().observed(key, ticking(route.level(), key));
        }
    }

    private static boolean ticking(ServerLevel level, long key) {
        ChunkHolder holder = ((SeamlessChunkMapAccess) level.getChunkSource().chunkMap).wormholesVisibleChunk(key);
        return holder != null && holder.getTickingChunk() != null;
    }

    private void releaseOutside(RemoteRoute route) {
        RouteWindow window = route.window();
        ObjectIterator<Long2ObjectMap.Entry<ChunkLease>> iterator = route.leases().long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<ChunkLease> entry = iterator.next();
            long key = entry.getLongKey();
            if (!window.contains(key) && route.stream().revision(key) == 0) {
                entry.getValue().close();
                iterator.remove();
            }
        }
    }

    private void releaseLeases(RemoteRoute route) {
        for (ChunkLease lease : route.leases().values()) {
            lease.close();
        }
        route.leases().clear();
    }

    private void evaluate(ServerLevel level, Int2ObjectMap<?> entityMap, RemoteRoute route, ServerPlayer player, RoutedSends sends) {
        RouteWindow window = route.window();
        AABB box = new AABB(window.minX() * 16.0D, level.getMinY(), window.minZ() * 16.0D,
            (window.maxX() + 1) * 16.0D, level.getMaxY(), (window.maxZ() + 1) * 16.0D);
        seen.clear();
        for (Entity entity : level.getEntities((Entity) null, box, Entity::isAlive)) {
            if (entityMap.get(entity.getId()) instanceof RemoteTrackedEntityAccess tracked) {
                seen.add(entity.getId());
                RemoteViewer.update(route, tracked, player, sends);
            }
        }
        if (route.paired().isEmpty()) {
            return;
        }
        for (int id : route.paired().toIntArray()) {
            if (seen.contains(id)) {
                continue;
            }
            if (entityMap.get(id) instanceof RemoteTrackedEntityAccess tracked) {
                RemoteViewer.unpair(route, tracked, player, sends, true);
            } else {
                route.paired().remove(id);
            }
        }
    }

    private void unpairAll(RemoteRoute route) {
        if (route.paired().isEmpty() || route.viewer() == null) {
            route.paired().clear();
            return;
        }
        Int2ObjectMap<?> entityMap = ((ProjectionEntityMapAccess) route.level().getChunkSource().chunkMap).wormholesEntityMap();
        ServerPlayer player = route.viewer().getPlayer();
        for (int id : route.paired().toIntArray()) {
            if (entityMap.get(id) instanceof RemoteTrackedEntityAccess tracked && tracked.wormholesSeenBy().remove(route.viewer())) {
                tracked.wormholesTrackedEntity().stopSeenByPlayer(player);
            }
        }
        route.paired().clear();
    }

    private static void hold(ServerPlayer player, ServerLevel level, ChunkTrackingView view) {
        LongSet held = ((HeldChunkSender) player.connection.chunkSender).wormholesHeldChunks();
        held.clear();
        view.forEach(position -> {
            long key = position.pack();
            if (level.getChunkSource().chunkMap.getChunkToSend(key) == null) {
                held.add(key);
            }
        });
    }

    private List<RemoteTrackedEntityAccess> trackedBy(ServerLevel level, ServerPlayer player) {
        List<RemoteTrackedEntityAccess> tracked = new ArrayList<>();
        for (Object value : ((ProjectionEntityMapAccess) level.getChunkSource().chunkMap).wormholesEntityMap().values()) {
            if (value instanceof RemoteTrackedEntityAccess access && access.wormholesSeenBy().remove(player.connection)) {
                tracked.add(access);
            }
        }
        return tracked;
    }

    private void adoptForward(ServerPlayer player, RemoteRoute forward, SeamlessMove.Events events) {
        if (forward.paired().isEmpty() || forward.viewer() == null) {
            return;
        }
        Int2ObjectMap<?> entityMap = ((ProjectionEntityMapAccess) forward.level().getChunkSource().chunkMap).wormholesEntityMap();
        for (int id : forward.paired().toIntArray()) {
            if (entityMap.get(id) instanceof RemoteTrackedEntityAccess tracked && tracked.wormholesSeenBy().remove(forward.viewer())) {
                tracked.wormholesSeenBy().add(player.connection);
                relayPairingPayloads(tracked, player);
                events.entityTracked(player, tracked.wormholesTrackedEntity());
            }
        }
        forward.paired().clear();
    }

    private static void relayPairingPayloads(RemoteTrackedEntityAccess tracked, ServerPlayer player) {
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        tracked.wormholesServerEntity().sendPairingData(player, packets::add);
        for (int index = 0; index < packets.size(); index++) {
            if (packets.get(index) instanceof ClientboundCustomPayloadPacket payload) {
                player.connection.send(payload);
            }
        }
    }

    private static void leaveDeparted(ServerPlayer player, HandOver handOver, ServerLevel level, ChunkTrackingView view, SeamlessMove.Events events) {
        boolean levelChanged = level != handOver.origin();
        handOver.departedView().forEach(position -> {
            if (levelChanged || !view.contains(position.x(), position.z())) {
                events.chunkUnwatched(new SeamlessMove.ChunkLeave(player, handOver.origin(), position,
                    !handOver.departedPending().contains(position.pack())));
            }
        });
    }

    private static void watchDelivered(ServerPlayer player, HandOver handOver, ServerLevel level, ChunkTrackingView view, SeamlessMove.Events events) {
        boolean levelChanged = level != handOver.origin();
        view.forEach(position -> {
            if (!levelChanged && handOver.departedView().contains(position.x(), position.z())) {
                return;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(position.x(), position.z());
            if (chunk != null) {
                events.chunkWatched(player, level, chunk);
            }
        });
    }

    private RemoteRoute adoptReturn(ServerPlayer player, HandOver handOver, long tick) {
        Return back = handOver.back();
        TravelMessage.RemoteLevelOpen open = back.open();
        RouteWindow window = new RouteWindow(open.center().x(), open.center().z(), open.viewRadius());
        RemoteRoute returned = adopt(player, new RemoteRoute.Key(player.getUUID(), back.source().getId(), back.destination().getId()),
            handOver.origin(), back.destination().getOrigin(), window, open.levelHandle());
        if (returned == null) {
            return null;
        }
        LongArrayList adopted = new LongArrayList();
        handOver.departedView().forEach(position -> {
            long key = position.pack();
            if (!handOver.departedPending().contains(key)) {
                adopted.add(key);
            }
        });
        for (int index = 0; index < adopted.size(); index++) {
            returned.stream().adopt(adopted.getLong(index), tick);
        }
        PlayerRoutes state = players.get(player.getUUID());
        state.sends.control(open);
        state.sends.send(returned, new ClientboundSetChunkCacheCenterPacket(window.centerX(), window.centerZ()));
        return returned;
    }

    private void index(RemoteRoute route) {
        List<RemoteRoute> routes = resident.computeIfAbsent(route.level(), ignored -> new ArrayList<>(2));
        if (!routes.contains(route)) {
            routes.add(route);
        }
        if (route.level() instanceof ResidentRoutesHolder holder) {
            holder.wormholesResidentRoutes(true);
        }
    }

    private void unindex(RemoteRoute route) {
        List<RemoteRoute> routes = resident.get(route.level());
        if (routes != null && routes.remove(route) && routes.isEmpty()) {
            resident.remove(route.level());
            if (route.level() instanceof ResidentRoutesHolder holder) {
                holder.wormholesResidentRoutes(false);
            }
        }
    }

    private void holdArrival(RemoteRoute route) {
        long key = ChunkPos.pack(route.window().centerX(), route.window().centerZ());
        Long2IntOpenHashMap counts = arrivals.computeIfAbsent(route.level(), ignored -> new Long2IntOpenHashMap());
        if (counts.addTo(key, 1) == 0) {
            MinecraftChunkLeasePlatform.holdArrival(route.level(), route.window().centerX(), route.window().centerZ());
        }
    }

    private void releaseArrival(RemoteRoute route) {
        Long2IntOpenHashMap counts = arrivals.get(route.level());
        long key = ChunkPos.pack(route.window().centerX(), route.window().centerZ());
        if (counts == null || !counts.containsKey(key)) {
            return;
        }
        if (counts.addTo(key, -1) <= 1) {
            counts.remove(key);
            MinecraftChunkLeasePlatform.releaseArrival(route.level(), route.window().centerX(), route.window().centerZ());
            if (counts.isEmpty()) {
                arrivals.remove(route.level());
            }
        }
    }

    public record Candidate(MinecraftPortal source, MinecraftPortal destination, ServerLevel level, double distance) {
        public Candidate {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(level, "level");
        }

        public RemoteRoute.Key key(UUID player) {
            return new RemoteRoute.Key(player, source.getId(), destination.getId());
        }
    }

    public record Return(MinecraftPortal source, MinecraftPortal destination, TravelMessage.RemoteLevelOpen open) {
        public Return {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(open, "open");
        }
    }

    public record HandOver(RemoteRoute forward, ServerLevel origin, ChunkTrackingView departedView, LongSet departedPending, Return back) {
        public HandOver {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(departedView, "departedView");
            Objects.requireNonNull(departedPending, "departedPending");
        }
    }

    private static final class Budget {
        private int chunks;
        private int bytes;

        private Budget(int chunks, int bytes) {
            this.chunks = chunks;
            this.bytes = bytes;
        }
    }

    private static final class PlayerRoutes {
        private final ClientViewTravel<?> travel;
        private final RoutedSends sends;
        private final List<RemoteRoute> routes = new ArrayList<>(4);
        private final BitSet handles = new BitSet(TravelMessage.MAX_LEVEL_HANDLE + 1);

        private PlayerRoutes(ClientViewTravel<?> travel, RoutedSends sends) {
            this.travel = travel;
            this.sends = sends;
        }

        private RemoteRoute find(UUID player, Candidate candidate) {
            for (int index = 0; index < routes.size(); index++) {
                RemoteRoute route = routes.get(index);
                if (route.playerId().equals(player) && route.sourceId().equals(candidate.source().getId())
                    && route.destinationId().equals(candidate.destination().getId())) {
                    return route;
                }
            }
            return null;
        }
    }
}
