package art.arcane.wormholes.modded.client.render.iris;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;

final class IrisProgramReserve {
    static final int HOME_SETS = 2;
    static final int DEMANDED_SETS = 1;
    private static final int RECIPE_CAPACITY = 96;

    private final Options options;
    private final Map<String, Recipe> recipes = new LinkedHashMap<>();
    private final Set<String> demanded = new LinkedHashSet<>();
    private final Map<Inputs, ArrayDeque<Integer>> spares = new HashMap<>();
    private final ArrayDeque<Inputs> deficits = new ArrayDeque<>();
    private final Set<Inputs> failed = new HashSet<>();
    private Construction construction;
    private String home;
    private boolean planned = true;
    private boolean constructed;
    private int reserved;
    private long linked;
    private long handed;
    private long misses;
    private long deleted;
    private long failures;
    private long linkNanos;

    IrisProgramReserve(Options options) {
        this.options = Objects.requireNonNull(options);
    }

    void home(String dimension) {
        if (!Objects.equals(home, dimension)) {
            home = dimension;
            planned = false;
        }
    }

    void begin(String dimension, boolean consuming) {
        Objects.requireNonNull(dimension);
        if (construction != null) {
            throw new IllegalStateException("An Iris program recording is already open for " + construction.dimension);
        }
        construction = new Construction(dimension, consuming);
    }

    int take(Inputs inputs) {
        Objects.requireNonNull(inputs);
        if (construction == null) {
            return 0;
        }
        construction.sequence.add(inputs);
        if (!construction.consuming) {
            return 0;
        }
        ArrayDeque<Integer> available = spares.get(inputs);
        if (available == null || available.isEmpty()) {
            misses++;
            return 0;
        }
        reserved--;
        handed++;
        planned = false;
        return available.pop();
    }

    void end(boolean completed) {
        Construction finished = construction;
        if (finished == null) {
            throw new IllegalStateException("No Iris program recording is open");
        }
        construction = null;
        constructed = true;
        if (finished.consuming && demanded.add(finished.dimension)) {
            planned = false;
        }
        if (!completed || finished.sequence.isEmpty()) {
            return;
        }
        Recipe previous = recipes.get(finished.dimension);
        if (previous != null && previous.sequence.equals(finished.sequence)) {
            return;
        }
        recipes.put(finished.dimension, new Recipe(finished.sequence));
        planned = false;
    }

    boolean admits(String dimension) {
        Recipe recipe = recipes.get(dimension);
        if (recipe == null || covered(recipe)) {
            return true;
        }
        if (demanded.add(dimension)) {
            planned = false;
        }
        return false;
    }

    boolean step() {
        if (construction != null) {
            return false;
        }
        if (constructed) {
            constructed = false;
            return false;
        }
        return linkNext();
    }

    boolean fill(long budgetNanos) {
        if (construction != null) {
            return false;
        }
        long started = options.clock().getAsLong();
        boolean pending = true;
        while (pending && options.clock().getAsLong() - started < budgetNanos) {
            pending = linkNext();
        }
        plan();
        return deficits.isEmpty();
    }

