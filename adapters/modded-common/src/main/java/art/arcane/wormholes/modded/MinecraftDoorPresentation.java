package art.arcane.wormholes.modded;

import art.arcane.wormholes.door.DimensionalDoorSounds;
import art.arcane.wormholes.door.DoorAccessFeedbackPolicy;
import art.arcane.wormholes.door.DoorHinge;
import art.arcane.wormholes.door.DoorPortalAnimation;
import art.arcane.wormholes.door.DoorPortalGeometry;
import art.arcane.wormholes.door.DoorVisualAnimationBudget;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.door.PortalPlaneGeometry;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.modded.mixin.DoorDisplayDataAccess;
import art.arcane.wormholes.util.Direction;
import com.mojang.math.Transformation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static net.minecraft.core.Direction.Axis.X;
import static net.minecraft.core.Direction.Axis.Z;

final class MinecraftDoorPresentation implements AutoCloseable {
    private static final DustColorTransitionOptions SURFACE_DUST = new DustColorTransitionOptions(0xB969FF, 0x140523, 0.7F);
    private static final DustParticleOptions DENY_DUST = new DustParticleOptions(0xFF4646, 1.0F);
    private final WormholesModRuntime runtime;
    private final MinecraftDoorService doors;
    private final WormholesModConfiguration configuration;
    private final Map<Key, Visual> visuals = new HashMap<>();
    private final Map<UUID, Long> denied = new HashMap<>();
    private final DoorVisualAnimationBudget<Visual> animations = new DoorVisualAnimationBudget<>(
        new DoorVisualAnimationBudget.Policy(64, 64, DoorPortalAnimation.ATTENDANCE_PERIOD_TICKS / DoorPortalAnimation.FRAME_PERIOD_TICKS,
            DoorPortalAnimation.FRAME_PERIOD_TICKS));
    private long tick;

    MinecraftDoorPresentation(WormholesModRuntime runtime, MinecraftDoorService doors) {
        this.runtime = runtime;
        this.doors = doors;
        configuration = runtime.configuration();
    }

    void tick() {
        if (++tick % DoorPortalAnimation.FRAME_PERIOD_TICKS != 0) {
            return;
        }
        for (MinecraftDoorService.DoorView door : doors.projectableViews()) {
            if (!door.active() || !available(door.endpoint())) {
                continue;
            }
            DoorwayPlane plane = door.plane();
            BlockState block = door.level().getBlockState(new BlockPos(plane.blockX(), plane.blockY(), plane.blockZ()));
            DoorHinge hinge = block.getBlock() instanceof DoorBlock && block.getValue(DoorBlock.HINGE) == DoorHingeSide.RIGHT ? DoorHinge.RIGHT : DoorHinge.LEFT;
            PortalPlaneGeometry geometry = DoorPortalGeometry.planeGeometry(plane, hinge);
            for (ServerPlayer player : door.level().players()) {
                if (player.hasDisconnected() || player.distanceToSqr(plane.blockX() + 0.5, plane.blockY(), plane.blockZ() + 0.5)
                    > DoorPortalAnimation.ATTENDANCE_RANGE_SQUARED) {
                    continue;
                }
                Key key = new Key(player.getUUID(), door.endpoint().identity().itemId());
                boolean hideBacking = configuration.settings().getDoors().projectionHideBacking
                    && runtime.projections().isDoorProjected(key.observer(), key.door());
                Visual visual = visuals.get(key);
                if (visual == null || !visual.matches(door, geometry, hideBacking)) {
                    if (visual != null) {
                        retire(visual);
                    }
                    visual = new Visual(player, door, geometry, hideBacking);
                    visuals.put(key, visual);
                    animations.register(visual);
                }
                visual.seen = tick;
            }
        }
        Iterator<Visual> iterator = visuals.values().iterator();
        while (iterator.hasNext()) {
            Visual visual = iterator.next();
            if (visual.seen != tick) {
                iterator.remove();
                retire(visual);
            }
        }
        for (DoorVisualAnimationBudget.AttendanceCheck<Visual> check : animations.advanceAttendanceChecks()) {
            animations.reportAttendance(check, check.key().seen == tick);
        }
        for (DoorVisualAnimationBudget.Admission<Visual> admission : animations.acquire()) {
            try {
                admission.key().frame(admission.animationTick());
            } finally {
                animations.complete(admission);
            }
        }
    }

