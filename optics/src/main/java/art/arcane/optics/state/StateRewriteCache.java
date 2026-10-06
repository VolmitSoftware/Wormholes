package art.arcane.optics.state;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

import art.arcane.optics.frame.AxisPermutation;

public final class StateRewriteCache<B> {
    static final int LIMIT = 8192;
    private static final int PERMUTATIONS = 48;
    private static final Object[] UNAFFECTED = new Object[0];
    private static final Object UNCHANGED = new Object();

    private final Function<B, StateProperties> reader;
    private final BiFunction<B, StateProperties, B> writer;
    private final ConcurrentHashMap<B, Object[]> rewrites;

    public StateRewriteCache(Function<B, StateProperties> reader, BiFunction<B, StateProperties, B> writer) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.rewrites = new ConcurrentHashMap<B, Object[]>();
    }

    public boolean rewrites(B block) {
        return row(block) != UNAFFECTED;
    }

    @SuppressWarnings("unchecked")
    public B apply(B block, AxisPermutation permutation) {
        Object[] row = permutation == AxisPermutation.IDENTITY ? UNAFFECTED : row(block);
        if (row == UNAFFECTED) {
            return block;
        }
        int index = permutation.index();
        Object cached = row[index];
        if (cached == null) {
            StateProperties properties = reader.apply(block);
            StateProperties rewritten = BlockStateRules.apply(properties, permutation);
            B result = rewritten.equals(properties) ? block : writer.apply(block, rewritten);
            cached = result == block ? UNCHANGED : result;
            row[index] = cached;
        }
        return cached == UNCHANGED ? block : (B) cached;
    }

    private Object[] row(B block) {
        Object[] row = rewrites.get(block);
        if (row != null) {
            return row;
        }
        Object[] created = BlockStateRules.affects(reader.apply(block)) ? new Object[PERMUTATIONS] : UNAFFECTED;
        if (rewrites.size() >= LIMIT) {
            rewrites.clear();
        }
        Object[] existing = rewrites.putIfAbsent(block, created);
        return existing == null ? created : existing;
    }
}
