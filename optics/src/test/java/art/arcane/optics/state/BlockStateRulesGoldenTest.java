package art.arcane.optics.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.AxisPermutation;

final class BlockStateRulesGoldenTest {
    private static final String GOLDENS = "/block-state-goldens.txt";

    @Test
    void everyRecordedStateRewritesToItsGolden() throws IOException {
        List<String[]> vectors = vectors();
        BitSet permutations = new BitSet(48);
        for (String[] vector : vectors) {
            AxisPermutation permutation = AxisPermutation.ofIndex(Integer.parseInt(vector[1]));
            permutations.set(permutation.index());
            StateProperties rewritten = BlockStateRules.apply(properties(vector[0]), permutation);
            assertEquals(properties(vector[2]), rewritten, vector[0] + " via " + permutation);
        }
        assertTrue(vectors.size() >= 300, "golden vectors " + vectors.size());
        assertEquals(48, permutations.cardinality());
    }

    @Test
    void goldensOnlyChangeTheBlockNotItsPropertyNames() throws IOException {
        for (String[] vector : vectors()) {
            assertEquals(key(vector[0]), key(vector[2]));
            assertEquals(key(vector[0]), key(vector[3]));
            StateProperties source = properties(vector[0]);
            StateProperties applied = properties(vector[3]);
            assertEquals(source.size(), applied.size(), vector[0]);
            for (int index = 0; index < source.size(); index++) {
                assertEquals(source.name(index), applied.name(index), vector[0]);
            }
        }
    }

    private static List<String[]> vectors() throws IOException {
        InputStream stream = BlockStateRulesGoldenTest.class.getResourceAsStream(GOLDENS);
        assertNotNull(stream, GOLDENS);
        List<String[]> vectors = new ArrayList<String[]>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    String[] vector = line.split("\t");
                    assertEquals(4, vector.length, line);
                    vectors.add(vector);
                }
            }
        }
        return vectors;
    }

    private static String key(String state) {
        int open = state.indexOf('[');
        return open < 0 ? state : state.substring(0, open);
    }

    private static StateProperties properties(String state) {
        int open = state.indexOf('[');
        return open < 0 ? StateProperties.EMPTY : StateProperties.parse(state.substring(open));
    }
}
