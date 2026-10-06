package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.VisualQualityProfile;
import art.arcane.wormholes.modded.clientview.MinecraftClientViewService;
import art.arcane.wormholes.modded.mixin.DoorDisplayDataAccess;
import art.arcane.wormholes.network.MinecraftGatewayPolicies;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.effects.PortalAnimation;
import art.arcane.wormholes.portal.rtp.RtpService;
import art.arcane.wormholes.render.client.session.ClientViewEmitters;
import art.arcane.optics.math.Box;
import com.mojang.math.Transformation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MinecraftPortalEffects implements AutoCloseable {
    private final WormholesModRuntime runtime;
    private final Map<UUID, Boolean> states = new HashMap<>();
    private final Map<UUID, Active> animations = new HashMap<>();

    public MinecraftPortalEffects(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    public void created(MinecraftPortal portal, Map<BlockPos, BlockState> originals) {
        List<PortalAnimation.Block> blocks = new ArrayList<>(originals.size());
        for (Map.Entry<BlockPos, BlockState> entry : originals.entrySet()) {
            if (!entry.getValue().isAir()) {
                BlockPos position = entry.getKey();
                blocks.add(new PortalAnimation.Block(new art.arcane.optics.math.Vec3(position.getX() + .5, position.getY() + .5, position.getZ() + .5), BlockStateParser.serialize(entry.getValue())));
            }
        }
        states.put(portal.getId(), ready(portal));
        begin(portal, blocks.isEmpty() ? PortalAnimation.Mode.OPEN : PortalAnimation.Mode.FORMATION, blocks);
    }

    public void removed(MinecraftPortal portal) {
        states.remove(portal.getId());
        begin(portal, PortalAnimation.Mode.CLOSE, List.of());
    }

    public void tick() {
        runtime.requireServerThread();
        Set<UUID> present = new HashSet<>();
        for (MinecraftPortal portal : runtime.portals().snapshot()) {
            UUID id = portal.getId();
            present.add(id);
            boolean open = ready(portal);
            Boolean previous = states.put(id, open);
            if ((previous == null && open || previous != null && previous != open)
                && !creating(id)) {
                begin(portal, open ? PortalAnimation.Mode.OPEN : PortalAnimation.Mode.CLOSE, List.of());
            }
        }
        states.keySet().retainAll(present);
        Iterator<Map.Entry<UUID, Active>> iterator = animations.entrySet().iterator();
        while (iterator.hasNext()) {
            Active active = iterator.next().getValue();
            if (!active.animation.tick()) {
                iterator.remove();
            }
        }
    }

    @Override
    public void close() {
        for (Active active : animations.values()) {
            active.animation.close();
        }
        animations.clear();
        states.clear();
    }

    boolean ready(MinecraftPortal portal) {
        if (portal.getType() == PortalType.RTP) {
            return runtime.rtp().snapshot(portal.getId()).map(snapshot -> snapshot.runtime().ready()).orElse(false);
        }
        if (portal.isMirrorMode() && portal.getProjectionMode() == ProjectionMode.ON
            || runtime.nexus().perTraveler(portal) || MinecraftGatewayPolicies.active(portal)
            || runtime.api().hasResolvers()) {
            return true;
        }
        if (portal.getDestinationId() == null) {
            return false;
        }
        if ("UNIVERSAL".equals(portal.getTunnelType())) {
            return runtime.network().remotePortals().get(portal.getDestinationServer(), portal.getDestinationId()) != null;
        }
        MinecraftPortal destination = runtime.portals().get(portal.getDestinationId());
        return destination != null && destination.getType() != PortalType.RTP;
    }

    private boolean creating(UUID id) {
        Active active = animations.get(id);
        return active != null && active.creation;
    }

    private void begin(MinecraftPortal portal, PortalAnimation.Mode mode, List<PortalAnimation.Block> blocks) {
        Active previous = animations.remove(portal.getId());
        if (previous != null) {
            previous.animation.close();
        }
        ServerLevel level = runtime.portals().resolveLevel(portal);
        if (level == null) {
            return;
        }
        Box bounds = portal.getGeometry().getArea();
        art.arcane.optics.math.Vec3 center = portal.getGeometry().getApertureCenter();
        art.arcane.optics.math.Vec3 size = new art.arcane.optics.math.Vec3(Math.abs(bounds.getXb() - bounds.getXa()), Math.abs(bounds.getYb() - bounds.getYa()), Math.abs(bounds.getZb() - bounds.getZa()));
        Active active = new Active(mode == PortalAnimation.Mode.FORMATION || !states.containsKey(portal.getId()));
        VisualQualityProfile quality = runtime.configuration().settings().getVisualQualityProfile();
        boolean particles = runtime.configuration().settings().getMain().enableParticles;
        PortalAnimation.Options options = new PortalAnimation.Options(mode, center, size, quality, particles,
            runtime.configuration().settings().getMain().portalSoundVolumeMultiplier, runtime::running,
            () -> portal.getType() != PortalType.RTP || runtime.rtp().settings(portal).isSoundEnabled(), blocks);
        MinecraftClientViewService clientViews = runtime.clientViews();
        active.animation = new PortalAnimation<>(options, new Host(level, center, clientViews));
        animations.put(portal.getId(), active);
        if (particles && mode != PortalAnimation.Mode.SOUNDS) {
            clientViews.oneShotNear(level, center.x(), center.y(), center.z(), ClientViewEmitters.animation(mode, center, size, quality));
        }
    }

    private static final class Active {
        private final boolean creation;
        private PortalAnimation<DisplayHandle> animation;

        private Active(boolean creation) {
            this.creation = creation;
        }
    }

    private record DisplayHandle(Display.BlockDisplay entity, List<ServerPlayer> viewers) { }

    private static final class Host implements PortalAnimation.Host<DisplayHandle> {
        private final ServerLevel level;
        private final art.arcane.optics.math.Vec3 anchor;
        private final MinecraftClientViewService clientViews;

        private Host(ServerLevel level, art.arcane.optics.math.Vec3 anchor, MinecraftClientViewService clientViews) {
            this.level = level;
            this.anchor = anchor;
            this.clientViews = clientViews;
        }

        @Override
        public void particle(PortalAnimation.ParticleEmission emission) {
            ParticleOptions particle = MinecraftAnimationParticles.options(emission.type());
            art.arcane.optics.math.Vec3 position = emission.position();
            art.arcane.optics.math.Vec3 spread = emission.spread();
            if (!clientViews.particles(level, particle, position.x(), position.y(), position.z(), emission.count(), spread.x(), spread.y(), spread.z(),
                emission.speed(), null)) {
                level.sendParticles(particle, position.x(), position.y(), position.z(), emission.count(), spread.x(), spread.y(), spread.z(),
                    emission.speed());
            }
        }

        @Override
        public void sound(PortalAnimation.SoundEmission sound) {
            level.playSound(null, sound.position().x(), sound.position().y(), sound.position().z(),
                SoundEvent.createVariableRangeEvent(Identifier.withDefaultNamespace(sound.id())), SoundSource.BLOCKS, sound.volume(), sound.pitch());
        }

        @Override
        public DisplayHandle spawn(PortalAnimation.DisplaySpec specification) {
            BlockState block;
            try {
                block = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, specification.blockState(), false).blockState();
            } catch (CommandSyntaxException failure) {
                throw new IllegalArgumentException("Invalid portal animation block " + specification.blockState(), failure);
            }
            CompoundTag data = new CompoundTag();
            data.store("block_state", BlockState.CODEC, block);
            data.store("transformation", Transformation.EXTENDED_CODEC, transformation(specification));
            data.putInt("interpolation_duration", 2);
            data.putFloat("view_range", 2.5f);
            CompoundTag brightness = new CompoundTag();
            brightness.putInt("block", 15);
            brightness.putInt("sky", 15);
            data.put("brightness", brightness);
            Display.BlockDisplay display = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
            display.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data));
            display.setPos(anchor.x(), anchor.y(), anchor.z());
            List<ServerPlayer> viewers = new ArrayList<>();
            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(anchor.x(), anchor.y(), anchor.z()) <= 6400 && !player.hasDisconnected()) {
                    viewers.add(player);
                    player.connection.send(new ClientboundAddEntityPacket(display.getId(), display.getUUID(), anchor.x(), anchor.y(), anchor.z(), 0, 0, EntityTypes.BLOCK_DISPLAY, 0, Vec3.ZERO, 0));
                }
            }
            DisplayHandle handle = new DisplayHandle(display, List.copyOf(viewers));
            metadata(handle, false);
            return handle;
        }

        @Override
        public void transform(DisplayHandle handle, PortalAnimation.DisplaySpec specification) {
            Transformation transformation = transformation(specification);
            SynchedEntityData data = handle.entity().getEntityData();
            data.set(DoorDisplayDataAccess.wormholesTranslation(), transformation.translation());
            data.set(DoorDisplayDataAccess.wormholesScale(), transformation.scale());
            data.set(DoorDisplayDataAccess.wormholesLeftRotation(), transformation.leftRotation());
            data.set(DoorDisplayDataAccess.wormholesInterpolationStart(), 0, true);
            metadata(handle, true);
        }

        private Transformation transformation(PortalAnimation.DisplaySpec specification) {
            Quaternionf rotation = new Quaternionf().rotationAxis(specification.rotation(), specification.normal() == 0 ? 1 : 0, specification.normal() == 1 ? 1 : 0, specification.normal() == 2 ? 1 : 0);
            Vector3f scale = new Vector3f((float) specification.scale().x(), (float) specification.scale().y(), (float) specification.scale().z());
            Vector3f half = new Vector3f(scale).mul(.5f).rotate(rotation);
            Vector3f position = new Vector3f((float) (specification.center().x() - anchor.x()), (float) (specification.center().y() - anchor.y()), (float) (specification.center().z() - anchor.z())).sub(half);
            return new Transformation(position, rotation, scale, new Quaternionf());
        }

        private void metadata(DisplayHandle handle, boolean changed) {
            List<SynchedEntityData.DataValue<?>> values = changed ? handle.entity().getEntityData().packDirty()
                : handle.entity().getEntityData().getNonDefaultValues();
            if (values != null && !values.isEmpty()) {
                for (ServerPlayer viewer : handle.viewers()) {
                    if (!viewer.hasDisconnected() && viewer.level() == level) {
                        viewer.connection.send(new ClientboundSetEntityDataPacket(handle.entity().getId(), values));
                    }
                }
            }
        }

        @Override
        public void remove(DisplayHandle handle) {
            for (ServerPlayer viewer : handle.viewers()) {
                if (!viewer.hasDisconnected()) {
                    viewer.connection.send(new ClientboundRemoveEntitiesPacket(handle.entity().getId()));
                }
            }
        }
    }
}
