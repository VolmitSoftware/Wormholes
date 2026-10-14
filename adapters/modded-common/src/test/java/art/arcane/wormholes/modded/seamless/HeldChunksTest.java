package art.arcane.wormholes.modded.seamless;

import net.minecraft.world.level.ChunkPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HeldChunksTest {
    private static final long CHUNK = ChunkPos.pack(6, 1);
    private static final long OTHER = ChunkPos.pack(-3, 4);

    @Test
    public void unchangedHeldChunkSkipsItsReadySendOnce() {
        HeldChunks held = new HeldChunks();
        held.hold(CHUNK, 100L);

        assertEquals(HeldChunks.Ready.SKIP, held.ready(CHUNK, false, 0L));
        assertEquals(HeldChunks.Ready.SEND, held.ready(CHUNK, false, 0L));
    }

    @Test
    public void changeDroppedAfterTheHoldRefreshesTheHeldColumnAndReleasesTheHold() {
        HeldChunks held = new HeldChunks();
        held.hold(CHUNK, 100L);

        assertEquals(HeldChunks.Ready.REFRESH, held.ready(CHUNK, true, 150L));
        assertEquals(HeldChunks.Ready.SEND, held.ready(CHUNK, false, 0L));
    }

    @Test
    public void changeDroppedInTheSameInstantAsTheHoldRefreshes() {
        HeldChunks held = new HeldChunks();
        held.hold(CHUNK, 100L);

        assertEquals(HeldChunks.Ready.REFRESH, held.ready(CHUNK, true, 100L));
    }

    @Test
    public void changeDroppedBeforeTheHoldStillSkips() {
        HeldChunks held = new HeldChunks();
        held.hold(CHUNK, 100L);

        assertEquals(HeldChunks.Ready.SKIP, held.ready(CHUNK, true, 40L));
    }

    @Test
    public void staleHeldColumnRefreshesEvenWithoutALaterChange() {
        HeldChunks held = new HeldChunks();
        held.holdStale(CHUNK, 100L);

        assertEquals(HeldChunks.Ready.REFRESH, held.ready(CHUNK, false, 0L));
        assertEquals(HeldChunks.Ready.SEND, held.ready(CHUNK, false, 0L));
    }

    @Test
    public void currentHoldReplacesAnEarlierStaleHold() {
        HeldChunks held = new HeldChunks();
        held.holdStale(CHUNK, 100L);
        held.hold(CHUNK, 200L);

        assertEquals(HeldChunks.Ready.SKIP, held.ready(CHUNK, true, 150L));
    }

    @Test
    public void orderingSurvivesNanoTimeWrap() {
        HeldChunks held = new HeldChunks();
        held.hold(CHUNK, Long.MAX_VALUE - 10L);

        assertEquals(HeldChunks.Ready.REFRESH, held.ready(CHUNK, true, Long.MIN_VALUE + 10L));
    }

    @Test
    public void chunkThatWasNeverHeldIsSentThroughTheVanillaQueue() {
        HeldChunks held = new HeldChunks();
        held.hold(OTHER, 100L);

        assertEquals(HeldChunks.Ready.SEND, held.ready(CHUNK, false, 0L));
        assertEquals(HeldChunks.Ready.SKIP, held.ready(OTHER, false, 0L));
    }

    @Test
    public void releasedAndClearedChunksAreSent() {
        HeldChunks held = new HeldChunks();
        held.holdStale(CHUNK, 100L);
        held.hold(OTHER, 100L);

        held.release(CHUNK);
        assertEquals(HeldChunks.Ready.SEND, held.ready(CHUNK, false, 0L));
        held.clear();
        assertEquals(HeldChunks.Ready.SEND, held.ready(OTHER, false, 0L));
    }

    @Test
    public void newHoldReplacesTheEarlierHoldTime() {
        HeldChunks held = new HeldChunks();
        held.hold(CHUNK, 100L);
        held.hold(CHUNK, 200L);

        assertEquals(HeldChunks.Ready.REFRESH, held.ready(CHUNK, true, 200L));
    }
}
