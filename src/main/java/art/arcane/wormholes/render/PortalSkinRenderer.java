package art.arcane.wormholes.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;

import io.github.retrooper.packetevents.util.SpigotConversionUtil;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalSurfaceSkins;
import art.arcane.wormholes.util.Axis;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class PortalSkinRenderer {
    static final int MAX_PER_CELL_PANES = 128;
    private static final int FULL_BRIGHT = (15 << 4) | (15 << 20);
    private static final float VIEW_RANGE = 64.0F;
    private static final AtomicInteger NEXT_SKIN_ID = new AtomicInteger(1_800_000_000);

    private final ProjectionClaimArbiter claimArbiter;
    private final Map<UUID, Map<UUID, PortalSkinState>> instances;
    private final Set<UUID> reconcilingObservers;
    private final Map<String, BlockData> blockDataCache;
    private final Map<BlockData, Integer> globalIdCache;

    public PortalSkinRenderer(ProjectionClaimArbiter claimArbiter) {
        this.claimArbiter = claimArbiter;
        this.instances = new ConcurrentHashMap<UUID, Map<UUID, PortalSkinState>>();
        this.reconcilingObservers = ConcurrentHashMap.newKeySet();
        this.blockDataCache = new ConcurrentHashMap<String, BlockData>();
        this.globalIdCache = new ConcurrentHashMap<BlockData, Integer>();
    }

    public boolean isActive() {
        return !instances.isEmpty();
    }

    public void tick(List<ILocalPortal> skinnedPortals, List<Player> onlinePlayers) {
        if (skinnedPortals.isEmpty() && instances.isEmpty()) {
            return;
        }
        Map<UUID, ILocalPortal> skinnedById = new HashMap<UUID, ILocalPortal>(skinnedPortals.size() * 2);
        for (ILocalPortal portal : skinnedPortals) {
            if (portal.getId() != null) {
                skinnedById.put(portal.getId(), portal);
            }
        }
        for (Player observer : onlinePlayers) {
            UUID observerId = observer.getUniqueId();
            if (!reconcilingObservers.add(observerId)) {
                continue;
            }
            boolean scheduled = FoliaScheduler.runEntity(Wormholes.instance, observer, () -> {
                try {
                    reconcileObserver(observer, skinnedById);
                } finally {
                    reconcilingObservers.remove(observerId);
                }
            });
            if (!scheduled) {
                reconcilingObservers.remove(observerId);
            }
        }
    }

    public void discardObserver(UUID observerId) {
        instances.remove(observerId);
    }

    public void shutdown() {
        instances.clear();
        reconcilingObservers.clear();
    }

    private void reconcileObserver(Player observer, Map<UUID, ILocalPortal> skinnedById) {
        if (observer == null || !observer.isOnline()) {
            return;
        }
        UUID observerId = observer.getUniqueId();
        World world = observer.getWorld();
        if (world == null) {
            return;
        }
        Location location = observer.getLocation();
        Map<UUID, PortalSkinState> current = instances.get(observerId);

        if (current != null && anyFluid(current)) {
            claimArbiter.retryPending(observer, world);
        }

        Set<UUID> eligible = new HashSet<UUID>();
        for (ILocalPortal portal : skinnedById.values()) {
            World portalWorld = portal.getWorld();
            if (portalWorld == null || !world.getUID().equals(portalWorld.getUID())) {
                continue;
            }
            AxisAlignedBB view = portal.getView();
            if (view == null || !view.contains(location)) {
                continue;
            }
            String skin = portal.getSurfaceSkin();
            SkinRenderMode mode = skinRenderMode(skin);
            if (mode == SkinRenderMode.NONE) {
                continue;
            }
            UUID portalId = portal.getId();
            eligible.add(portalId);
            String key = stateKey(portal);
            PortalSkinState existing = current == null ? null : current.get(portalId);
            if (existing != null && existing.stateKey().equals(key)) {
                if (existing.mode() == SkinRenderMode.FLUID_CLAIMS) {
                    submitFluidClaims(observer, portal, world, skin);
                }
                continue;
            }
            if (existing != null) {
                teardown(observer, existing, world);
            }
            PortalSkinState spawned = spawn(observer, portal, mode, skin, world, key);
            if (spawned == null) {
                if (current != null) {
                    current.remove(portalId);
                }
                continue;
            }
            if (current == null) {
                current = new ConcurrentHashMap<UUID, PortalSkinState>();
                Map<UUID, PortalSkinState> raced = instances.putIfAbsent(observerId, current);
                if (raced != null) {
                    current = raced;
                }
            }
            current.put(portalId, spawned);
        }

        if (current == null) {
            return;
        }
        for (UUID portalId : new ArrayList<UUID>(current.keySet())) {
            if (eligible.contains(portalId)) {
                continue;
            }
            PortalSkinState stale = current.remove(portalId);
            if (stale != null) {
                teardown(observer, stale, world);
            }
        }
        if (current.isEmpty()) {
            instances.remove(observerId, current);
        }
    }

    private PortalSkinState spawn(Player observer, ILocalPortal portal, SkinRenderMode mode, String skin, World world, String stateKey) {
        BlockData data = blockDataFor(skin);
        if (data == null) {
            return null;
        }
        if (mode == SkinRenderMode.FLUID_CLAIMS) {
            return spawnFluid(observer, portal, data, world, stateKey);
        }
        return spawnDisplays(observer, portal, data, stateKey);
    }

    private PortalSkinState spawnFluid(Player observer, ILocalPortal portal, BlockData data, World world, String stateKey) {
        if (!submitFluidClaims(observer, portal, world, portal.getSurfaceSkin())) {
            return null;
        }
        return new PortalSkinState(SkinRenderMode.FLUID_CLAIMS, stateKey, null, portal);
    }

    private boolean submitFluidClaims(Player observer, ILocalPortal portal, World world, String skin) {
        BlockData data = blockDataFor(skin);
        if (data == null) {
            return false;
        }
        PortalStructure structure = portal.getStructure();
        KList<Vector> cells = structure.getBlockPositions();
        if (cells.isEmpty()) {
            return false;
        }
        claimArbiter.submit(observer, portal, world, fluidClaims(cells, data), priorityDistance(observer, portal), false);
        return true;
    }

    static Long2ObjectOpenHashMap<ProjectedBlockClaim> fluidClaims(List<Vector> cells, BlockData data) {
        Long2ObjectOpenHashMap<ProjectedBlockClaim> claims =
            new Long2ObjectOpenHashMap<ProjectedBlockClaim>(Math.max(4, cells.size() * 2));
        for (Vector cell : cells) {
            long key = ProjectionCellKey.pack(cell.getBlockX(), cell.getBlockY(), cell.getBlockZ());
            claims.put(key, new ProjectedBlockClaim(data, null, ProjectedBlockClaim.NO_REMOTE_KEY, false));
        }
        return claims;
    }

    private PortalSkinState spawnDisplays(Player observer, ILocalPortal portal, BlockData data, String stateKey) {
        int globalId = globalIdFor(data);
        if (globalId < 0) {
            return null;
        }
        List<SkinTransform> panes = buildPanes(portal);
        if (panes.isEmpty()) {
            return null;
        }
        int[] ids = new int[panes.size()];
        for (int i = 0; i < panes.size(); i++) {
            int id = NEXT_SKIN_ID.getAndIncrement();
            ids[i] = id;
            sendDisplay(observer, id, panes.get(i), globalId);
        }
        return new PortalSkinState(SkinRenderMode.DISPLAY, stateKey, ids, portal);
    }

    static List<SkinTransform> buildPanes(ILocalPortal portal) {
        PortalStructure structure = portal.getStructure();
        Axis normalAxis = portal.getFrame().getNormal().getAxis();
        double planeCoordinate = axisComponent(portal.getOrigin(), normalAxis);
        double thickness = portal.getSurfaceThickness() / 100.0D;
        KList<Vector> cells = structure.getBlockPositions();
        if (structure.isFullCuboid() || cells.isEmpty() || cells.size() > MAX_PER_CELL_PANES) {
            return List.of(skinTransforms(structure.getArea(), normalAxis, planeCoordinate, thickness));
        }
        List<SkinTransform> panes = new ArrayList<SkinTransform>(cells.size());
        for (Vector cell : cells) {
            int x = cell.getBlockX();
            int y = cell.getBlockY();
            int z = cell.getBlockZ();
            AxisAlignedBB cellBox = new AxisAlignedBB(x, x + 1.0D, y, y + 1.0D, z, z + 1.0D);
            panes.add(skinTransforms(cellBox, normalAxis, planeCoordinate, thickness));
        }
        return panes;
    }

    private void sendDisplay(Player observer, int entityId, SkinTransform transform, int globalId) {
        Vector3d position = new Vector3d(transform.anchorX(), transform.anchorY(), transform.anchorZ());
        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(entityId, Optional.of(UUID.randomUUID()),
            EntityTypes.BLOCK_DISPLAY, position, 0.0F, 0.0F, 0.0F, 0, Optional.empty());
        sendPacket(observer, spawn);
        sendPacket(observer, new WrapperPlayServerEntityMetadata(entityId, displayMetadata(transform, globalId)));
    }

    private static List<EntityData<?>> displayMetadata(SkinTransform transform, int globalId) {
        List<EntityData<?>> metadata = new ArrayList<EntityData<?>>();
        metadata.add(new EntityData<Byte>(0, EntityDataTypes.BYTE, Byte.valueOf((byte) 0)));
        metadata.add(new EntityData<Boolean>(5, EntityDataTypes.BOOLEAN, Boolean.TRUE));
        metadata.add(new EntityData<Integer>(8, EntityDataTypes.INT, Integer.valueOf(0)));
        metadata.add(new EntityData<Integer>(9, EntityDataTypes.INT, Integer.valueOf(0)));
        metadata.add(new EntityData<Integer>(10, EntityDataTypes.INT, Integer.valueOf(0)));
        metadata.add(new EntityData<Vector3f>(11, EntityDataTypes.VECTOR3F,
            new Vector3f((float) transform.translationX(), (float) transform.translationY(), (float) transform.translationZ())));
        metadata.add(new EntityData<Vector3f>(12, EntityDataTypes.VECTOR3F,
            new Vector3f((float) transform.scaleX(), (float) transform.scaleY(), (float) transform.scaleZ())));
        metadata.add(new EntityData<Quaternion4f>(13, EntityDataTypes.QUATERNION, new Quaternion4f(0.0F, 0.0F, 0.0F, 1.0F)));
        metadata.add(new EntityData<Quaternion4f>(14, EntityDataTypes.QUATERNION, new Quaternion4f(0.0F, 0.0F, 0.0F, 1.0F)));
        metadata.add(new EntityData<Byte>(15, EntityDataTypes.BYTE, Byte.valueOf((byte) 0)));
        metadata.add(new EntityData<Integer>(16, EntityDataTypes.INT, Integer.valueOf(FULL_BRIGHT)));
        metadata.add(new EntityData<Float>(17, EntityDataTypes.FLOAT, Float.valueOf(VIEW_RANGE)));
        metadata.add(new EntityData<Float>(20, EntityDataTypes.FLOAT, Float.valueOf(0.0F)));
        metadata.add(new EntityData<Float>(21, EntityDataTypes.FLOAT, Float.valueOf(0.0F)));
        metadata.add(new EntityData<Integer>(23, EntityDataTypes.BLOCK_STATE, Integer.valueOf(globalId)));
        return metadata;
    }

    private void teardown(Player observer, PortalSkinState state, World world) {
        if (state.mode() == SkinRenderMode.FLUID_CLAIMS) {
            claimArbiter.release(observer, state.portal(), world, true);
            return;
        }
        int[] ids = state.displayIds();
        if (ids != null && ids.length > 0) {
            sendPacket(observer, new WrapperPlayServerDestroyEntities(ids));
        }
    }

    private void sendPacket(Player observer, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(observer, packet);
    }

    private BlockData blockDataFor(String skin) {
        BlockData cached = blockDataCache.get(skin);
        if (cached != null) {
            return cached;
        }
        BlockData parsed;
        try {
            parsed = Bukkit.createBlockData(skin);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        blockDataCache.put(skin, parsed);
        return parsed;
    }

    private int globalIdFor(BlockData data) {
        Integer cached = globalIdCache.get(data);
        if (cached != null) {
            return cached.intValue();
        }
        int id;
        try {
            id = SpigotConversionUtil.fromBukkitBlockData(data).getGlobalId();
        } catch (RuntimeException ex) {
            return -1;
        }
        globalIdCache.put(data, Integer.valueOf(id));
        return id;
    }

    private static boolean anyFluid(Map<UUID, PortalSkinState> states) {
        for (PortalSkinState state : states.values()) {
            if (state.mode() == SkinRenderMode.FLUID_CLAIMS) {
                return true;
            }
        }
        return false;
    }

    private static String stateKey(ILocalPortal portal) {
        return portal.getStructure().getRevision() + "|" + portal.getSurfaceSkin() + "|" + portal.getSurfaceThickness();
    }

    private static double priorityDistance(Player observer, ILocalPortal portal) {
        Location eye = observer.getEyeLocation();
        Vector origin = portal.getOrigin();
        Direction normal = portal.getFrame().getNormal();
        double dot = ((eye.getX() - origin.getX()) * normal.x())
            + ((eye.getY() - origin.getY()) * normal.y())
            + ((eye.getZ() - origin.getZ()) * normal.z());
        return Math.abs(dot);
    }

    private static double axisComponent(Vector vector, Axis axis) {
        return switch (axis) {
            case X -> vector.getX();
            case Y -> vector.getY();
            case Z -> vector.getZ();
        };
    }

    static SkinRenderMode skinRenderMode(String skin) {
        if (PortalSurfaceSkins.normalizeSkin(skin).isEmpty()) {
            return SkinRenderMode.NONE;
        }
        if (PortalSurfaceSkins.isFluid(skin)) {
            return SkinRenderMode.FLUID_CLAIMS;
        }
        return SkinRenderMode.DISPLAY;
    }

    static SkinTransform skinTransforms(AxisAlignedBB area, Axis normalAxis, double planeCoordinate, double thickness) {
        double sizeX = normalAxis == Axis.X ? thickness : area.sizeX();
        double sizeY = normalAxis == Axis.Y ? thickness : area.sizeY();
        double sizeZ = normalAxis == Axis.Z ? thickness : area.sizeZ();
        double anchorX = normalAxis == Axis.X ? planeCoordinate : area.getXa();
        double anchorY = normalAxis == Axis.Y ? planeCoordinate : area.getYa();
        double anchorZ = normalAxis == Axis.Z ? planeCoordinate : area.getZa();
        double translationX = normalAxis == Axis.X ? -thickness / 2.0D : 0.0D;
        double translationY = normalAxis == Axis.Y ? -thickness / 2.0D : 0.0D;
        double translationZ = normalAxis == Axis.Z ? -thickness / 2.0D : 0.0D;
        return new SkinTransform(anchorX, anchorY, anchorZ, translationX, translationY, translationZ, sizeX, sizeY, sizeZ);
    }

    enum SkinRenderMode {
        NONE,
        DISPLAY,
        FLUID_CLAIMS
    }

    record SkinTransform(
        double anchorX,
        double anchorY,
        double anchorZ,
        double translationX,
        double translationY,
        double translationZ,
        double scaleX,
        double scaleY,
        double scaleZ) {
    }

    private record PortalSkinState(SkinRenderMode mode, String stateKey, int[] displayIds, ILocalPortal portal) {
    }
}
