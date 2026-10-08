package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

public class ResidentLevelsMemoryBudgetTest extends MinecraftTestBase {
    private static final int SECTION_BYTES = 4_000;

    @Test
    public void estimateCountsSerializedSectionsAndTheirLightForEveryLoadedColumn() {
        ClientLevel level = ResidentTestFixtures.level(ResidentTestFixtures.NETHER);
        columns(level, 3, 2);
        assertEquals(3L * 2L * (SECTION_BYTES + ResidentLevel.LIGHT_SECTION_BYTES), ResidentLevel.estimate(level));
    }

    @Test
    public void closedLevelsAreReleasedOldestFirstOnceTheBudgetIsExceeded() {
        ClientLevel current = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        long column = 24L * (SECTION_BYTES + ResidentLevel.LIGHT_SECTION_BYTES);
        try (ResidentLevelsOpenCloseTest.Scope scope = new ResidentLevelsOpenCloseTest.Scope(current)) {
            ResidentLevels residents = new ResidentLevels(scope.sent::add, column * 25L);
            ClientLevel first = residents.open(ResidentTestFixtures.open(1, ResidentTestFixtures.NETHER, 0, 0));
            columns(first, 10, 24);
            ClientLevel second = residents.open(ResidentTestFixtures.open(2, ResidentTestFixtures.NETHER, 500, 500));
            columns(second, 10, 24);
            residents.close(new TravelMessage.RemoteLevelClose(1));
            residents.close(new TravelMessage.RemoteLevelClose(2));
            assertTrue(residents.resident(first));
            assertTrue(residents.resident(second));
            ClientLevel third = residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.NETHER, -900, -900));
            columns(third, 10, 24);
            residents.close(new TravelMessage.RemoteLevelClose(3));
            assertFalse(residents.resident(first));
            assertTrue(residents.resident(second));
            assertTrue(residents.resident(third));
            scope.worlds.verify(() -> ClientWorldLoader.forget(first));
            scope.worlds.verify(() -> ClientWorldLoader.forget(second), never());
            assertEquals(20L * column, residents.bytes());
        }
    }

    @Test
    public void openLevelsAndTheActiveLevelAreNeverEvicted() {
        ClientLevel current = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        try (ResidentLevelsOpenCloseTest.Scope scope = new ResidentLevelsOpenCloseTest.Scope(current)) {
            ResidentLevels residents = new ResidentLevels(scope.sent::add, 1L);
            ClientLevel open = residents.open(ResidentTestFixtures.open(1, ResidentTestFixtures.NETHER, 0, 0));
            columns(open, 4, 24);
            ClientLevel other = residents.open(ResidentTestFixtures.open(2, ResidentTestFixtures.NETHER, 900, 900));
            columns(other, 4, 24);
            assertTrue(residents.has(1));
            assertTrue(residents.has(2));
            residents.crossing(current);
            scope.minecraft.level = open;
            residents.crossing(null);
            residents.retire(current);
            columns(current, 4, 24);
            residents.close(new TravelMessage.RemoteLevelClose(1));
            residents.close(new TravelMessage.RemoteLevelClose(2));
            assertFalse(residents.resident(open));
            assertFalse(residents.resident(other));
            assertFalse(residents.resident(current));
            scope.worlds.verify(() -> ClientWorldLoader.forget(open), never());
        }
    }

    private static void columns(ClientLevel level, int count, int sections) {
        AtomicReferenceArray<LevelChunk> storage = new AtomicReferenceArray<>(count + 2);
        for (int index = 0; index < count; index++) {
            LevelChunk chunk = mock(LevelChunk.class);
            LevelChunkSection[] array = new LevelChunkSection[sections];
            for (int section = 0; section < sections; section++) {
                array[section] = mock(LevelChunkSection.class);
                when(array[section].getSerializedSize()).thenReturn(SECTION_BYTES);
            }
            when(chunk.getSections()).thenReturn(array);
            storage.set(index, chunk);
        }
        when(((PreparedChunkColumns) level.getChunkSource()).wormholes$columns()).thenReturn(storage);
    }
}
