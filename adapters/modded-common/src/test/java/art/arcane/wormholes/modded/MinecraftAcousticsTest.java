package art.arcane.wormholes.modded;

import art.arcane.optics.fidelity.AcousticsBridge;
import art.arcane.optics.fidelity.AcousticsProfile;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class MinecraftAcousticsTest {
    @Test
    public void relayedPacketKeepsAperturePositionPitchVolumeAndCategory() {
        AcousticsBridge.Playback sound = new AcousticsBridge.Playback("minecraft:block.stone.break", AcousticsProfile.SoundClass.WORLD,
            -3.5D, 64.25D, 7.125D, 0.35F, 0.8F);
        ClientboundSoundPacket packet = MinecraftAcoustics.packet(sound, 37L);
        assertEquals(sound.soundKey(), packet.getSound().value().location().toString());
        assertEquals(SoundSource.BLOCKS, packet.getSource());
        assertEquals(sound.x(), packet.getX(), 0.0D);
        assertEquals(sound.y(), packet.getY(), 0.0D);
        assertEquals(sound.z(), packet.getZ(), 0.0D);
        assertEquals(sound.volume(), packet.getVolume(), 0.0F);
        assertEquals(sound.pitch(), packet.getPitch(), 0.0F);
        assertEquals(37L, packet.getSeed());
        assertEquals(sound, MinecraftAcoustics.capture(null, packet));
    }

    @Test
    public void personalMusicDoesNotBecomeAThroughPortalWorldEvent() {
        ClientboundSoundPacket packet = new ClientboundSoundPacket(Holder.direct(SoundEvent.createVariableRangeEvent(
            Identifier.parse("minecraft:music.overworld.day"))), SoundSource.MUSIC, 0, 64, 0, 1, 1, 37L);
        assertNull(MinecraftAcoustics.capture(null, packet));
    }
}
