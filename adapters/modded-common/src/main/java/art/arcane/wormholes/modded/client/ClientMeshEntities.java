package art.arcane.wormholes.modded.client;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.frame.OpticTransform;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Consumer;

public final class ClientMeshEntities {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadLocal<ClientMeshEntities> ACTIVE = new ThreadLocal<>();
    private final ClientMeshSections.View view;
    private final ClientLevel level;
    private final Long2ObjectOpenHashMap<SectionEntities> sections = new Long2ObjectOpenHashMap<>();
    private final ArrayList<BlockEntity> activeBlockEntities = new ArrayList<>();
    private final IdentityHashMap<EntityRenderState, EntitySource> entitySources = new IdentityHashMap<>();
    private SceneCamera sceneCamera;
    private OpticTransform transform;
    private OpticTransform cells;
    private OpticTransform destinationCells;
    private final int[] cellScratch = new int[3];
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

    public static Predicate<? super Entity> worldEntityPredicate(Entity source, Predicate<? super Entity> selector) {
        WormholesClient client = WormholesClient.instance();
        if (client == null) {
            return selector;
        }
        ClientProjectedEntities projected = client.tickState().entities();
        if ((projected == null || !projected.hasMeshEntities()) && !client.reflections().hasMeshEntities()) {
            return selector;
        }
        if (source != null && hiddenFromWorld(source)) {
            return entity -> false;
        }
        return entity -> !hiddenFromWorld(entity) && selector.test(entity);
    }

    public static Consumer<Entity> worldEntityTick(Consumer<Entity> ticker) {
        WormholesClient client = WormholesClient.instance();
        if (client == null) {
            return ticker;
        }
        ClientProjectedEntities projected = client.tickState().entities();
        if ((projected == null || !projected.hasMeshEntities()) && !client.reflections().hasMeshEntities()) {
            return ticker;
        }
        return entity -> {
            if (!hiddenFromWorld(entity)) {
                ticker.accept(entity);
            }
        };
    }

    public static List<Entity> worldPushableEntities(Entity source, List<Entity> entities) {
        return hiddenFromWorld(source) ? List.of() : entities;
    }

    public static boolean interactionTarget(Entity entity) {
        return EntitySelector.CAN_BE_PICKED.test(entity) && !hiddenFromWorld(entity);
    }

