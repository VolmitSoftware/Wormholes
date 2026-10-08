package art.arcane.wormholes.modded.client.render.iris;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class IrisPipelineSchedule {
    private static final List<String> PREFERRED = List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");

    private IrisPipelineSchedule() {
    }

    public static List<String> order(String current, Collection<String> dimensions) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>(dimensions.size() + 1);
        ordered.add(current);
        for (String dimension : PREFERRED) {
            if (dimensions.contains(dimension)) {
                ordered.add(dimension);
            }
        }
        ordered.addAll(new TreeSet<>(dimensions));
        return List.copyOf(ordered);
    }

    public static List<String> pending(List<String> order, Set<String> created, Set<String> failed) {
        List<String> pending = new ArrayList<>(order.size());
        for (String dimension : order) {
            if (!created.contains(dimension) && !failed.contains(dimension)) {
                pending.add(dimension);
            }
        }
        return pending;
    }
}
