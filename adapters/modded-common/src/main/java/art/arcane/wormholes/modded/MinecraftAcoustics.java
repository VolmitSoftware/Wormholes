package art.arcane.wormholes.modded;

import art.arcane.wormholes.render.acoustics.AcousticsBridge;
import art.arcane.wormholes.render.acoustics.AcousticsProfile;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

public final class MinecraftAcoustics {
    private MinecraftAcoustics() {
    }

    public static void play(ServerPlayer observer, AcousticsBridge.Playback sound) {
        MinecraftEntityPackets.send(observer, packet(sound, observer.level().getRandom().nextLong()));
    }

    public static ClientboundSoundPacket packet(AcousticsBridge.Playback sound, long seed) {
        SoundSource category = switch (sound.soundClass()) {
            case AMBIENT -> SoundSource.AMBIENT;
            case WORLD -> SoundSource.BLOCKS;
            case ENTITY -> SoundSource.NEUTRAL;
        };
        return new ClientboundSoundPacket(Holder.direct(SoundEvent.createVariableRangeEvent(Identifier.parse(sound.soundKey()))),
            category, sound.x(), sound.y(), sound.z(), sound.volume(), sound.pitch(), seed);
    }

    public static AcousticsBridge.Playback capture(ServerLevel level, Packet<?> packet) {
        if (packet instanceof ClientboundSoundPacket sound) {
            AcousticsProfile.SoundClass category = category(sound.getSource());
            return category == null ? null : new AcousticsBridge.Playback(sound.getSound().value().location().toString(), category,
                sound.getX(), sound.getY(), sound.getZ(), sound.getVolume(), sound.getPitch());
        }
        if (packet instanceof ClientboundSoundEntityPacket sound) {
            Entity entity = level.getEntity(sound.getId());
            AcousticsProfile.SoundClass category = category(sound.getSource());
            return entity == null || category == null ? null : new AcousticsBridge.Playback(sound.getSound().value().location().toString(), category,
                entity.getX(), entity.getY(), entity.getZ(), sound.getVolume(), sound.getPitch());
        }
        return null;
    }

    public static AcousticsBridge.Environment environment(ServerLevel level) {
        if (level.dimension() == Level.NETHER) {
            return AcousticsBridge.Environment.NETHER;
        }
        if (level.dimension() == Level.END) {
            return AcousticsBridge.Environment.THE_END;
        }
        return AcousticsBridge.Environment.NORMAL;
    }

    private static AcousticsProfile.SoundClass category(SoundSource source) {
        return switch (source) {
            case BLOCKS -> AcousticsProfile.SoundClass.WORLD;
            case PLAYERS, NEUTRAL, HOSTILE -> AcousticsProfile.SoundClass.ENTITY;
            case AMBIENT, WEATHER -> AcousticsProfile.SoundClass.AMBIENT;
            default -> null;
        };
    }
}
