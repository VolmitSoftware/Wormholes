package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

import org.junit.jupiter.api.Test;

final class ProjectorEmptyCellRunsTest {
    @Test
    void skipsOnlyRecordedCellsAcrossGroupsInEitherDirection() {
        Random random = new Random(71923L);
        for (int axis = 0; axis < 3; axis++) {
            ProjectorEmptyCellRuns runs = new ProjectorEmptyCellRuns();
            int[] row = new int[] {-97, 65, -31};
            boolean[] empty = new boolean[513];
            runs.beginRow(axis, row);
            for (int index = 0; index < empty.length; index++) {
                empty[index] = index < 160 || random.nextBoolean();
                if (empty[index]) {
                    runs.markEmpty(index - 256);
                }
            }
            for (int step : new int[] {-1, 1}) {
                for (int start = -256; start <= 256; start++) {
                    int end = step > 0 ? 256 : -256;
                    int expected = start;
                    while (ProjectorCellScan.scanContinues(expected, end, step)
                        && empty[expected + 256]) {
                        expected += step;
                    }
                    int actual = runs.nextCandidate(start, end, step);
                    if (ProjectorCellScan.scanContinues(expected, end, step)) {
                        assertEquals(expected, actual, "axis=" + axis + " step=" + step + " start=" + start);
                    } else {
                        assertEquals(false, ProjectorCellScan.scanContinues(actual, end, step));
                    }
                }
            }
            int[] differentRow = row.clone();
            differentRow[(axis + 1) % 3]++;
            runs.beginRow(axis, differentRow);
            assertEquals(-128, runs.nextCandidate(-128, 256, 1));
            runs.beginRow(axis, row);
            runs.clear();
            assertEquals(-128, runs.nextCandidate(-128, 256, 1));
        }
    }
}
