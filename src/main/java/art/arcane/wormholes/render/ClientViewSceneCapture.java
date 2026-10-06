package art.arcane.wormholes.render;

import art.arcane.optics.entity.EntityProfile;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.client.BrickLightSource;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.PacketBlobs;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.render.client.ClientViewEntityTransform;
import art.arcane.wormholes.render.client.session.ClientViewPlateLight;
import art.arcane.wormholes.render.plate.PlateBox;
import art.arcane.wormholes.render.plate.ViewPlate;
import art.arcane.wormholes.render.view.ProjectionContentView;
import art.arcane.wormholes.render.view.ProjectionEntityView;
import art.arcane.wormholes.render.view.ProjectionWorldView;

public final class ClientViewSceneCapture {
    private static final long BLOB_RECAPTURE_TICKS = 40L;
    private static final long FRAME_RECAPTURE_TICKS = 10L;
    private static final long IDLE_TICKS = 200L;
    private static final String ITEM_FRAME = "minecraft:item_frame";
    private static final String GLOW_ITEM_FRAME = "minecraft:glow_item_frame";
    private static final String PAINTING = "minecraft:painting";

    private final long secret;
    private final ClientViewEntityTransform transform;
    private final ClientViewPlateLight.Cache<BlockData> lights;
    private final Map<UUID, Blobs> blobs;
    private final Map<UUID, Patched> patched;
    private final ConcurrentHashMap<UUID, Source> sources;
    private long sweepTick;

    public ClientViewSceneCapture() {
        this.secret = new SecureRandom().nextLong();
        this.transform = new ClientViewEntityTransform();
        this.lights = new ClientViewPlateLight.Cache<BlockData>();
        this.blobs = new HashMap<UUID, Blobs>();
        this.patched = new HashMap<UUID, Patched>();
        this.sources = new ConcurrentHashMap<UUID, Source>();
    }

    public synchronized List<EntityVisual> entities(ClientViewPortalSource source, ClientViewEntityTransform.Frame frame, long tick, boolean nativeMesh) {
        ProjectionWorldView view = source.destinationView();
        if (frame == null || view == null || !Settings.ENTITY_SPOOFING || Settings.MAX_SPOOFED_ENTITIES <= 0) {
            return List.of();
        }
        double range = Math.min(Settings.ENTITY_SPOOF_RANGE, frame.depth());
        boolean upsideDown = !nativeMesh && transform.upsideDown(frame);
        List<EntityVisual> out = new ArrayList<EntityVisual>();
        if (view instanceof ProjectionEntityView entityView && (source.regionSnapshots() || source.destinationWorld() == null)) {
            List<EntityVisual> visuals = entityView.getEntities(frame.remoteOriginX(), frame.remoteOriginY(), frame.remoteOriginZ(), range);
            for (int i = 0; i < visuals.size() && out.size() < Settings.MAX_SPOOFED_ENTITIES; i++) {
                EntityVisual visual = visuals.get(i);
                project(withProfile(visual, entityView.getProfile(visual.id())), frame, upsideDown, tick, new Source(entityView, visual.id(), true), out, nativeMesh);
            }
        } else if (source.destinationWorld() != null) {
            World world = source.destinationWorld();
            Location center = new Location(world, frame.remoteOriginX(), frame.remoteOriginY(), frame.remoteOriginZ());
            IPortal anchor = source.destinationAnchor();
            ILocalPortal key = anchor instanceof ILocalPortal local ? local : source.portal();
            Collection<Entity> nearby = EntityRenderCaches.nearbyRemoteEntities(key, center, range);
            for (Entity entity : nearby) {
                if (out.size() >= Settings.MAX_SPOOFED_ENTITIES) {
                    break;
                }
                if (!ProjectionEntityFilter.canCapture(entity)) {
                    continue;
                }
                project(capture(entity, tick), frame, upsideDown, tick, new Source(null, entity.getUniqueId(), entity.isVisibleByDefault()), out, nativeMesh);
            }
        }
        sweep(tick);
        return out;
    }

    public UUID projectedId(UUID sourceId) {
        return ClientViewEntityTransform.opaque(secret, sourceId);
    }

