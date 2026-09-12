package art.arcane.wormholes.render.acoustics;

import java.util.logging.Level;

import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.sound.Sound;
import com.github.retrooper.packetevents.protocol.sound.SoundCategory;
import com.github.retrooper.packetevents.protocol.sound.Sounds;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.service.WormholesTelemetry;

/** Plays relayed sounds through the sound-effect packet, positioned at the local aperture. */
public final class SoundPacketSink implements AcousticsBridge.SoundSink {
    private static final String FAILURE_REASON = "ACOUSTICS_SOUND_PACKET_FAILED";

    private volatile boolean failureLogged;

    @Override
    public void play(Player observer, String soundKey, AcousticsProfile.SoundClass soundClass,
                     double x, double y, double z, float volume, float pitch) {
        if (observer == null || soundKey == null) {
            return;
        }
        try {
            Sound sound = Sounds.getByNameOrCreate(soundKey);
            PacketEvents.getAPI().getPlayerManager().sendPacket(observer,
                new WrapperPlayServerSoundEffect(sound, category(soundClass), new Vector3d(x, y, z), volume, pitch));
            WormholesTelemetry.countPacket();
        } catch (RuntimeException failure) {
            WormholesTelemetry.countFailure(FAILURE_REASON);
            if (failureLogged) {
                return;
            }
            failureLogged = true;
            Wormholes plugin = Wormholes.instance;
            if (plugin != null) {
                plugin.getLogger().log(Level.WARNING, "[acoustics] sound packet failed for " + soundKey, failure);
            }
        }
    }

    static SoundCategory category(AcousticsProfile.SoundClass soundClass) {
        return switch (soundClass) {
            case AMBIENT -> SoundCategory.AMBIENT;
            case WORLD -> SoundCategory.BLOCK;
            case ENTITY -> SoundCategory.NEUTRAL;
        };
    }
}
