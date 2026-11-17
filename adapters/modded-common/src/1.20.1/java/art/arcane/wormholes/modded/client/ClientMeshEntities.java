package art.arcane.wormholes.modded.client;

import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.wormholes.modded.client.render.PortalFeatureRenderer;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnchantmentTableBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The block entities and entities of one streamed scene. Block entities are rebuilt from the streamed sections at
 * their destination cells; entities are the projected, local and reflection entities the session assigns to the
 * portal. While {@link #active()} is this scene, the mesh mixins answer level queries from the streamed sections.
 */
public final class ClientMeshEntities {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadLocal<ClientMeshEntities> ACTIVE = new ThreadLocal<>();
    private final ClientMeshSections.View view;
    private final ClientLevel level;
    private final Long2ObjectOpenHashMap<SectionEntities> sections = new Long2ObjectOpenHashMap<>();
    private final ArrayList<BlockEntity> activeBlockEntities = new ArrayList<>();
    private OpticTransform transform;
    private OpticTransform cells;
    private OpticTransform destinationCells;
    private final int[] cellScratch = new int[3];
    private boolean destinationQueries;
    private final BlockPos.MutableBlockPos queryPosition = new BlockPos.MutableBlockPos();
    private long synchronizedRevision = -1;
    private long animationTick = Long.MIN_VALUE;

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
        if (!(entity.level() instanceof ClientLevel level)) {
            return false;
        }
        WormholesClient client = WormholesClient.instance();
        if (client == null || !client.viewsAttachedTo(level)) {
            return false;
        }
        int entityId = ProjectedEntityGuard.entityId(entity);
        ClientProjectedEntities projected = client.tickState().entities();
        return projected != null && projected.meshEntity(entityId) || client.reflections().meshEntity(entityId);
    }

    public static Predicate<? super Entity> worldEntityPredicate(Entity source, Predicate<? super Entity> selector) {
        if (!meshEntitiesPresent()) {
            return selector;
        }
        if (source != null && hiddenFromWorld(source)) {
            return entity -> false;
        }
        return entity -> !hiddenFromWorld(entity) && selector.test(entity);
    }

    public static Consumer<Entity> worldEntityTick(Consumer<Entity> ticker) {
        if (!meshEntitiesPresent()) {
            return ticker;
        }
        return entity -> {
            if (!hiddenFromWorld(entity)) {
                ticker.accept(entity);
            }
        };
    }

    public static boolean interactionTarget(Entity entity) {
        return !entity.isSpectator() && entity.isPickable() && !hiddenFromWorld(entity);
    }

    private static boolean meshEntitiesPresent() {
        WormholesClient client = WormholesClient.instance();
        if (client == null) {
            return false;
        }
        ClientProjectedEntities projected = client.tickState().entities();
        return projected != null && projected.hasMeshEntities() || client.reflections().hasMeshEntities();
    }

    /** Draws this scene's block entities and entities into the view {@code renderer} is rendering. */
    public void render(int portalKey, PortalFeatureRenderer renderer, OpticTransform transform) {
        synchronize(transform);
        float partialTick = renderer.partialTick();
        inDestinationWorld(() -> {
            animate(level.getGameTime());
            for (BlockEntity entity : activeBlockEntities) {
                renderer.blockEntity(entity, partialTick);
            }
            WormholesClient client = WormholesClient.instance();
            if (client == null) {
                return;
            }
            ClientProjectedEntities projected = client.tickState().entities();
            if (projected != null) {
                projected.forEachEntity(portalKey, id -> renderEntity(level.getEntity(id), renderer, partialTick));
            }
            renderEntity(client.reflections().entity(portalKey), renderer, partialTick);
        });
        renderLocalEntities(client().localMeshes().entities(portalKey), renderer, partialTick);
    }

    private static WormholesClient client() {
        return WormholesClient.instance();
    }

    private void renderLocalEntities(Set<UUID> local, PortalFeatureRenderer renderer, float partialTick) {
        if (local.isEmpty()) {
            return;
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (local.contains(entity.getUUID()) && !hiddenFromWorld(entity)) {
                renderEntity(entity, renderer, partialTick);
            }
        }
    }

    private static void renderEntity(Entity entity, PortalFeatureRenderer renderer, float partialTick) {
        if (entity != null && !entity.isRemoved()) {
            try {
                renderer.entity(entity, partialTick);
            } catch (RuntimeException failure) {
                LOGGER.debug("Skipped portal entity {}", entity, failure);
            }
        }
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
            if (entity instanceof EnchantmentTableBlockEntity table) {
                EnchantmentTableBlockEntity.bookAnimationTick(level, position, state, table);
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
        inDestinationWorld(() -> {
            entity.baseTick();
            entity.tick();
        });
    }

    void inDestinationWorld(Runnable work) {
        ClientMeshEntities previous = ACTIVE.get();
        boolean previousQueries = destinationQueries;
        ACTIVE.set(this);
        destinationQueries = true;
        try {
            work.run();
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
        if (!destinationQueries || cells == null) {
            return position;
        }
        cells.cellInto(position.getX(), position.getY(), position.getZ(), cellScratch);
        return queryPosition.set(cellScratch[0], cellScratch[1], cellScratch[2]);
    }

    private void load(BlockEntity entity, BlockEntitySample sample) {
        if (!BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString().equals(sample.typeKey())) {
            return;
        }
        try {
            CompoundTag tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(sample.nbt())),
                new NbtAccounter(BlockEntitySample.MAX_NBT_BYTES * 16L));
            entity.load(tag);
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Unable to load portal block entity {} at {}", sample.typeKey(), entity.getBlockPos(), failure);
        }
    }

    private static int cell(BlockPos position) {
        return ((position.getY() & 15) << 8) | ((position.getZ() & 15) << 4) | (position.getX() & 15);
    }

    private record SectionEntities(ClientMeshSections.Section snapshot, Long2ObjectOpenHashMap<BlockEntity> entities) {
    }
}