    void deny(ServerPlayer player, PlacedDoorEndpoint endpoint, DoorwayPlane plane) {
        long now = System.currentTimeMillis();
        if (DoorAccessFeedbackPolicy.isCoolingDown(denied.get(player.getUUID()), now)) {
            return;
        }
        denied.put(player.getUUID(), DoorAccessFeedbackPolicy.nextAllowedMillis(now));
        if (denied.size() > 256) {
            denied.values().removeIf(expiry -> expiry <= now);
        }
        player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.DOOR_ACCESS_DENIED, Map.of()), true);
        double x = endpoint.position().x() + 0.5;
        double y = endpoint.position().y() + 1.0;
        double z = endpoint.position().z() + 0.5;
        sound(player.level(), new Vec3(x, y, z), DimensionalDoorSounds.denyBassSound(), 1.0F, 0.5F);
        sound(player.level(), new Vec3(x, y, z), DimensionalDoorSounds.denyThudSound(), 0.8F, 0.55F);
        if (!configuration.settings().getMain().enableParticles) {
            return;
        }
        if (plane != null) {
            PortalPlaneGeometry geometry = DoorPortalGeometry.planeGeometry(plane, DoorHinge.LEFT);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int index = 0; index < 24; index++) {
                double[] point = DoorPortalAnimation.scatterPoint(geometry, DoorPortalGeometry.panelFace(plane), random.nextDouble(), random.nextDouble());
                player.level().sendParticles(DENY_DUST, x + point[0], endpoint.position().y() + point[1], z + point[2], 1, 0, 0, 0, 0);
            }
        }
        player.level().sendParticles(ParticleTypes.SMOKE, x, y, z, 12, 0.25, 0.25, 0.25, 0.05);
    }

    void swing(ServerLevel level, BlockPos position, BlockState state, boolean open) {
        String material = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        sound(level, new Vec3(position.getX() + 0.5, position.getY() + 1.0, position.getZ() + 0.5),
            open ? DimensionalDoorSounds.openSound(material) : DimensionalDoorSounds.closeSound(material), 1.0F, 1.0F);
    }

    void teleport(Entity traveler, ServerLevel level) {
        float volume = (float) configuration.settings().getMain().portalSoundVolumeMultiplier;
        if (volume <= 0) {
            return;
        }
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getOptional(Identifier.withDefaultNamespace(DimensionalDoorSounds.teleportSound())).orElseThrow();
        if (traveler instanceof ServerPlayer player) {
            player.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), SoundSource.PLAYERS,
                player.getX(), player.getY(), player.getZ(), volume, 1.0F, level.getRandom().nextLong()));
        } else {
            level.playSound(null, traveler.getX(), traveler.getY(), traveler.getZ(), sound, SoundSource.NEUTRAL, volume, 1.0F);
        }
    }

    void disconnected(ServerPlayer player) {
        denied.remove(player.getUUID());
        Iterator<Map.Entry<Key, Visual>> iterator = visuals.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, Visual> entry = iterator.next();
            if (entry.getKey().observer().equals(player.getUUID())) {
                retire(entry.getValue());
                iterator.remove();
            }
        }
    }

    void clear() {
        for (Visual visual : visuals.values()) {
            retire(visual);
        }
        visuals.clear();
        denied.clear();
    }

    @Override
    public void close() {
        clear();
        animations.close();
    }

    private boolean available(PlacedDoorEndpoint endpoint) {
        return switch (endpoint.identity().kind()) {
            case PAIR -> doors.state().findMate(endpoint.identity()).map(mate -> doors.level(mate.position().worldKey()) != null).orElse(false);
            case PERSONAL, PUBLIC -> doors.level("wormholes:pockets") != null;
            case RETURN -> true;
        };
    }

    private void sound(ServerLevel level, Vec3 point, String name, float volume, float pitch) {
        float scaled = (float) (volume * configuration.settings().getMain().portalSoundVolumeMultiplier);
        if (scaled <= 0) {
            return;
        }
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getOptional(Identifier.withDefaultNamespace(name)).orElseThrow();
        level.playSound(null, point.x, point.y, point.z, sound, SoundSource.BLOCKS, scaled, pitch);
    }

    private void retire(Visual visual) {
        animations.retire(visual);
        if (!visual.player.hasDisconnected()) {
            visual.player.connection.send(visual.backing == null
                ? new ClientboundRemoveEntitiesPacket(visual.overlay.getId())
                : new ClientboundRemoveEntitiesPacket(visual.overlay.getId(), visual.backing.getId()));
        }
    }

    private final class Visual {
        private final ServerPlayer player;
        private final MinecraftDoorService.DoorView door;
        private final PortalPlaneGeometry geometry;
        private final Direction facing;
        private final Display.BlockDisplay backing;
        private final Display.BlockDisplay overlay;
        private final PortalPlaneGeometry overlayGeometry;
        private long seen;

        private Visual(ServerPlayer player, MinecraftDoorService.DoorView door, PortalPlaneGeometry geometry, boolean hideBacking) {
            this.player = player;
            this.door = door;
            this.geometry = geometry;
            facing = DoorPortalGeometry.panelFace(door.plane());
            backing = hideBacking ? null : display(Blocks.CRYING_OBSIDIAN.defaultBlockState(), geometry);
            overlayGeometry = DoorPortalGeometry.overlayGeometry(geometry, facing);
            BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS,
                facing == Direction.E || facing == Direction.W ? Z : X);
            try {
                overlay = display(portal, overlayGeometry);
            } catch (RuntimeException failure) {
                if (backing != null && !player.hasDisconnected()) {
                    player.connection.send(new ClientboundRemoveEntitiesPacket(backing.getId()));
                }
                throw failure;
            }
        }

        private boolean matches(MinecraftDoorService.DoorView current, PortalPlaneGeometry geometry, boolean hideBacking) {
            return door.level() == current.level() && door.plane().equals(current.plane()) && this.geometry.equals(geometry)
                && (backing == null) == hideBacking;
        }

        private Display.BlockDisplay display(BlockState block, PortalPlaneGeometry geometry) {
            CompoundTag data = new CompoundTag();
            data.store("transformation", Transformation.EXTENDED_CODEC, transformation(geometry));
            data.store("block_state", BlockState.CODEC, block);
            CompoundTag brightness = new CompoundTag();
            brightness.putInt("block", 15);
            brightness.putInt("sky", 15);
            data.put("brightness", brightness);
            data.putFloat("view_range", 32.0F);
            data.putFloat("width", 0.9375F);
            data.putFloat("height", 1.875F);
            data.putInt("interpolation_duration", DoorPortalAnimation.FRAME_PERIOD_TICKS);
            Display.BlockDisplay display = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, door.level());
            display.load(TagValueInput.create(ProblemReporter.DISCARDING, door.level().registryAccess(), data));
            DoorwayPlane plane = door.plane();
            display.setPos(plane.blockX() + 0.5, plane.blockY(), plane.blockZ() + 0.5);
            player.connection.send(new ClientboundAddEntityPacket(display.getId(), display.getUUID(), display.getX(), display.getY(), display.getZ(),
                0, 0, EntityTypes.BLOCK_DISPLAY, 0, Vec3.ZERO, 0));
            List<SynchedEntityData.DataValue<?>> values = display.getEntityData().getNonDefaultValues();
            if (values != null && !values.isEmpty()) {
                player.connection.send(new ClientboundSetEntityDataPacket(display.getId(), values));
            }
            display.getEntityData().packDirty();
            return display;
        }

        private void frame(int tick) {
            PortalPlaneGeometry frame = DoorPortalAnimation.frame(overlayGeometry, facing, tick);
            SynchedEntityData data = overlay.getEntityData();
            data.set(DoorDisplayDataAccess.wormholesTranslation(), new Vector3f(frame.translationX(), frame.translationY(), frame.translationZ()));
            data.set(DoorDisplayDataAccess.wormholesScale(), new Vector3f(frame.scaleX(), frame.scaleY(), frame.scaleZ()));
            data.set(DoorDisplayDataAccess.wormholesInterpolationStart(), 0, true);
            List<SynchedEntityData.DataValue<?>> changed = data.packDirty();
            if (changed != null && !changed.isEmpty()) {
                player.connection.send(new ClientboundSetEntityDataPacket(overlay.getId(), changed));
            }
            if (!configuration.settings().getMain().enableParticles) {
                return;
            }
            for (int arm = 0; arm < DoorPortalAnimation.ORBIT_ARMS; arm++) {
                double[] point = DoorPortalAnimation.orbitPoint(overlayGeometry, facing, tick, arm);
                door.level().sendParticles(player, arm % 2 == 0 ? ParticleTypes.PORTAL : ParticleTypes.REVERSE_PORTAL, false, false,
                    overlay.getX() + point[0], overlay.getY() + point[1], overlay.getZ() + point[2], 1, 0.03, 0.03, 0.03, 0.015);
            }
            if (tick % 16 == 0) {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                double[] point = DoorPortalAnimation.scatterPoint(overlayGeometry, facing, random.nextDouble(), random.nextDouble());
                door.level().sendParticles(player, SURFACE_DUST, false, false,
                    overlay.getX() + point[0], overlay.getY() + point[1], overlay.getZ() + point[2], 1, 0, 0, 0, 0);
            }
        }
    }

    private static Transformation transformation(PortalPlaneGeometry geometry) {
        return new Transformation(new Vector3f(geometry.translationX(), geometry.translationY(), geometry.translationZ()), new Quaternionf(),
            new Vector3f(geometry.scaleX(), geometry.scaleY(), geometry.scaleZ()), new Quaternionf());
    }

    private record Key(UUID observer, UUID door) {
    }
}
