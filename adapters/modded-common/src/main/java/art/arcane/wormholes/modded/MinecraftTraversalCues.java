package art.arcane.wormholes.modded;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.render.client.session.ClientViewEmitters;
import art.arcane.wormholes.transit.TransitionProfile;
import art.arcane.optics.math.Face;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.phys.Vec3;
import art.arcane.wormholes.transit.TraversalCues;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import art.arcane.wormholes.network.client.FxMessage;

public final class MinecraftTraversalCues {
    private MinecraftTraversalCues() { }

    public static void threshold(WormholesModRuntime runtime, MinecraftPortal source, Vec3d point, Entity traveler) {
        if (!runtime.configuration().settings().getTransit().cinematicsEnabled) {
            return;
        }
        ServerLevel level = runtime.portals().resolveLevel(source);
        if (level == null) {
            return;
        }
        ServerPlayer excluded = traveler instanceof ServerPlayer player
            && runtime.clientViews().seamlessTravel(player.getUUID(), source.getId()) ? player : null;
        if (runtime.configuration().settings().getMain().enableParticles) {
            Identifier key = Identifier.tryParse(TraversalCues.particleKey(profile(source).thresholdEffect()));
            ParticleType<?> selected = key == null ? null : BuiltInRegistries.PARTICLE_TYPE.getOptional(key).orElse(null);
            SimpleParticleType particle = selected instanceof SimpleParticleType simple ? simple : ParticleTypes.REVERSE_PORTAL;
            if (excluded == null) {
                runtime.clientViews().burst(level, particle, point.x(), point.y(), point.z(), TraversalCues.THRESHOLD_PARTICLES,
                    TraversalCues.THRESHOLD_SPREAD, TraversalCues.THRESHOLD_SPREAD, TraversalCues.THRESHOLD_SPREAD,
                    TraversalCues.THRESHOLD_SPEED);
            } else {
                FxMessage.FxEmitter emitter = ClientViewEmitters.burst(BuiltInRegistries.PARTICLE_TYPE.getKey(particle).toString(),
                    point.x(), point.y(), point.z(), TraversalCues.THRESHOLD_PARTICLES, TraversalCues.THRESHOLD_SPREAD,
                    TraversalCues.THRESHOLD_SPREAD, TraversalCues.THRESHOLD_SPEED);
                for (ServerPlayer receiver : level.players()) {
                    if (receiver == excluded) {
                        continue;
                    }
                    if (runtime.clientViews().receiver(receiver)) {
                        if (receiver.distanceToSqr(point.x(), point.y(), point.z()) < 32.0D * 32.0D) {
                            runtime.clientViews().oneShot(receiver, emitter);
                        }
                    } else {
                        level.sendParticles(receiver, particle, false, false, point.x(), point.y(), point.z(),
                            TraversalCues.THRESHOLD_PARTICLES, TraversalCues.THRESHOLD_SPREAD, TraversalCues.THRESHOLD_SPREAD,
                            TraversalCues.THRESHOLD_SPREAD, TraversalCues.THRESHOLD_SPEED);
                    }
                }
            }
        }
        if (soundEnabled(runtime, source)) {
            level.playSound(excluded, point.x(), point.y(), point.z(), sound(TraversalCues.THRESHOLD_SOUND), SoundSource.BLOCKS,
                volume(runtime, source), 1.3F);
        }
    }

    public static void arrival(WormholesModRuntime runtime, MinecraftPortal destination, Entity traveler, boolean seamless) {
        if (!(traveler instanceof ServerPlayer player) || !runtime.configuration().settings().getTransit().cinematicsEnabled
            || !soundEnabled(runtime, destination) || seamless) {
            return;
        }
        String dimension = player.level().dimension().identifier().toString();
        SoundEvent sound = sound(TraversalCues.arrivalSound(dimension, profile(destination).arrivalSound()));
        player.connection.send(new ClientboundSoundPacket(Holder.direct(sound), SoundSource.PLAYERS,
            player.getX(), player.getY(), player.getZ(), volume(runtime, destination), TraversalCues.arrivalPitch(dimension), player.getRandom().nextLong()));
    }

    public static void reject(WormholesModRuntime runtime, MinecraftPortal portal, Entity traveler) {
        Face normal = portal.getFrame().getNormal();
        Vec3d origin = portal.getOrigin();
        double side = (traveler.xo - origin.x()) * normal.x() + (traveler.yo - origin.y()) * normal.y()
            + (traveler.zo - origin.z()) * normal.z();
        double magnitude = 3.0D * runtime.configuration().settings().getMain().portalPushbackMultiplier
            * runtime.rules().document(portal).profile().pushbackScale() * (side < 0.0D ? -1.0D : 1.0D);
        Vec3 velocity = new Vec3(normal.x() * magnitude, normal.y() * magnitude, normal.z() * magnitude);
        traveler.setDeltaMovement(velocity);
        if (traveler instanceof ServerPlayer player) {
            player.connection.send(new ClientboundSetEntityMotionPacket(player.getId(), velocity));
        }
        ServerLevel level = (ServerLevel) traveler.level();
        if (runtime.configuration().settings().getMain().enableParticles) {
            runtime.clientViews().burst(level, ParticleTypes.SMOKE, traveler.getX(), traveler.getY(), traveler.getZ(), 24,
                0.2D, 0.2D, 0.2D, 0.08D);
        }
        float volume = volume(runtime, portal);
        if (volume > 0.0F && soundEnabled(runtime, portal)) {
            level.playSound(null, traveler.getX(), traveler.getY(), traveler.getZ(), sound("minecraft:block.anvil.land"),
                SoundSource.BLOCKS, volume * 0.35F, 1.8F);
            level.playSound(null, traveler.getX(), traveler.getY(), traveler.getZ(), sound("minecraft:block.glass.break"),
                SoundSource.BLOCKS, volume * 0.3F, 0.7F);
        }
    }

    private static boolean soundEnabled(WormholesModRuntime runtime, MinecraftPortal portal) {
        return portal.getType() != PortalType.RTP || runtime.rtp().settings(portal).isSoundEnabled();
    }

    private static float volume(WormholesModRuntime runtime, MinecraftPortal portal) {
        return (float) (0.6D * runtime.configuration().settings().getMain().portalSoundVolumeMultiplier
            * runtime.rules().document(portal).profile().soundVolume());
    }

    private static TransitionProfile profile(MinecraftPortal portal) {
        Object encoded = portal.setting("transit.profile");
        return TransitionProfile.decode(encoded instanceof String text ? text : "");
    }

    private static SoundEvent sound(String key) {
        Identifier id = Identifier.tryParse(key);
        if (id == null) {
            id = Identifier.parse(TraversalCues.OVERWORLD_SOUND);
        }
        return SoundEvent.createVariableRangeEvent(id);
    }
}