    public void extract(int portalKey, Camera camera, float partialTick, OpticTransform transform) {
        entitySources.clear();
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

    public Predicate<EntityRenderState> entityVisibility(CameraRenderState camera, Frustum frustum,
                                                       OpticTransform transform) {
        if (frustum == null || transform == null) {
            return state -> true;
        }
        DestinationFrustum destinationFrustum = new DestinationFrustum(frustum, transform);
        Vec3d eye = transform.inverse().point(new Vec3d(camera.pos.x, camera.pos.y, camera.pos.z));
        EntityRenderDispatcher renderer = Minecraft.getInstance().getEntityRenderDispatcher();
        return state -> entityVisible(state, renderer, destinationFrustum, eye);
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

    void synchronize(OpticTransform transform) {
        if (!transform.equals(this.transform)) {
            this.transform = transform;
            cells = transform.cellAligned();
            destinationCells = cells.inverse();
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
            if (snapshot.hasEntityBlocks()) {
                for (int cell = 0; cell < 4096; cell++) {
                    BlockState state = snapshot.state(cell);
                    if (!(state.getBlock() instanceof EntityBlock block)) {
                        continue;
                    }
                    BlockPos position = new BlockPos((SectionPos.x(key) << 4) + (cell & 15),
                        (SectionPos.y(key) << 4) + (cell >> 8), (SectionPos.z(key) << 4) + ((cell >> 4) & 15));
                    destinationCells.cellInto(position.getX(), position.getY(), position.getZ(), cellScratch);
                    BlockPos nativePosition = new BlockPos(cellScratch[0], cellScratch[1], cellScratch[2]);
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

    public void tickEntity(Entity entity, OpticTransform transform) {
        synchronize(transform);
        ClientMeshEntities previous = ACTIVE.get();
        boolean previousQueries = destinationQueries;
        ACTIVE.set(this);
        destinationQueries = true;
        try {
            entity.commonTick();
            entity.tick();
        } finally {
            destinationQueries = previousQueries;
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
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

    private boolean entityVisible(EntityRenderState state, EntityRenderDispatcher renderer, DestinationFrustum frustum,
                                  Vec3d eye) {
        EntitySource source = entitySources.get(state);
        if (source == null || state.nameTag != null || state.scoreText != null || state.appearsGlowing()) {
            return true;
        }
        ClientMeshEntities previous = ACTIVE.get();
        boolean previousQueries = destinationQueries;
        if (source.destinationQueries()) {
            ACTIVE.set(this);
            destinationQueries = true;
        } else {
            ACTIVE.remove();
        }
        try {
            frustum.tested = false;
            frustum.visible = false;
            boolean rendered = renderer.shouldRender(source.entity(), frustum, eye.x(), eye.y(), eye.z(), source.partialTick());
            return rendered || !frustum.tested || frustum.visible;
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
        cells.cellInto(x, y, z, cellScratch);
        return queryPosition.set(cellScratch[0], cellScratch[1], cellScratch[2]);
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
            extractProjectedEntities(portalKey, client.session(), projected, Minecraft.getInstance().player, renderer, partialTick, states);
        }
        extractLocalEntities(client.localMeshes().entities(portalKey), renderer, partialTick, states);
        extractEntity(client.reflections().entity(portalKey), renderer, partialTick, states);
    }

    void extractProjectedEntities(int portalKey, ClientViewSession session, ClientProjectedEntities projected, LocalPlayer player,
                                  EntityRenderDispatcher renderer, float partialTick, List<EntityRenderState> states) {
        LocalPlayer self = localSelf(portalKey, session, projected, player);
        int excluded = self == null ? 0 : projected.entityId(portalKey, session.selfEntityId());
        projected.forEachEntity(portalKey, id -> {
            if (id != excluded) {
                extractEntity(level.getEntity(id), renderer, partialTick, states);
            }
        });
        if (self != null) {
            extractLocalSelf(self, renderer, partialTick, states);
        }
    }

    private LocalPlayer localSelf(int portalKey, ClientViewSession session, ClientProjectedEntities projected, LocalPlayer player) {
        UUID id = session.selfEntityId();
        ClientPortal portal = session.portal(portalKey);
        EnvironmentState environment = session.environment(portalKey);
        if (id == null || player == null || portal == null || portal.geometry().mirror()
            || environment == null || !environment.world().dimensionKey().equals(level.dimension().identifier().toString())
            || !projected.presentPlayer(portalKey, id) || player.level() != level) {
            return null;
        }
        return player;
    }

    private void extractLocalSelf(LocalPlayer player, EntityRenderDispatcher renderer, float partialTick, List<EntityRenderState> states) {
        ClientMeshEntities previous = ACTIVE.get();
        ACTIVE.remove();
        try {
            extractEntity(player, renderer, partialTick, states);
        } finally {
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
    }

    void extractLocalEntities(Set<UUID> local, EntityRenderDispatcher renderer, float partialTick, List<EntityRenderState> states) {
        if (local.isEmpty()) {
            return;
        }
        ClientMeshEntities previous = ACTIVE.get();
        ACTIVE.remove();
        try {
            for (Entity entity : level.entitiesForRendering()) {
                if (local.contains(entity.getUUID()) && !hiddenFromWorld(entity)) {
                    extractEntity(entity, renderer, partialTick, states);
                }
            }
        } finally {
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
    }

    private void extractEntity(Entity entity, EntityRenderDispatcher renderer, float partialTick, List<EntityRenderState> states) {
        if (entity != null && !entity.isRemoved()) {
            EntityRenderState state = renderer.extractEntity(entity, partialTick);
            states.add(state);
            entitySources.put(state, new EntitySource(entity, partialTick, ACTIVE.get() == this && destinationQueries));
        }
    }

    private static List<OpticTransform> space(int portalKey) {
        WormholesClient client = WormholesClient.instance();
        if (client == null) {
            return List.of();
        }
        List<OpticTransform> ancestors = new ArrayList<>();
        ClientPortal portal = client.session().portal(portalKey);
        for (int depth = 0; depth < ViewStreamLimits.MAX_GEOMETRY_DEPTH && portal != null
            && portal.geometry().parentPortalKey() != 0; depth++) {
            int parent = portal.geometry().parentPortalKey();
            EnvironmentState environment = client.session().environment(parent);
            if (environment == null) {
                return List.of();
            }
            ancestors.addFirst(environment.transform());
            portal = client.session().portal(parent);
        }
        return ancestors;
    }

    static Vec3d contentPoint(List<OpticTransform> ancestors, double x, double y, double z) {
        Vec3d point = new Vec3d(x, y, z);
        for (OpticTransform transform : ancestors) {
            point = transform.inverse().point(point);
        }
        return point;
    }

    private static int cell(BlockPos position) {
        return ((position.getY() & 15) << 8) | ((position.getZ() & 15) << 4) | (position.getX() & 15);
    }

    private record EntitySource(Entity entity, float partialTick, boolean destinationQueries) {
    }

    static final class DestinationFrustum extends Frustum {
        private final Frustum display;
        private final OpticTransform transform;
        private boolean tested;
        private boolean visible;

        DestinationFrustum(Frustum display, OpticTransform transform) {
            super(display);
            this.display = display;
            this.transform = transform;
        }

        @Override
        public boolean isVisible(AABB bounds) {
            tested = true;
            if (!Double.isFinite(bounds.getSize())) {
                visible = true;
                return true;
            }
            double minX = bounds.minX * transform.permutation().x().x() + bounds.minY * transform.permutation().y().x()
                + bounds.minZ * transform.permutation().z().x() + transform.translationX();
            double minY = bounds.minX * transform.permutation().x().y() + bounds.minY * transform.permutation().y().y()
                + bounds.minZ * transform.permutation().z().y() + transform.translationY();
            double minZ = bounds.minX * transform.permutation().x().z() + bounds.minY * transform.permutation().y().z()
                + bounds.minZ * transform.permutation().z().z() + transform.translationZ();
            double maxX = bounds.maxX * transform.permutation().x().x() + bounds.maxY * transform.permutation().y().x()
                + bounds.maxZ * transform.permutation().z().x() + transform.translationX();
            double maxY = bounds.maxX * transform.permutation().x().y() + bounds.maxY * transform.permutation().y().y()
                + bounds.maxZ * transform.permutation().z().y() + transform.translationY();
            double maxZ = bounds.maxX * transform.permutation().x().z() + bounds.maxY * transform.permutation().y().z()
                + bounds.maxZ * transform.permutation().z().z() + transform.translationZ();
            boolean result = display.isVisible(new AABB(Math.min(minX, maxX), Math.min(minY, maxY), Math.min(minZ, maxZ),
                Math.max(minX, maxX), Math.max(minY, maxY), Math.max(minZ, maxZ)));
            visible |= result;
            return result;
        }
    }

    private static final class SceneCamera extends Camera {
        private final Vector3f forward = new Vector3f();
        private final Vector3f up = new Vector3f();
        private final Vector3f left = new Vector3f();
        private Camera source;

        void update(Camera source, List<OpticTransform> space, OpticTransform destination) {
            this.source = source;
            Vec3 eye = source.position();
            Vec3d point = contentPoint(space, eye.x, eye.y, eye.z);
            Vec3d nativeEye = destination.inverse().point(point);
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

        private void transform(List<OpticTransform> space, OpticTransform destination, Vec3 eye, Vector3fc direction, Vector3f result) {
            Vec3d point = contentPoint(space, eye.x + direction.x(), eye.y + direction.y(), eye.z + direction.z());
            Vec3d nativePoint = destination.inverse().point(point);
            result.set((float) (nativePoint.x() - position().x), (float) (nativePoint.y() - position().y), (float) (nativePoint.z() - position().z)).normalize();
        }
    }

    private record SectionEntities(ClientMeshSections.Section snapshot, Long2ObjectOpenHashMap<BlockEntity> entities) {
    }
}
