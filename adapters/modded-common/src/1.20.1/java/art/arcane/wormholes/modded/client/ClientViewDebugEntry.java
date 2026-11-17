package art.arcane.wormholes.modded.client;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Wormholes lines added to the F3 screen; 1.20.1 has no debug entry registry, so the overlay mixin reads these. */
public final class ClientViewDebugEntry {
    public static final ResourceLocation STATUS_ID = new ResourceLocation("wormholes", "status");
    public static final ResourceLocation DETAIL_ID = new ResourceLocation("wormholes", "status_detail");

    private static final Map<ResourceLocation, ClientViewDebugEntry> ENTRIES = new LinkedHashMap<>();
    private static final Set<ResourceLocation> SHOWN = new HashSet<>();

    private final Supplier<List<String>> lines;

    public ClientViewDebugEntry(Supplier<List<String>> lines) {
        this.lines = Objects.requireNonNull(lines, "lines");
    }

    public static void register(ResourceLocation id, ClientViewDebugEntry entry) {
        ENTRIES.put(Objects.requireNonNull(id, "id"), Objects.requireNonNull(entry, "entry"));
    }

    public static void setShown(ResourceLocation id, boolean shown) {
        if (shown) {
            SHOWN.add(Objects.requireNonNull(id, "id"));
        } else {
            SHOWN.remove(id);
        }
    }

    public static List<String> shownLines() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<ResourceLocation, ClientViewDebugEntry> entry : ENTRIES.entrySet()) {
            if (SHOWN.contains(entry.getKey())) {
                result.addAll(entry.getValue().lines.get());
            }
        }
        return result;
    }
}