    void clear() {
        Throwable failure = null;
        for (ArrayDeque<Integer> programs : spares.values()) {
            for (int program : programs) {
                try {
                    options.delete().accept(program);
                    deleted++;
                } catch (RuntimeException | Error deletionFailure) {
                    if (failure == null) {
                        failure = deletionFailure;
                    } else {
                        failure.addSuppressed(deletionFailure);
                    }
                }
            }
        }
        spares.clear();
        deficits.clear();
        recipes.clear();
        demanded.clear();
        failed.clear();
        reserved = 0;
        planned = true;
        constructed = false;
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    Statistics statistics() {
        return new Statistics(linked, handed, misses, deleted, failures, linkNanos, reserved, deficits.size(), recipes.size());
    }

    private boolean covered(Recipe recipe) {
        for (int index = 0; index < recipe.keys.length; index++) {
            if (failed.contains(recipe.keys[index])) {
                continue;
            }
            ArrayDeque<Integer> available = spares.get(recipe.keys[index]);
            if (available == null || available.size() < recipe.counts[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean linkNext() {
        plan();
        Inputs next = deficits.poll();
        if (next == null) {
            return false;
        }
        long started = options.clock().getAsLong();
        int program;
        try {
            program = options.linker().applyAsInt(next);
        } catch (RuntimeException failure) {
            failed.add(next);
            failures++;
            planned = false;
            throw failure;
        } finally {
            linkNanos += options.clock().getAsLong() - started;
        }
        if (program < 1) {
            failed.add(next);
            failures++;
            planned = false;
            throw new IllegalStateException("A reserved Iris program must have a valid handle");
        }
        spares.computeIfAbsent(next, ignored -> new ArrayDeque<>(HOME_SETS)).push(program);
        reserved++;
        linked++;
        return true;
    }

    private void plan() {
        if (planned) {
            return;
        }
        planned = true;
        deficits.clear();
        List<String> order = priority();
        Map<Inputs, Integer> required = new HashMap<>();
        int levels = 0;
        for (String dimension : order) {
            int sets = sets(dimension);
            levels = Math.max(levels, sets);
            Recipe recipe = recipes.get(dimension);
            for (int index = 0; index < recipe.keys.length; index++) {
                required.merge(recipe.keys[index], sets * recipe.counts[index], Math::max);
            }
        }
        release(required);
        Map<Inputs, Integer> queued = new HashMap<>(required.size());
        for (int level = 1; level <= levels; level++) {
            for (String dimension : order) {
                if (sets(dimension) >= level) {
                    queue(recipes.get(dimension), level, required, queued);
                }
            }
        }
    }

    private List<String> priority() {
        List<String> order = new ArrayList<>(demanded.size() + 1);
        if (home != null && recipes.containsKey(home)) {
            order.add(home);
        }
        for (String dimension : demanded) {
            if (!dimension.equals(home) && recipes.containsKey(dimension)) {
                order.add(dimension);
            }
        }
        return order;
    }

    private int sets(String dimension) {
        if (dimension.equals(home)) {
            return HOME_SETS;
        }
        return demanded.contains(dimension) ? DEMANDED_SETS : 0;
    }

    private void queue(Recipe recipe, int level, Map<Inputs, Integer> required, Map<Inputs, Integer> queued) {
        for (int index = 0; index < recipe.keys.length; index++) {
            Inputs key = recipe.keys[index];
            if (failed.contains(key)) {
                continue;
            }
            int target = Math.min(required.get(key), level * recipe.counts[index]);
            ArrayDeque<Integer> available = spares.get(key);
            int have = queued.getOrDefault(key, available == null ? 0 : available.size());
            for (int count = have; count < target; count++) {
                deficits.add(key);
            }
            queued.put(key, Math.max(have, target));
        }
    }

    private void release(Map<Inputs, Integer> required) {
        Iterator<Map.Entry<Inputs, ArrayDeque<Integer>>> iterator = spares.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Inputs, ArrayDeque<Integer>> entry = iterator.next();
            if (required.containsKey(entry.getKey())) {
                continue;
            }
            ArrayDeque<Integer> programs = entry.getValue();
            iterator.remove();
            while (!programs.isEmpty()) {
                int program = programs.pop();
                reserved--;
                options.delete().accept(program);
                deleted++;
            }
        }
    }

    record Inputs(Kind kind, String name, Stages stages, Object format, boolean fallback, Set<Integer> units) {
        Inputs {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(name);
            Objects.requireNonNull(stages);
            Objects.requireNonNull(units);
        }

        static Inputs shader(String name, String vertex, String geometry, String tessControl, String tessEval, String fragment, Object format,
                             boolean fallback) {
            return new Inputs(Kind.SHADER, name, new Stages(vertex, geometry, tessControl, tessEval, fragment, null), format, fallback, Set.of());
        }

        static Inputs program(String name, String vertex, String geometry, String fragment, Set<Integer> units) {
            return new Inputs(Kind.PROGRAM, name, new Stages(vertex, geometry, null, null, fragment, null), null, false, units);
        }

        static Inputs compute(String name, String source, Set<Integer> units) {
            return new Inputs(Kind.COMPUTE, name, new Stages(null, null, null, null, null, source), null, false, units);
        }

        static Inputs sodium(String name, String vertex, String geometry, String tessControl, String tessEval, String fragment) {
            return new Inputs(Kind.SODIUM, name, new Stages(vertex, geometry, tessControl, tessEval, fragment, null), null, false, Set.of());
        }
    }

    record Stages(String vertex, String geometry, String tessControl, String tessEval, String fragment, String compute) {
    }

    record Statistics(long linked, long handed, long misses, long deleted, long failures, long linkNanos, int reserved, int deficits,
                      int recipes) {
    }

    record Options(ToIntFunction<Inputs> linker, IntConsumer delete, LongSupplier clock) {
        Options {
            Objects.requireNonNull(linker);
            Objects.requireNonNull(delete);
            Objects.requireNonNull(clock);
        }
    }

    enum Kind {
        SHADER,
        PROGRAM,
        COMPUTE,
        SODIUM
    }

    private static final class Recipe {
        private final List<Inputs> sequence;
        private final Inputs[] keys;
        private final int[] counts;

        private Recipe(List<Inputs> sequence) {
            this.sequence = List.copyOf(sequence);
            Map<Inputs, Integer> occurrences = new LinkedHashMap<>();
            for (Inputs inputs : this.sequence) {
                occurrences.merge(inputs, 1, Integer::sum);
            }
            keys = occurrences.keySet().toArray(new Inputs[0]);
            counts = new int[keys.length];
            for (int index = 0; index < keys.length; index++) {
                counts[index] = occurrences.get(keys[index]);
            }
        }
    }

    private static final class Construction {
        private final String dimension;
        private final boolean consuming;
        private final List<Inputs> sequence = new ArrayList<>(RECIPE_CAPACITY);

        private Construction(String dimension, boolean consuming) {
            this.dimension = dimension;
            this.consuming = consuming;
        }
    }
}