    public boolean visible(Player observer, UUID opaqueId) {
        Source source = sources.get(opaqueId);
        if (source == null || observer == null) {
            return true;
        }
        if (source.view() != null) {
            return source.view().isVisibleTo(observer, source.id());
        }
        return WormholesPlatform.isEntityVisible(observer, source.id(), source.visibleByDefault(), Wormholes.instance);
    }

    public boolean isObserver(Player observer, UUID opaqueId) {
        return observer != null && ClientViewEntityTransform.opaque(secret, observer.getUniqueId()).equals(opaqueId);
    }

    public BrickLightSource light(ClientViewPortalSource source, ViewPlate<BlockData> plate, boolean mesh) {
        if (plate == null) {
            return BrickLightSource.NONE;
        }
        ProjectedBlockClaim.LightingPolicy policy = source.lightingPolicy();
        if (!mesh && policy == ProjectedBlockClaim.LightingPolicy.LOCAL) {
            return BrickLightSource.NONE;
        }
        ClientViewEntityTransform.Frame frame = source.transformFrame();
        World world = source.destinationWorld();
        ProjectionWorldView view = source.destinationView();
        if (frame == null || world == null || view == null) {
            return BrickLightSource.NONE;
        }
        boolean fullBright = !mesh && policy == ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT;
        return lights.light(plate, () -> {
            ClientViewPlateLight.Sampler sampler = fullBright ? (x, y, z) -> ProjectionContentView.packLight(15, 15)
                : source.regionSnapshots() ? view::getLight : snapshot(world, ClientViewPlateLight.remoteBox(plate.box(), frame));
            return new ClientViewPlateLight<BlockData>(plate, frame, sampler, fullBright);
        });
    }

    private void project(EntityVisual visual, ClientViewEntityTransform.Frame frame, boolean upsideDown, long tick, Source source,
                         List<EntityVisual> out, boolean nativeMesh) {
        String type = visual.typeKey();
        boolean itemFrame = ITEM_FRAME.equals(type) || GLOW_ITEM_FRAME.equals(type);
        boolean hanging = itemFrame || PAINTING.equals(type);
        ClientViewEntityTransform.Projected projected = nativeMesh ? transform.nativeModel(visual, frame, hanging, secret)
            : transform.project(visual, frame, hanging, itemFrame, secret);
        if (projected == null) {
            return;
        }
        EntityVisual local = patch(projected, visual, upsideDown && !hanging, tick);
        sources.put(local.id(), source.at(tick));
        out.add(local);
    }

    private EntityVisual patch(ClientViewEntityTransform.Projected projected, EntityVisual source, boolean flip, long tick) {
        EntityVisual visual = projected.visual();
        int metadataTransform = projected.metadataTransform();
        byte[] raw = source.metadata();
        boolean map = source.mapData() != null && source.mapData().length > 0;
        if (raw == null || raw.length == 0 || metadataTransform == ProjectedItemFrameTransform.NONE && !flip && !map) {
            return visual;
        }
        Patched cached = patched.get(source.id());
        if (cached != null && cached.source == raw && cached.transform == metadataTransform && cached.flip == flip) {
            cached.touched = tick;
            return withMetadata(visual, cached.bytes);
        }
        List<EntityData<?>> metadata = PacketBlobs.readMetadata(raw);
        metadata = BukkitItemFrameMetadata.TRANSFORM.transformMetadata(metadata, metadataTransform, null, map);
        if (flip) {
            metadata = EntityRenderMetadataBridge.upsideDown(visual.isPlayer(), metadata);
        }
        byte[] bytes = PacketBlobs.writeMetadata(metadata);
        patched.put(source.id(), new Patched(raw, metadataTransform, flip, bytes, tick));
        return withMetadata(visual, bytes);
    }

