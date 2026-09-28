package art.arcane.wormholes.modded;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.AmbientOutlineGeometry;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalSurfaceSkins;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.PortalSkinGeometry;
import art.arcane.wormholes.render.PortalSkinGeometry.SkinTransform;
import art.arcane.wormholes.render.ProjectedBlockClaim;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.ProjectionClaimSet;
import art.arcane.wormholes.render.view.ProjectionContentView;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.math.Transformation;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class MinecraftPortalSurfaces implements AutoCloseable {
    private final WormholesModRuntime runtime;
    private final Context context;
    private final Map<UUID, Surface> surfaces = new HashMap<>();
    private final Map<UUID, AmbientOutlineGeometry> outlines = new HashMap<>();

    MinecraftPortalSurfaces(WormholesModRuntime runtime, Context context) {
        this.runtime = runtime;
        this.context = context;
    }

    static boolean interact(WormholesModRuntime runtime, ServerPlayer player, InteractionHand hand) {
        ItemStack item = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND || item.isEmpty() || MinecraftPortalTools.isWand(item)) {
            return false;
        }
        String skin = item.is(Items.WATER_BUCKET) ? "minecraft:water" : item.is(Items.LAVA_BUCKET) ? "minecraft:lava"
            : item.getItem() instanceof BlockItem block ? BlockStateParser.serialize(block.getBlock().defaultBlockState()) : null;
        if (skin == null) {
            return false;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        for (MinecraftPortal portal : runtime.portals().snapshot()) {
            GeometryVector center = portal.getGeometry().getApertureCenter();
            if (runtime.portals().resolveLevel(portal) != player.level()
                || player.position().distanceToSqr(center.x(), center.y(), center.z()) >= 64) {
                continue;
            }
            for (double distance = 0; distance < 16; distance += 0.25) {
                if (portal.getGeometry().contains(new GeometryVector(eye.x + look.x * distance, eye.y + look.y * distance, eye.z + look.z * distance))) {
                    return runtime.menus().cosmetics().applySurfaceSkinFromInteraction(player, portal, skin);
                }
            }
        }
        return false;
    }

    void update(List<MinecraftPortal> portals, MinecraftProjectorPortalAccess access, long tick) {
        ServerPlayer player = context.player();
        Set<UUID> eligible = new HashSet<>();
        Set<UUID> attended = new HashSet<>();
        for (MinecraftPortal portal : portals) {
            if (access.world(portal) != player.level() || !access.view(portal).containsPrimitive(player.getX(), player.getY(), player.getZ())) {
                continue;
            }
            attended.add(portal.getId());
            if (tick % 5L == 0 && runtime.configuration().settings().getMain().enableParticles) {
                particles(portal, tick / 5L);
            }
            String skin = portal.getSurfaceSkin();
            if (skin.isEmpty()) {
                continue;
            }
            eligible.add(portal.getId());
            Surface surface = surfaces.get(portal.getId());
            if (surface == null || !surface.matches(portal, player)) {
                if (surface != null) {
                    remove(surface);
                }
                surface = new Surface(portal, player);
                surfaces.put(portal.getId(), surface);
                populate(surface, portal);
            }
            if (!surface.fluid.isEmpty()) {
                Direction normal = portal.getDirection();
                GeometryVector origin = portal.getOrigin();
                Vec3 eye = player.getEyePosition();
                double distance = Math.abs((eye.x - origin.x()) * normal.x() + (eye.y - origin.y()) * normal.y() + (eye.z - origin.z()) * normal.z());
                context.claims().stagePortalClaims(surface.owner, surface.owner.toString(), distance, surface.fluid, context.staged());
            }
        }
        outlines.keySet().retainAll(attended);
        Iterator<Map.Entry<UUID, Surface>> iterator = surfaces.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Surface> entry = iterator.next();
            if (!eligible.contains(entry.getKey())) {
                remove(entry.getValue());
                iterator.remove();
            }
        }
    }

    private void populate(Surface surface, MinecraftPortal portal) {
        BlockState block;
        try {
            block = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, portal.getSurfaceSkin(), false).blockState();
        } catch (CommandSyntaxException failure) {
            throw new IllegalArgumentException("Invalid skin on portal " + portal.getId(), failure);
        }
        if (PortalSurfaceSkins.isFluid(portal.getSurfaceSkin()) || surface.withholdsDisplays) {
            for (GeometryVector cell : portal.getGeometry().getBlockPositions()) {
                surface.fluid.put(ProjectionCellKey.pack(cell.getBlockX(), cell.getBlockY(), cell.getBlockZ()),
                    new ProjectedBlockClaim<>(block, null, ProjectedBlockClaim.NO_REMOTE_KEY, false));
            }
            return;
        }
        for (SkinTransform pane : PortalSkinGeometry.panes(portal.getGeometry(), portal.getFrame(), portal.getOrigin())) {
            spawn(surface, pane, block);
        }
    }

    private void spawn(Surface surface, SkinTransform pane, BlockState block) {
        ServerPlayer player = context.player();
        CompoundTag data = new CompoundTag();
        data.store("transformation", Transformation.EXTENDED_CODEC, new Transformation(
            new Vector3f((float) pane.translationX(), (float) pane.translationY(), (float) pane.translationZ()), new Quaternionf(),
            new Vector3f((float) pane.scaleX(), (float) pane.scaleY(), (float) pane.scaleZ()), new Quaternionf()));
        data.store("block_state", BlockState.CODEC, block);
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        data.put("brightness", brightness);
        data.putFloat("view_range", 64.0f);
        Display.BlockDisplay display = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, player.level());
        display.load(TagValueInput.create(ProblemReporter.DISCARDING, player.level().registryAccess(), data));
        display.setPos(pane.anchorX(), pane.anchorY(), pane.anchorZ());
        surface.displays.add(display.getId());
        player.connection.send(new ClientboundAddEntityPacket(display.getId(), display.getUUID(), pane.anchorX(), pane.anchorY(), pane.anchorZ(),
            0, 0, EntityTypes.BLOCK_DISPLAY, 0, Vec3.ZERO, 0));
        List<SynchedEntityData.DataValue<?>> values = display.getEntityData().getNonDefaultValues();
        if (values != null && !values.isEmpty()) {
            player.connection.send(new ClientboundSetEntityDataPacket(display.getId(), values));
        }
    }

    private void particles(MinecraftPortal portal, long cursor) {
        AmbientParticleStyle style = portal.getAmbientStyle();
        if (style == AmbientParticleStyle.OFF) {
            return;
        }
        List<GeometryVector> cells = portal.getGeometry().getBlockPositions();
        if (style == AmbientParticleStyle.SPARKS) {
            if (cells.isEmpty()) {
                return;
            }
            for (int index = 0; index < (portal.isOpen() ? 4 : 1); index++) {
                GeometryVector cell = cells.get(context.player().getRandom().nextInt(cells.size()));
                particle(ParticleTypes.MYCELIUM, cell.x() + context.player().getRandom().nextDouble(), cell.y() + context.player().getRandom().nextDouble(), cell.z() + context.player().getRandom().nextDouble());
            }
            return;
        }
        DustParticleOptions dust = new DustParticleOptions(portal.getAmbientColor(), 1.0f);
        if (style == AmbientParticleStyle.CORNERS) {
            AxisAlignedBB area = portal.getGeometry().getArea();
            for (int offset = 0; offset < (portal.isOpen() ? 8 : 2); offset++) {
                int corner = Math.floorMod(cursor + offset, 8);
                particle(dust, (corner & 1) == 0 ? area.getXa() : area.getXb(),
                    (corner & 2) == 0 ? area.getYa() : area.getYb(), (corner & 4) == 0 ? area.getZa() : area.getZb());
            }
            return;
        }
        List<double[]> points = outlines.computeIfAbsent(portal.getId(), ignored -> new AmbientOutlineGeometry())
            .points(portal.getGeometry().getRevision(), portal.getDirection().getAxis(), portal.getGeometry());
        for (int index = 0; index < Math.min(points.size(), portal.isOpen() ? 32 : 8); index++) {
            double[] point = points.get(Math.floorMod(cursor + index, points.size()));
            particle(dust, point[0], point[1], point[2]);
        }
    }

    private void particle(ParticleOptions particle, double x, double y, double z) {
        context.player().connection.send(new ClientboundLevelParticlesPacket(particle, false, false, x, y, z, 0, 0, 0, 0, 1));
    }

    private void remove(Surface surface) {
        context.claims().stagePortalRelease(surface.owner, context.staged());
        if (!surface.displays.isEmpty() && !context.player().hasDisconnected()) {
            int[] ids = new int[surface.displays.size()];
            for (int index = 0; index < ids.length; index++) {
                ids[index] = surface.displays.get(index);
            }
            context.player().connection.send(new ClientboundRemoveEntitiesPacket(ids));
        }
    }

    @Override
    public void close() {
        for (Surface surface : surfaces.values()) {
            remove(surface);
        }
        surfaces.clear();
        outlines.clear();
    }

    record Context(ServerPlayer player, ProjectionClaimSet<ProjectedBlockClaim<BlockState, ProjectionContentView<BlockState, BlockState>>> claims,
                   LongOpenHashSet staged) { }

    private static final class Surface {
        private final UUID owner;
        private final String skin;
        private final long revision;
        private final PortalFrame frame;
        private final boolean withholdsDisplays;
        private final List<Integer> displays = new ArrayList<>();
        private final Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockState, ProjectionContentView<BlockState, BlockState>>> fluid = new Long2ObjectOpenHashMap<>();

        private Surface(MinecraftPortal portal, ServerPlayer viewer) {
            owner = UUID.nameUUIDFromBytes(("wormholes:surface-skin:" + portal.getId()).getBytes(StandardCharsets.UTF_8));
            skin = portal.getSurfaceSkin();
            revision = portal.getGeometry().getRevision();
            frame = portal.getFrame();
            withholdsDisplays = MinecraftClientProfiles.profile(viewer).withholdsDisplays();
        }

        private boolean matches(MinecraftPortal portal, ServerPlayer viewer) {
            return revision == portal.getGeometry().getRevision() && frame == portal.getFrame() && skin.equals(portal.getSurfaceSkin())
                && withholdsDisplays == MinecraftClientProfiles.profile(viewer).withholdsDisplays();
        }
    }
}
