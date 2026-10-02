package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.render.client.ClientViewBlockTransform;
import net.minecraft.world.phys.Vec3;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.EnchantingTableBlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import org.slf4j.Logger;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class ClientMeshEntities {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadLocal<ClientMeshEntities> ACTIVE = new ThreadLocal<>();
    private final ClientMeshSections.View view;
    private final ClientLevel level;
    private final Long2ObjectOpenHashMap<SectionEntities> sections = new Long2ObjectOpenHashMap<>();
    private final ArrayList<BlockEntity> activeBlockEntities = new ArrayList<>();
    private SceneCamera sceneCamera;
    private ClientViewEnvironment.Transform transform;
    private ClientViewBlockTransform cells;
    private boolean destinationQueries;
    private final BlockPos.MutableBlockPos queryPosition = new BlockPos.MutableBlockPos();
    private long synchronizedRevision = -1;
    private long animationTick = Long.MIN_VALUE;
    private List<BlockEntityRenderState> blockStates = List.of();
    private List<EntityRenderState> entityStates = List.of();

    public ClientMeshEntities(ClientMeshSections.View view, ClientLevel level) {
        this.view = view;
        this.level = level;
    }

    public static ClientMeshEntities active() {
        return ACTIVE.get();
    }

    public static ClientMeshEntities active(Object level) {
        ClientMeshEntities active = ACTIVE.get();
        return active != null && active.level == level ? active : null;
    }

    public static boolean hiddenFromWorld(Entity entity) {
        WormholesClient client = WormholesClient.instance();
        if (client == null) {
            return false;
        }
        ClientProjectedEntities projected = client.tickState().entities();
        return projected != null && projected.meshEntity(entity.getId()) || client.reflections().meshEntity(entity.getId());
    }

    public static boolean interactionTarget(Entity entity) {
        return EntitySelector.CAN_BE_PICKED.test(entity) && !hiddenFromWorld(entity);
    }

    public void extract(int portalKey, Camera camera, float partialTick, ClientViewEnvironment.Transform transform) {
        synchronize(transform);
        ArrayList<BlockEntityRenderState> blocks = new ArrayList<>(activeBlockEntities.size());
        ArrayList<EntityRenderState> entities = new ArrayList<>();
        Minecraft minecraft = Minecraft.getInstance();
        BlockEntityRenderDispatcher blockRenderer = minecraft.getBlockEntityRenderDispatcher();
        EntityRenderDispatcher entityRenderer = minecraft.getEntityRenderDispatcher();
        ClientMeshEntities previous = ACTIVE.get();
        ACTIVE.set(this);
        try {
            if (sceneCamera == null) {
                sceneCamera = new SceneCamera();
            }
            sceneCamera.update(camera, space(portalKey), transform);
            blockRenderer.prepare(sceneCamera.position());
            entityRenderer.prepare(sceneCamera, minecraft.crosshairPickEntity);
            inDestinationWorld(() -> {
                animate(level.getGameTime());
                extractBlocks(blockRenderer, partialTick, blocks);
                extractEntities(portalKey, entityRenderer, partialTick, entities);
            });
        } finally {
            blockRenderer.prepare(camera.position());
            entityRenderer.prepare(camera, minecraft.crosshairPickEntity);
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
        blockStates = List.copyOf(blocks);
        entityStates = List.copyOf(entities);
    }

    public List<BlockEntityRenderState> blockEntities() {
        return blockStates;
    }

    public List<EntityRenderState> entities() {
        return entityStates;
    }

    public BlockState blockState(BlockPos position) {
        position = displayPosition(position);
        ClientMeshSections.Section section = view.section(SectionPos.asLong(position));
        return section == null ? Blocks.AIR.defaultBlockState() : section.state(cell(position));
    }

    public BlockEntity blockEntity(BlockPos position) {
        position = displayPosition(position);
        SectionEntities section = sections.get(SectionPos.asLong(position));
        return section == null ? null : section.entities.get(position.asLong());
    }

    public int brightness(LightLayer layer, BlockPos position) {
        position = displayPosition(position);
        ClientMeshSections.Section section = view.section(SectionPos.asLong(position));
        int light = section == null ? -1 : section.light(layer == LightLayer.SKY, cell(position));
        return light >= 0 ? light : destinationQueries ? 0 : level.getLightEngine().getLayerListener(layer).getLightValue(position);
    }

    public int rawBrightness(BlockPos position, int skyDarken) {
        return Math.max(brightness(LightLayer.BLOCK, position), brightness(LightLayer.SKY, position) - skyDarken);
    }

    void synchronize(ClientViewEnvironment.Transform transform) {
        if (!transform.equals(this.transform)) {
            this.transform = transform;
            cells = new ClientViewBlockTransform(transform);
            sections.clear();
            synchronizedRevision = -1;
            animationTick = Long.MIN_VALUE;
        }
        long revision = view.contentRevision();
        if (synchronizedRevision == revision) {
            return;
        }
        for (LongIterator iterator = sections.keySet().iterator(); iterator.hasNext();) {
            if (view.section(iterator.nextLong()) == null) {
                iterator.remove();
            }
        }
        for (LongIterator iterator = view.sectionKeys().iterator(); iterator.hasNext();) {
            long key = iterator.nextLong();
            ClientMeshSections.Section snapshot = view.section(key);
            SectionEntities cached = sections.get(key);
            if (cached != null && cached.snapshot == snapshot) {
                continue;
            }
            Long2ObjectOpenHashMap<BlockEntity> entities = new Long2ObjectOpenHashMap<>();
            for (int cell = 0; cell < 4096; cell++) {
                BlockState state = snapshot.state(cell);
                if (!(state.getBlock() instanceof EntityBlock block)) {
                    continue;
                }
                BlockPos position = new BlockPos((SectionPos.x(key) << 4) + (cell & 15),
                    (SectionPos.y(key) << 4) + (cell >> 8), (SectionPos.z(key) << 4) + ((cell >> 4) & 15));
                BlockPos nativePosition = new BlockPos(cells.destinationX(position.getX(), position.getY(), position.getZ()),
                    cells.destinationY(position.getX(), position.getY(), position.getZ()), cells.destinationZ(position.getX(), position.getY(), position.getZ()));
                BlockEntity entity = block.newBlockEntity(nativePosition, state);
                if (entity == null) {
                    continue;
                }
                entity.setLevel(level);
                BlockEntitySample sample = snapshot.blockEntity(cell);
                if (sample != null) {
                    load(entity, sample);
                }
                entities.put(position.asLong(), entity);
            }
            sections.put(key, new SectionEntities(snapshot, entities));
        }
        activeBlockEntities.clear();
        for (SectionEntities section : sections.values()) {
            activeBlockEntities.addAll(section.entities.values());
        }
        synchronizedRevision = revision;
    }

    void animate(long tick) {
        if (animationTick == tick) {
            return;
        }
        animationTick = tick;
        for (BlockEntity entity : activeBlockEntities) {
            BlockPos position = entity.getBlockPos();
            BlockState state = entity.getBlockState();
            if (entity instanceof EnchantingTableBlockEntity table) {
                EnchantingTableBlockEntity.bookAnimationTick(level, position, state, table);
            } else if (entity instanceof SkullBlockEntity skull) {
                SkullBlockEntity.animation(level, position, state, skull);
            } else if (entity instanceof ChestBlockEntity chest) {
                ChestBlockEntity.lidAnimateTick(level, position, state, chest);
            } else if (entity instanceof EnderChestBlockEntity chest) {
                EnderChestBlockEntity.lidAnimateTick(level, position, state, chest);
            }
        }
    }

    void inDestinationWorld(Runnable extraction) {
        ClientMeshEntities previous = ACTIVE.get();
        boolean previousQueries = destinationQueries;
        ACTIVE.set(this);
        destinationQueries = true;
        try {
            extraction.run();
        } finally {
            destinationQueries = previousQueries;
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
    }

    private BlockPos displayPosition(BlockPos position) {
        if (!destinationQueries) {
            return position;
        }
        int x = position.getX();
        int y = position.getY();
        int z = position.getZ();
        return queryPosition.set(cells.displayX(x, y, z), cells.displayY(x, y, z), cells.displayZ(x, y, z));
    }

    private void extractBlocks(BlockEntityRenderDispatcher dispatcher, float partialTick, List<BlockEntityRenderState> states) {
        for (BlockEntity entity : activeBlockEntities) {
            BlockEntityRenderer<BlockEntity, BlockEntityRenderState> renderer = dispatcher.getRenderer(entity);
            if (renderer == null) {
                continue;
            }
            BlockEntityRenderState state = dispatcher.tryExtractRenderState(entity, partialTick, null, renderer.shouldRenderOffScreen());
            if (state != null) {
                states.add(state);
            }
        }
    }

    private void load(BlockEntity entity, BlockEntitySample sample) {
        if (!BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString().equals(sample.typeKey())) {
            return;
        }
        try {
            CompoundTag tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(sample.nbt())),
                NbtAccounter.create(BlockEntitySample.MAX_NBT_BYTES * 16L));
            entity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Unable to load portal block entity {} at {}", sample.typeKey(), entity.getBlockPos(), failure);
        }
    }

    private void extractEntities(int portalKey, EntityRenderDispatcher renderer, float partialTick, List<EntityRenderState> states) {
        WormholesClient client = WormholesClient.instance();
        if (client == null) {
            return;
        }
        ClientProjectedEntities projected = client.tickState().entities();
        if (projected != null) {
            projected.forEachEntity(portalKey, id -> extractEntity(level.getEntity(id), renderer, partialTick, states));
        }
        extractEntity(client.reflections().entity(portalKey), renderer, partialTick, states);
    }

    private static void extractEntity(Entity entity, EntityRenderDispatcher renderer, float partialTick, List<EntityRenderState> states) {
        if (entity != null && !entity.isRemoved()) {
            states.add(renderer.extractEntity(entity, partialTick));
        }
    }

    private static List<ClientViewEnvironment.Transform> space(int portalKey) {
        WormholesClient client = WormholesClient.instance();
        if (client == null) {
            return List.of();
        }
        List<ClientViewEnvironment.Transform> ancestors = new ArrayList<>();
        ClientPortal portal = client.session().portal(portalKey);
        for (int depth = 0; depth < ClientViewProtocol.MAX_GEOMETRY_DEPTH && portal != null
            && portal.geometry().parentPortalKey() != 0; depth++) {
            int parent = portal.geometry().parentPortalKey();
            ClientViewEnvironment environment = client.session().environment(parent);
            if (environment == null) {
                return List.of();
            }
            ancestors.addFirst(environment.transform());
            portal = client.session().portal(parent);
        }
        return ancestors;
    }

    static GeometryVector contentPoint(List<ClientViewEnvironment.Transform> ancestors, double x, double y, double z) {
        GeometryVector point = new GeometryVector(x, y, z);
        for (ClientViewEnvironment.Transform transform : ancestors) {
            point = transform.destinationPoint(point.x(), point.y(), point.z());
        }
        return point;
    }

    private static int cell(BlockPos position) {
        return ((position.getY() & 15) << 8) | ((position.getZ() & 15) << 4) | (position.getX() & 15);
    }

    private static final class SceneCamera extends Camera {
        private final Vector3f forward = new Vector3f();
        private final Vector3f up = new Vector3f();
        private final Vector3f left = new Vector3f();
        private Camera source;

        void update(Camera source, List<ClientViewEnvironment.Transform> space, ClientViewEnvironment.Transform destination) {
            this.source = source;
            Vec3 eye = source.position();
            GeometryVector point = contentPoint(space, eye.x, eye.y, eye.z);
            GeometryVector nativeEye = destination.destinationPoint(point.x(), point.y(), point.z());
            setPosition(nativeEye.x(), nativeEye.y(), nativeEye.z());
            transform(space, destination, eye, source.forwardVector(), forward);
            transform(space, destination, eye, source.upVector(), up);
            forward.cross(up, left).negate().normalize();
            float yaw = (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));
            float pitch = (float) Math.toDegrees(Math.asin(-forward.y));
            setRotation(yaw, pitch);
            rotation().identity().lookAlong(forward, up).conjugate();
            setEntity(source.entity());
        }

        @Override
        public boolean isDetached() {
            return source.isDetached();
        }

        @Override
        public boolean isInitialized() {
            return source.isInitialized();
        }

        @Override
        public Vector3fc forwardVector() {
            return forward;
        }

        @Override
        public Vector3fc upVector() {
            return up;
        }

        @Override
        public Vector3fc leftVector() {
            return left;
        }

        private void transform(List<ClientViewEnvironment.Transform> space, ClientViewEnvironment.Transform destination, Vec3 eye, Vector3fc direction, Vector3f result) {
            GeometryVector point = contentPoint(space, eye.x + direction.x(), eye.y + direction.y(), eye.z + direction.z());
            GeometryVector nativePoint = destination.destinationPoint(point.x(), point.y(), point.z());
            result.set((float) (nativePoint.x() - position().x), (float) (nativePoint.y() - position().y), (float) (nativePoint.z() - position().z)).normalize();
        }
    }

    private record SectionEntities(ClientMeshSections.Section snapshot, Long2ObjectOpenHashMap<BlockEntity> entities) {
    }
}
