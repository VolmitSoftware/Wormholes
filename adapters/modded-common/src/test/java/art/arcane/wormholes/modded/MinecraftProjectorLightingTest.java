package art.arcane.wormholes.modded;

import art.arcane.optics.light.ProjectorLighting;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import org.junit.Test;

import java.util.BitSet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class MinecraftProjectorLightingTest {
    @Test
    public void nativeLightPacketPreservesNegativeChunkMasksAndNibbleArrays() {
        BitSet skyMask = new BitSet();
        skyMask.set(1);
        skyMask.set(23);
        BitSet blockMask = new BitSet();
        blockMask.set(7);
        BitSet emptySky = new BitSet();
        emptySky.set(4);
        BitSet emptyBlock = new BitSet();
        emptyBlock.set(9);
        byte[] sky = new byte[2048];
        byte[] secondSky = new byte[2048];
        byte[] block = new byte[2048];
        sky[2047] = (byte) 0xAF;
        secondSky[0] = (byte) 0x31;
        block[35] = (byte) 0xD8;
        ProjectorLighting.ChunkLight light = new ProjectorLighting.ChunkLight(-17, 65, blockMask, skyMask,
            emptyBlock, emptySky, new byte[][] {sky, secondSky}, new byte[][] {block});

        ClientboundLightUpdatePacket packet = MinecraftProjectorLighting.packet(light);

        assertEquals(-17, packet.x());
        assertEquals(65, packet.z());
        assertEquals(skyMask, packet.lightData().skyYMask());
        assertEquals(blockMask, packet.lightData().blockYMask());
        assertEquals(emptySky, packet.lightData().emptySkyYMask());
        assertEquals(emptyBlock, packet.lightData().emptyBlockYMask());
        assertArrayEquals(sky, packet.lightData().skyUpdates().get(0));
        assertArrayEquals(secondSky, packet.lightData().skyUpdates().get(1));
        assertArrayEquals(block, packet.lightData().blockUpdates().get(0));
    }
}
