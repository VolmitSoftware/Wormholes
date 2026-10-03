package art.arcane.wormholes.modded.client.render;

import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class PortalIrisResolution {
    private static final int STEP = 64;
    private static final int GROWTH_FRAMES = 120;

    private final long limit;
    private Demand demand;
    private List<PortalShaderRenderer.Resolution> selected;
    private List<PortalShaderRenderer.Resolution> desired;
    private int stableFrames;

    PortalIrisResolution(long limit) {
        this.limit = limit;
    }

    List<PortalShaderRenderer.Resolution> select(Demand updated) {
        if (updated.width() < 1 || updated.height() < 1) {
            throw new IllegalArgumentException("Shader view dimensions must be positive");
        }
        if (updated.programs().isEmpty()) {
            stableFrames = 0;
            return selected == null ? sizes(updated, Collections.nCopies(PortalRenderTargets.DEPTHS, updated.width())) : selected;
        }
        boolean resized = demand == null || demand.width() != updated.width() || demand.height() != updated.height();
        if (!updated.equals(demand)) {
            desired = fit(updated);
            demand = updated;
            stableFrames = 0;
        }
        if (selected == null || resized) {
            selected = desired;
        } else if (++stableFrames >= GROWTH_FRAMES) {
            selected = desired;
        } else {
            List<PortalShaderRenderer.Resolution> reduced = new ArrayList<>(selected.size());
            for (int depth = 0; depth < selected.size(); depth++) {
                reduced.add(dimensions(updated, Math.min(selected.get(depth).width(), desired.get(depth).width())));
            }
            selected = List.copyOf(reduced);
        }
        return selected;
    }

    private List<PortalShaderRenderer.Resolution> fit(Demand demand) {
        List<Integer> widths = new ArrayList<>(Collections.nCopies(PortalRenderTargets.DEPTHS, demand.width()));
        if (bytes(demand, widths) <= limit) {
            return sizes(demand, widths);
        }
        int minimum = Math.min(256, demand.width());
        Collections.fill(widths, minimum);
        widths.set(0, demand.width());
        long activeLimit = Math.max(limit, bytes(demand, widths));
        for (int depth = 0; depth < widths.size(); depth++) {
            int lower = Math.max(1, minimum / STEP);
            int upper = Math.max(1, demand.width() / STEP);
            widths.set(depth, demand.width());
            if (bytes(demand, widths) <= activeLimit) {
                continue;
            }
            while (lower < upper) {
                int midpoint = (lower + upper + 1) / 2;
                widths.set(depth, midpoint * STEP);
                if (bytes(demand, widths) <= activeLimit) {
                    lower = midpoint;
                } else {
                    upper = midpoint - 1;
                }
            }
            widths.set(depth, Math.min(demand.width(), lower * STEP));
        }
        return sizes(demand, widths);
    }

    private static long bytes(Demand demand, List<Integer> widths) {
        long bytes = demand.retainedBytes();
        for (Dimension entry : demand.programs().values()) {
            ProgramSet programs = entry.programs();
            for (int depth : entry.depths()) {
                PortalShaderRenderer.Resolution size = dimensions(demand, widths.get(depth));
                bytes = Math.addExact(bytes, PortalIrisResources.targets(programs, size.width(), size.height()));
            }
            long shadows = Math.multiplyExact(PortalIrisResources.shadows(programs),
                PortalIrisResources.shareShadows(programs) ? 1 : entry.depths().size());
            bytes = Math.addExact(bytes, shadows);
        }
        return bytes;
    }

    private static List<PortalShaderRenderer.Resolution> sizes(Demand demand, List<Integer> widths) {
        List<PortalShaderRenderer.Resolution> sizes = new ArrayList<>(widths.size());
        for (int width : widths) {
            sizes.add(dimensions(demand, width));
        }
        return List.copyOf(sizes);
    }

    private static PortalShaderRenderer.Resolution dimensions(Demand demand, int width) {
        return new PortalShaderRenderer.Resolution(width, Math.max(1, (int) ((long) demand.height() * width / demand.width())));
    }

    record Dimension(ProgramSet programs, List<Integer> depths) {
        Dimension {
            List<Integer> sorted = new ArrayList<>(depths);
            sorted.sort(Integer::compare);
            depths = List.copyOf(sorted);
        }
    }

    record Demand(int width, int height, Map<NamespacedId, Dimension> programs, long resourceRevision, long retainedBytes) {
        Demand {
            programs = Map.copyOf(programs);
        }
    }
}
