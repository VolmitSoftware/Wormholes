package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftBlockEntityTags;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.modded.clientview.MinecraftLightSnapshot;
import art.arcane.wormholes.network.client.Brick;
import art.arcane.wormholes.network.client.BrickCodec;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.SectionBiomes;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.render.blockentity.BlockEntitySanitizer;
import art.arcane.wormholes.render.client.ClientViewBlockTransform;
import art.arcane.wormholes.render.client.session.ClientMeshPlan;
import art.arcane.wormholes.render.plate.PlateBox;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class ClientLocalMeshSources {
    private static final long SNAPSHOT_BUDGET = 64 * 1024 * 1024L;
    private static final int SECTION_ATTEMPTS = 16;
    private final LinkedHashMap<SnapshotKey, Snapshot> snapshots = new LinkedHashMap<>(128, 0.75F, true);
    private final LinkedHashMap<SnapshotKey, WeakReference<ClientLevel>> pendingSnapshots = new LinkedHashMap<>();
    private final Map<Integer, Route> routes = new HashMap<>();
    private final Consumer<ClientViewMessage> sender;
    private ClientLevel level;
    private int nextRoute;
    private long snapshotBytes;
    private long tick;
    private long epoch;
    private boolean epochKnown;

    public ClientLocalMeshSources(Consumer<ClientViewMessage> sender) {
        this.sender = sender;
    }

    public long bytes() {
        return snapshotBytes;
    }

    public void clear() {
        snapshots.clear();
        pendingSnapshots.clear();
        snapshotBytes = 0;
        routes.clear();
        level = null;
        nextRoute = 0;
        epochKnown = false;
    }

    public void blockChanged(Object world, BlockPos position) {
        if (level != world || ClientMeshEntities.active() != null) {
            return;
        }
        for (Route route : routes.values()) {
            long display = SectionPos.asLong(route.cells.displayX(position.getX(), position.getY(), position.getZ()) >> 4,
                route.cells.displayY(position.getX(), position.getY(), position.getZ()) >> 4,
                route.cells.displayZ(position.getX(), position.getY(), position.getZ()) >> 4);
            if (route.derived.containsKey(display)) {
                route.refreshing.add(display);
                route.dirty.addAndMoveToFirst(display);
            }
        }
        dirty(SectionPos.asLong(position.getX() >> 4, position.getY() >> 4, position.getZ() >> 4));
    }

    public void chunkChanged(Object world, int x, int z) {
        if (!(world instanceof ClientLevel current) || ClientMeshEntities.active() != null) {
            return;
        }
        String name = world(current);
        LevelChunk loaded = current.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (loaded != null) {
            for (Iterator<Map.Entry<SnapshotKey, Snapshot>> iterator = snapshots.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<SnapshotKey, Snapshot> entry = iterator.next();
                SnapshotKey key = entry.getKey();
                if (key.world.equals(name) && SectionPos.x(key.section) == x && SectionPos.z(key.section) == z) {
                    snapshotBytes -= entry.getValue().bytes;
                    iterator.remove();
                }
            }
            for (int y = loaded.getMinSectionY(); y < loaded.getMinSectionY() + loaded.getSectionsCount(); y++) {
                enqueueSnapshot(current, SectionPos.asLong(x, y, z));
            }
        }
        if (level != current) {
            return;
        }
        for (Route route : routes.values()) {
            route.cursor = 0;
            for (Map.Entry<Long, Derived> entry : route.derived.long2ObjectEntrySet()) {
                if (entry.getValue().usesChunk(x, z)) {
                    route.enqueue(entry.getKey());
                }
            }
        }
    }

    public boolean localEntity(int portalKey, UUID id) {
        Route route = routes.get(portalKey);
        return route != null && route.entities.contains(id);
    }

    public Set<UUID> entities(int portalKey) {
        Route route = routes.get(portalKey);
        return route == null ? Set.of() : Set.copyOf(route.entities);
    }

    public void update(ClientViewSession session, ClientLevel current, double eyeX, double eyeY, double eyeZ) throws ClientViewProtocolException {
        tick++;
        if (session.acceptMessage() != null) {
            long currentEpoch = session.acceptMessage().hashSalt();
            if (epochKnown && epoch != currentEpoch) {
                retract(session);
                clear();
            }
            epoch = currentEpoch;
            epochKnown = true;
        }
        if (level != current) {
            retract(session);
            routes.clear();
            level = current;
        }
        capturePending();
        session.flushCached(sender);
        if (!session.active() || !session.has(ClientViewCapability.MESH_RENDER) || !session.has(ClientViewCapability.CLIENT_MIRROR)) {
            retract(session);
            routes.clear();
            return;
        }
        routes.entrySet().removeIf(entry -> session.portal(entry.getKey()) == null || session.meshes().view(entry.getKey()) == null);
        for (ClientPortal portal : session.portals().values()) {
            ClientViewEnvironment environment = session.environment(portal.portalKey());
            ClientMeshSections.View view = session.meshes().view(portal.portalKey());
            boolean local = environment != null && eligible(portal, environment, current);
            if (view == null || environment == null || !local && !session.has(ClientViewCapability.MESH_REUSE)) {
                Route removed = routes.remove(portal.portalKey());
                if (removed != null) {
                    release(session, removed);
                }
                continue;
            }
            GeometryVector eye = sourceEye(session, portal, new GeometryVector(eyeX, eyeY, eyeZ));
            if (eye == null) {
                Route removed = routes.remove(portal.portalKey());
                if (removed != null) {
                    release(session, removed);
                }
                continue;
            }
            Route route = routes.get(portal.portalKey());
            if (route == null || route.generation != view.generation() || !route.transform.equals(environment.transform()) || route.local != local) {
                Route replacement = new Route(portal, view, environment.transform(), eye, tick, environment.world().dimensionKey(), local);
                if (route != null && route.view == view && route.transform.equals(environment.transform()) && route.local == local) {
                    replacement.derived.putAll(route.derived);
                    for (long key : route.derived.keySet()) {
                        if (replacement.inBounds(key)) {
                            if (local) {
                                replacement.added.add(coordinate(key));
                            }
                        }
                    }
                } else if (route != null) {
                    release(session, route);
                }
                route = replacement;
                routes.put(portal.portalKey(), route);
            }
            if (tick - route.plannedTick >= 8 && route.eye.distance(eye) > 0.5) {
                route.plan(portal.geometry(), eye, tick);
            }
            if (route.replanned) {
                for (Iterator<Long2ObjectMap.Entry<Derived>> iterator = route.derived.long2ObjectEntrySet().iterator(); iterator.hasNext();) {
                    Long2ObjectMap.Entry<Derived> entry = iterator.next();
                    if (!route.selected.contains(entry.getLongKey())) {
                        if (route.local) {
                            session.meshes().local(route.key, entry.getLongKey(), null);
                        }
                        if (route.local && route.inBounds(entry.getLongKey())) {
                            route.removed.add(coordinate(entry.getLongKey()));
                        }
                        route.added.remove(coordinate(entry.getKey()));
                        iterator.remove();
                    }
                }
                route.replanned = false;
            }
            if (route.local && session.has(ClientViewCapability.LOCAL_MESH)) {
                updateEntities(route);
            } else {
                route.entities.clear();
                route.addedEntities.clear();
                route.removedEntities.clear();
            }
        }
        if (routes.isEmpty()) {
            return;
        }
        List<Route> active = new ArrayList<>(routes.values());
        int attempts = 0;
        long deadline = System.nanoTime() + 2_000_000L;
        while (attempts++ < SECTION_ATTEMPTS && System.nanoTime() < deadline) {
            if (nextRoute >= active.size()) {
                nextRoute = 0;
            }
            Route route = active.get(nextRoute++);
            Long section = route.dirty.isEmpty() ? null : Long.valueOf(route.dirty.removeFirstLong());
            if (section == null) {
                if (route.cursor >= route.selection.size()) {
                    continue;
                }
                section = route.selection.get(route.cursor++);
            }
            if (!route.inBounds(section) || !route.selected.contains(section.longValue())) {
                continue;
            }
            Derived previous = route.derived.get(section.longValue());
            if (previous != null && !route.refreshing.remove(section.longValue())) {
                continue;
            }
            Derived next = capture(session, route, section.longValue());
            if (next == null) {
                if (previous != null) {
                    if (route.local) {
                        session.meshes().local(route.key, section, null);
                    }
                    route.derived.remove(section.longValue());
                    if (route.local) {
                    route.removed.add(coordinate(section));
                }
                }
                continue;
            }
            ClientViewMessage.MeshClaim claim = route.local ? null : session.meshes().preview(route.key, section, next.section);
            if (route.local ? session.meshes().local(route.key, section, next.section) : claim != null) {
                route.derived.put(section.longValue(), new Derived(session.meshes().view(route.key).section(section.longValue()), next.dependencies));
                if (previous == null && route.local) {
                    route.added.add(coordinate(section));
                }
                if (claim != null) {
                    route.cached.add(claim);
                }
            }
        }
        for (Route route : active) {
            if (!route.cached.isEmpty() && session.has(ClientViewCapability.MESH_REUSE)) {
                session.cacheClaims(route.key, route.cached);
                route.cached.clear();
            }
            if (session.has(ClientViewCapability.LOCAL_MESH)) {
                flush(route, true, route.added, route.addedEntities);
                flush(route, false, route.removed, route.removedEntities);
            } else {
                route.added.clear();
                route.addedEntities.clear();
                route.removed.clear();
                route.removedEntities.clear();
            }
        }
    }

    private static boolean eligible(ClientPortal portal, ClientViewEnvironment environment, ClientLevel level) {
        return portal.geometry().mirror() && environment.world().dimensionKey().equals(level.dimension().identifier().toString());
    }

    static GeometryVector sourceEye(ClientViewSession session, ClientPortal portal, GeometryVector eye) {
        List<ClientViewEnvironment.Transform> ancestors = new ArrayList<>();
        int parent = portal.geometry().parentPortalKey();
        Set<Integer> visited = new HashSet<>();
        visited.add(portal.portalKey());
        while (parent != 0) {
            if (!visited.add(parent) || ancestors.size() >= ClientViewProtocol.MAX_GEOMETRY_DEPTH) {
                return null;
            }
            ClientPortal ancestor = session.portal(parent);
            ClientViewEnvironment environment = session.environment(parent);
            if (ancestor == null || environment == null) {
                return null;
            }
            ancestors.add(environment.transform());
            parent = ancestor.geometry().parentPortalKey();
        }
        for (int index = ancestors.size() - 1; index >= 0; index--) {
            eye = ancestors.get(index).destinationPoint(eye.x(), eye.y(), eye.z());
        }
        return eye;
    }

    private void dirty(long source) {
        Snapshot removed = snapshots.remove(new SnapshotKey(world(level), source));
        enqueueSnapshot(level, source);
        if (removed != null) {
            snapshotBytes -= removed.bytes;
        }
        for (Route route : routes.values()) {
            for (Map.Entry<Long, Derived> entry : route.derived.long2ObjectEntrySet()) {
                if (route.world.equals(world(level)) && entry.getValue().dependencies.contains(source)) {
                    route.enqueue(entry.getKey());
                }
            }
        }
    }

    private void updateEntities(Route route) {
        Set<UUID> present = new HashSet<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!entity.isRemoved() && !ClientMeshEntities.hiddenFromWorld(entity) && entity != Minecraft.getInstance().player) {
                present.add(entity.getUUID());
                if (!route.entities.contains(entity.getUUID())) {
                    route.addedEntities.add(entity.getUUID());
                    WormholesClient client = WormholesClient.instance();
                    if (client != null && client.tickState().entities() != null) {
                        client.tickState().entities().suppress(route.key, entity.getUUID());
                    }
                }
            }
        }
        for (UUID id : route.entities) {
            if (!present.contains(id)) {
                route.removedEntities.add(id);
            }
        }
        route.entities.clear();
        route.entities.addAll(present);
    }

    private Derived capture(ClientViewSession session, Route route, long display) throws ClientViewProtocolException {
        int x = SectionPos.x(display) << 4;
        int y = SectionPos.y(display) << 4;
        int z = SectionPos.z(display) << 4;
        Set<Long> dependencies = new HashSet<>(27);
        Long2ObjectOpenHashMap<Snapshot> sourceSamples = new Long2ObjectOpenHashMap<>(27);
        if (route.local) {
            for (int dx : new int[] {-8, 23}) {
                for (int dy : new int[] {-8, 23}) {
                    for (int dz : new int[] {-8, 23}) {
                        int sx = route.cells.destinationX(x + dx, y + dy, z + dz);
                        int sz = route.cells.destinationZ(x + dx, y + dy, z + dz);
                        if (chunk(sx >> 4, sz >> 4) == null) {
                            return null;
                        }
                    }
                }
            }
        }
        ClientPalette palette = session.palette();
        int[] ids = new int[4096];
        byte[] block = new byte[2048];
        byte[] sky = new byte[2048];
        List<Brick.BlockEntityCell> entities = new ArrayList<>();
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int cell = 0; cell < ids.length; cell++) {
            position.set(route.cells.destinationX(x + (cell & 15), y + (cell >> 8), z + (cell >> 4 & 15)),
                route.cells.destinationY(x + (cell & 15), y + (cell >> 8), z + (cell >> 4 & 15)),
                route.cells.destinationZ(x + (cell & 15), y + (cell >> 8), z + (cell >> 4 & 15)));
            Snapshot snapshot = snapshot(route, position, dependencies, sourceSamples);
            if (snapshot == null) {
                return null;
            }
            int source = (position.getY() & 15) << 8 | (position.getZ() & 15) << 4 | position.getX() & 15;
            ids[cell] = palette.localId(snapshot.state(source));
            block[cell >> 1] |= (byte) (snapshot.light(false, position) << ((cell & 1) * 4));
            sky[cell >> 1] |= (byte) (snapshot.light(true, position) << ((cell & 1) * 4));
            BlockEntitySample sample = snapshot.blockEntities.get(source);
            if (sample != null) {
                try {
                    entities.add(new Brick.BlockEntityCell(cell, BlockEntitySample.encode(sample)));
                } catch (IOException failure) {
                    throw new ClientViewProtocolException("Unable to encode local block entity", failure);
                }
            }
        }
        List<String> biomes = new ArrayList<>();
        Map<String, Integer> biomeIds = new HashMap<>();
        byte[] indices = new byte[SectionBiomes.INDEX_BYTES];
        for (int cell = 0; cell < SectionBiomes.CELLS; cell++) {
            int px = x - SectionBiomes.PADDING + (cell & 7) * 4;
            int py = y - SectionBiomes.PADDING + (cell >> 6) * 4;
            int pz = z - SectionBiomes.PADDING + (cell >> 3 & 7) * 4;
            position.set(route.cells.destinationX(px, py, pz), route.cells.destinationY(px, py, pz), route.cells.destinationZ(px, py, pz));
            Snapshot snapshot = snapshot(route, position, dependencies, sourceSamples);
            if (snapshot == null) {
                return null;
            }
            String biome = snapshot.biomes[(position.getY() & 15) >> 2 << 4 | (position.getZ() & 15) >> 2 << 2 | (position.getX() & 15) >> 2];
            Integer id = biomeIds.get(biome);
            if (id == null) {
                id = biomes.size();
                biomes.add(biome);
                biomeIds.put(biome, id);
            }
            indices[cell * 2] = (byte) id.intValue();
            indices[cell * 2 + 1] = (byte) (id >> 8);
        }
        Brick brick = BrickCodec.pack(0, ids).withLight(block, sky).withBlockEntities(entities.toArray(Brick.BlockEntityCell[]::new));
        ClientViewMessage.MeshSection message = new ClientViewMessage.MeshSection(route.key, route.generation, SectionPos.x(display),
            SectionPos.y(display), SectionPos.z(display), 1, 0, brick, new SectionBiomes(biomes, biomes.size() > 1 ? indices : new byte[0]));
        return new Derived(session.meshes().localSection(message), dependencies);
    }

    private Snapshot snapshot(Route route, BlockPos position, Set<Long> dependencies, Long2ObjectOpenHashMap<Snapshot> sourceSamples) {
        long key = SectionPos.asLong(position.getX() >> 4, position.getY() >> 4, position.getZ() >> 4);
        Snapshot captured = sourceSamples.get(key);
        if (captured != null) {
            return captured;
        }
        dependencies.add(key);
        SnapshotKey identity = new SnapshotKey(route.world, key);
        Snapshot previous = snapshots.get(identity);
        if (!route.local) {
            if (previous != null) {
                sourceSamples.put(key, previous);
            }
            return previous;
        }
        LevelChunk chunk = chunk(position.getX() >> 4, position.getZ() >> 4);
        if (chunk == null) {
            return null;
        }
        if (previous != null && previous.chunk.get() == chunk) {
            sourceSamples.put(key, previous);
            return previous;
        }
        Snapshot next = Snapshot.capture(level, chunk, SectionPos.y(key));
        store(identity, next);
        sourceSamples.put(key, next);
        return next;
    }

    private LevelChunk chunk(int x, int z) {
        return level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
    }

    private void retract(ClientViewSession session) {
        for (Route route : routes.values()) {
            release(session, route);
        }
    }

    private void release(ClientViewSession session, Route route) {
        for (long section : route.derived.keySet()) {
            if (route.local) {
                session.meshes().local(route.key, section, null);
            }
            if (route.local && route.inBounds(section)) {
                route.removed.add(coordinate(section));
            }
        }
        route.removedEntities.addAll(route.entities);
        if (session.active() && session.has(ClientViewCapability.LOCAL_MESH)) {
            flush(route, false, route.removed, route.removedEntities);
        }
    }

    private void flush(Route route, boolean available, List<ClientViewMessage.MeshCoordinate> sections, List<UUID> entities) {
        while (!sections.isEmpty() || !entities.isEmpty()) {
            int sectionCount = Math.min(sections.size(), ClientViewMessage.MeshLocal.MAX_SECTIONS);
            int entityCount = Math.min(entities.size(), ClientViewMessage.MeshLocal.MAX_ENTITIES);
            sender.accept(new ClientViewMessage.MeshLocal(route.key, route.generation, ++route.sequence, available,
                sections.subList(0, sectionCount), entities.subList(0, entityCount)));
            sections.subList(0, sectionCount).clear();
            entities.subList(0, entityCount).clear();
        }
    }

    private static ClientViewMessage.MeshCoordinate coordinate(long key) {
        return new ClientViewMessage.MeshCoordinate(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
    }

    private static final class Route {
        private final int key;
        private final int generation;
        private final ClientMeshSections.View view;
        private final ClientViewEnvironment.Transform transform;
        private final ClientViewBlockTransform cells;
        private final String world;
        private final boolean local;
        private final List<ClientViewMessage.MeshClaim> cached = new ArrayList<>();
        private final List<Long> selection = new ArrayList<>();
        private final LongOpenHashSet selected = new LongOpenHashSet();
        private final LongLinkedOpenHashSet dirty = new LongLinkedOpenHashSet();
        private final LongOpenHashSet refreshing = new LongOpenHashSet();
        private final Long2ObjectOpenHashMap<Derived> derived = new Long2ObjectOpenHashMap<>();
        private final Set<UUID> entities = new HashSet<>();
        private final List<ClientViewMessage.MeshCoordinate> added = new ArrayList<>();
        private final List<ClientViewMessage.MeshCoordinate> removed = new ArrayList<>();
        private final List<UUID> addedEntities = new ArrayList<>();
        private final List<UUID> removedEntities = new ArrayList<>();
        private int cursor;
        private int sequence;
        private GeometryVector eye;
        private long plannedTick;
        private boolean replanned;

        private boolean inBounds(long section) {
            PlateBox bounds = view.bounds();
            long x = (long) SectionPos.x(section) << 4;
            long y = (long) SectionPos.y(section) << 4;
            long z = (long) SectionPos.z(section) << 4;
            return x + 16 > bounds.minX() && x < (long) bounds.minX() + bounds.sizeX()
                && y + 16 > bounds.minY() && y < (long) bounds.minY() + bounds.sizeY()
                && z + 16 > bounds.minZ() && z < (long) bounds.minZ() + bounds.sizeZ();
        }

        private void enqueue(long key) {
            refreshing.add(key);
            dirty.add(key);
        }

        private Route(ClientPortal portal, ClientMeshSections.View view, ClientViewEnvironment.Transform transform, GeometryVector eye, long tick, String world, boolean local) {
            this.world = world;
            this.local = local;
            this.key = portal.portalKey();
            this.view = view;
            this.generation = view.generation();
            this.transform = transform;
            this.cells = new ClientViewBlockTransform(transform);
            plan(portal.geometry(), eye, tick);
        }

        private void plan(ClientPortalGeometry geometry, GeometryVector eye, long tick) {
            this.eye = eye;
            plannedTick = tick;
            selection.clear();
            selected.clear();
            for (ClientMeshPlan.Section section : ClientMeshPlan.visible(geometry, eye)) {
                long key = SectionPos.asLong(section.x(), section.y(), section.z());
                if (inBounds(key)) {
                    selection.add(key);
                    selected.add(key);
                }
            }
            cursor = 0;
            replanned = true;
        }
    }

    private static String world(ClientLevel level) {
        return level.dimension().identifier().toString();
    }

    private void enqueueSnapshot(ClientLevel world, long section) {
        SnapshotKey key = new SnapshotKey(world(world), section);
        pendingSnapshots.put(key, new WeakReference<>(world));
        while (pendingSnapshots.size() > 8192) {
            pendingSnapshots.remove(pendingSnapshots.keySet().iterator().next());
        }
    }

    private void capturePending() {
        long deadline = System.nanoTime() + 1_000_000L;
        int captured = 0;
        for (Iterator<Map.Entry<SnapshotKey, WeakReference<ClientLevel>>> iterator = pendingSnapshots.entrySet().iterator();
             iterator.hasNext() && captured < 4 && System.nanoTime() < deadline;) {
            Map.Entry<SnapshotKey, WeakReference<ClientLevel>> entry = iterator.next();
            SnapshotKey key = entry.getKey();
            ClientLevel current = entry.getValue().get();
            iterator.remove();
            if (current != level) {
                continue;
            }
            LevelChunk chunk = current.getChunkSource().getChunk(SectionPos.x(key.section), SectionPos.z(key.section), ChunkStatus.FULL, false);
            if (chunk == null) {
                continue;
            }
            store(key, Snapshot.capture(current, chunk, SectionPos.y(key.section)));
            captured++;
        }
    }

    private void store(SnapshotKey key, Snapshot snapshot) {
        Snapshot previous = snapshots.remove(key);
        if (previous != null) {
            snapshotBytes -= previous.bytes;
        }
        while (snapshotBytes + snapshot.bytes > SNAPSHOT_BUDGET && !snapshots.isEmpty()) {
            Snapshot removed = snapshots.remove(snapshots.keySet().iterator().next());
            snapshotBytes -= removed.bytes;
        }
        snapshots.put(key, snapshot);
        snapshotBytes += snapshot.bytes;
    }

    private record SnapshotKey(String world, long section) {
    }

    private record Derived(ClientMeshSections.Section section, Set<Long> dependencies) {
        private boolean usesChunk(int x, int z) {
            for (long dependency : dependencies) {
                if (SectionPos.x(dependency) == x && SectionPos.z(dependency) == z) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Snapshot(WeakReference<LevelChunk> chunk, LevelChunkSection states, boolean outside,
                            MinecraftLightSnapshot lighting, Map<Integer, BlockEntitySample> blockEntities, String[] biomes, long bytes) {
        private BlockState state(int cell) {
            return outside ? Blocks.AIR.defaultBlockState() : states.getBlockState(cell & 15, cell >> 8, cell >> 4 & 15);
        }

        private int light(boolean sky, BlockPos position) {
            int light = lighting.light(position.getX(), position.getY(), position.getZ());
            return sky ? light >> 4 & 15 : light & 15;
        }

        private static Snapshot capture(ClientLevel level, LevelChunk chunk, int sectionY) {
            Map<Integer, BlockEntitySample> entities = new HashMap<>();
            String[] biomes = new String[64];
            boolean outside = level.isOutsideBuildHeight(sectionY << 4);
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(Math.max(level.getMinY(), Math.min(sectionY << 4, level.getMaxY())))).copy();
            for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                BlockPos position = entry.getKey();
                if (position.getY() >> 4 != sectionY) {
                    continue;
                }
                BlockEntity entity = entry.getValue();
                String type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString();
                BlockEntitySample sample = BlockEntitySanitizer.sanitize(type, entity.saveWithFullMetadata(level.registryAccess()),
                    new BlockEntitySanitizer.Options<>(List.of(type), true, MinecraftBlockEntityTags.INSTANCE));
                if (sample != null) {
                    int cell = (position.getY() & 15) << 8 | (position.getZ() & 15) << 4 | position.getX() & 15;
                    entities.put(cell, sample);
                }
            }
            for (int cell = 0; cell < biomes.length; cell++) {
                biomes[cell] = section.getNoiseBiome(cell & 3, cell >> 4, cell >> 2 & 3).unwrapKey().orElseThrow().identifier().toString();
            }
            MinecraftLightSnapshot light = MinecraftLightSnapshot.capture(level,
                new PlateBox(chunk.getPos().x() << 4, sectionY << 4, chunk.getPos().z() << 4, 16, 16, 16));
            long bytes = 32_768L;
            for (BlockEntitySample sample : entities.values()) {
                bytes += sample.bytes() + 48L;
            }
            return new Snapshot(new WeakReference<>(chunk), section, outside, light, entities, biomes, bytes);
        }
    }
}