    private EntityVisual capture(Entity entity, long tick) {
        UUID id = entity.getUniqueId();
        Location location = entity.getLocation();
        Vector look = look(entity, location);
        Vector velocity = entity.getVelocity();
        int signature = signature(entity);
        long metadataRevision = WormholesPlatform.entityMetadataFingerprint(entity);
        boolean onFire = entity.getFireTicks() > 0;
        Blobs previous = blobs.get(id);
        long interval = entity instanceof ItemFrame ? FRAME_RECAPTURE_TICKS : BLOB_RECAPTURE_TICKS;
        Blobs current = previous;
        if (previous == null || tick - previous.tick >= interval || previous.pose != entity.getPose() || previous.onFire != onFire
            || previous.signature != signature || previous.metadataRevision != metadataRevision) {
            String[] textures = entity instanceof Player player ? textures(player) : new String[] {"", ""};
            current = new Blobs(PacketBlobs.captureMetadata(entity), PacketBlobs.captureEquipment(entity), textures, entity.getPose(), onFire,
                signature, metadataRevision, tick);
            blobs.put(id, current);
        }
        current.touched = tick;
        UUID vehicle = entity.getVehicle() == null ? null : entity.getVehicle().getUniqueId();
        UUID leash = null;
        if (entity instanceof LivingEntity living && living.isLeashed()) {
            try {
                Entity holder = living.getLeashHolder();
                leash = holder == null ? null : holder.getUniqueId();
            } catch (IllegalStateException unleashed) {
                leash = null;
            }
        }
        String name = entity instanceof Player player ? player.getName() : "";
        return EntityVisual.full(id, entity.getType().getKey().toString(), location.getX(), location.getY(), location.getZ(), entity.getHeight(),
            look.getX(), look.getY(), look.getZ(), entity instanceof LivingEntity living ? WormholesPlatform.bodyYaw(living, location.getYaw()) : location.getYaw(), location.getPitch(), velocity.getX(), velocity.getY(), velocity.getZ(),
            entity.isOnGround(), name, current.textures[0], current.textures[1], vehicle, leash, current.metadata, current.equipment,
            EntityVisual.EMPTY, 0);
    }

    private void sweep(long tick) {
        if (tick - sweepTick < IDLE_TICKS) {
            return;
        }
        sweepTick = tick;
        blobs.values().removeIf(entry -> tick - entry.touched > IDLE_TICKS);
        patched.values().removeIf(entry -> tick - entry.touched > IDLE_TICKS);
        sources.values().removeIf(entry -> tick - entry.tick() > IDLE_TICKS);
    }

    private static ClientViewPlateLight.Sampler snapshot(World world, PlateBox box) {
        if (box.cells() == 0L) {
            return (x, y, z) -> ClientViewPlateLight.UNAVAILABLE;
        }
        int minChunkX = box.minX() >> 4;
        int minChunkZ = box.minZ() >> 4;
        int sizeX = ((box.minX() + box.sizeX() - 1) >> 4) - minChunkX + 1;
        int sizeZ = ((box.minZ() + box.sizeZ() - 1) >> 4) - minChunkZ + 1;
        ChunkSnapshot[] chunks = new ChunkSnapshot[sizeX * sizeZ];
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                if (world.isChunkLoaded(minChunkX + dx, minChunkZ + dz)) {
                    chunks[dx * sizeZ + dz] = world.getChunkAt(minChunkX + dx, minChunkZ + dz).getChunkSnapshot(false, false, false);
                }
            }
        }
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();
        return (x, y, z) -> {
            int dx = (x >> 4) - minChunkX;
            int dz = (z >> 4) - minChunkZ;
            if (dx < 0 || dz < 0 || dx >= sizeX || dz >= sizeZ || y < minHeight || y >= maxHeight) {
                return ClientViewPlateLight.UNAVAILABLE;
            }
            ChunkSnapshot chunk = chunks[dx * sizeZ + dz];
            if (chunk == null) {
                return ClientViewPlateLight.UNAVAILABLE;
            }
            return ProjectionContentView.packLight(chunk.getBlockSkyLight(x & 15, y, z & 15), chunk.getBlockEmittedLight(x & 15, y, z & 15));
        };
    }

    private static EntityVisual withProfile(EntityVisual visual, EntityProfile profile) {
        if (!visual.isPlayer() || profile == null || profile.textureValue() == null || profile.textureValue().isEmpty()
            || profile.textureValue().equals(visual.textureValue())) {
            return visual;
        }
        return new EntityVisual(visual.mode(), visual.sequence(), visual.presentMask(), visual.id(), visual.typeKey(), visual.x(), visual.y(),
            visual.z(), visual.height(), visual.lookX(), visual.lookY(), visual.lookZ(), visual.yaw(), visual.pitch(), visual.velocityX(),
            visual.velocityY(), visual.velocityZ(), visual.onGround(), profile.name(), profile.textureValue(),
            profile.textureSignature() == null ? "" : profile.textureSignature(), visual.passengerOf(), visual.leashHolder(), visual.metadata(),
            visual.equipment(), visual.mapData());
    }

    private static EntityVisual withMetadata(EntityVisual visual, byte[] metadata) {
        if (Arrays.equals(visual.metadata(), metadata)) {
            return visual;
        }
        return new EntityVisual(visual.mode(), visual.sequence(), visual.presentMask(), visual.id(), visual.typeKey(), visual.x(), visual.y(),
            visual.z(), visual.height(), visual.lookX(), visual.lookY(), visual.lookZ(), visual.yaw(), visual.pitch(), visual.velocityX(),
            visual.velocityY(), visual.velocityZ(), visual.onGround(), visual.playerName(), visual.textureValue(), visual.textureSignature(),
            visual.passengerOf(), visual.leashHolder(), metadata, visual.equipment(), visual.mapData());
    }

    private static Vector look(Entity entity, Location location) {
        if (entity instanceof LivingEntity living) {
            return living.getEyeLocation().getDirection();
        }
        if (entity instanceof Hanging hanging) {
            BlockFace facing = hanging.getFacing();
            return new Vector(facing.getModX(), facing.getModY(), facing.getModZ());
        }
        return location.getDirection();
    }

    private static int signature(Entity entity) {
        if (entity instanceof ItemFrame frame) {
            int signature = frame.getItem().hashCode();
            signature = (31 * signature) + frame.getRotation().ordinal();
            return (31 * signature) + frame.getFacing().ordinal();
        }
        if (!(entity instanceof LivingEntity living)) {
            return 0;
        }
        EntityEquipment equipment = living.getEquipment();
        if (equipment == null) {
            return 0;
        }
        int signature = 1;
        signature = 31 * signature + item(equipment.getHelmet());
        signature = 31 * signature + item(equipment.getChestplate());
        signature = 31 * signature + item(equipment.getLeggings());
        signature = 31 * signature + item(equipment.getBoots());
        signature = 31 * signature + item(equipment.getItemInMainHand());
        return 31 * signature + item(equipment.getItemInOffHand());
    }

    private static int item(ItemStack stack) {
        return stack == null ? 0 : (stack.getType().ordinal() * 31) + stack.getAmount();
    }

    private static String[] textures(Player player) {
        for (TextureProperty property : SpigotReflectionUtil.getUserProfile(player)) {
            if ("textures".equals(property.getName())) {
                return new String[] {property.getValue(), property.getSignature() == null ? "" : property.getSignature()};
            }
        }
        return new String[] {"", ""};
    }

    private record Source(ProjectionEntityView view, UUID id, boolean visibleByDefault, long tick) {
        private Source(ProjectionEntityView view, UUID id, boolean visibleByDefault) {
            this(view, id, visibleByDefault, 0L);
        }

        private Source at(long captured) {
            return new Source(view, id, visibleByDefault, captured);
        }
    }

    private static final class Blobs {
        private final byte[] metadata;
        private final byte[] equipment;
        private final String[] textures;
        private final Pose pose;
        private final boolean onFire;
        private final int signature;
        private final long metadataRevision;
        private final long tick;
        private long touched;

        private Blobs(byte[] metadata, byte[] equipment, String[] textures, Pose pose, boolean onFire, int signature, long metadataRevision, long tick) {
            this.metadata = metadata;
            this.equipment = equipment;
            this.textures = textures;
            this.pose = pose;
            this.onFire = onFire;
            this.signature = signature;
            this.metadataRevision = metadataRevision;
            this.tick = tick;
            this.touched = tick;
        }
    }

    private static final class Patched {
        private final byte[] source;
        private final int transform;
        private final boolean flip;
        private final byte[] bytes;
        private long touched;

        private Patched(byte[] source, int transform, boolean flip, byte[] bytes, long touched) {
            this.source = source;
            this.transform = transform;
            this.flip = flip;
            this.bytes = bytes;
            this.touched = touched;
        }
    }
}
