package art.arcane.wormholes.clientgametest;

import art.arcane.optics.stream.BiomeVisual;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.WormholesClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BiomeRegistryFaults {
    public static final String PREFIX = "wormholes_fixture:missing/";
    private static final String TARGET = "iris:biomes/qa_visual";
    private static Mode mode = Mode.NONE;
    private static int renamed;
    private static int rejected;

    private BiomeRegistryFaults() {
    }

    public static void mode(Mode value) {
        mode = value;
        renamed = 0;
        rejected = 0;
    }

    public static int renamed() {
        return renamed;
    }

    public static int rejected() {
        return rejected;
    }

    public static ViewStreamMessage.MeshSection apply(ViewStreamMessage.MeshSection message) {
        if (mode == Mode.NONE || !message.biomes().palette().contains(TARGET)) {
            return message;
        }
        ClientPortal portal = WormholesClient.instance().session().portal(message.portalKey());
        boolean reject = mode == Mode.INVALID_ONE && portal != null && !portal.nested()
            && Math.abs(portal.geometry().originX() - 4) <= 1;
        List<String> palette = new ArrayList<>(message.biomes().palette().size());
        Map<String, BiomeVisual> visuals = new HashMap<>();
        for (String original : message.biomes().palette()) {
            String replacement = original.equals(TARGET) ? PREFIX + original.replace(':', '/') : original;
            palette.add(replacement);
            BiomeVisual visual = message.biomes().visuals().get(original);
            if (visual != null && !(reject && original.equals(TARGET))) {
                visuals.put(replacement, visual);
            }
        }
        renamed++;
        if (reject) {
            rejected++;
        }
        return new ViewStreamMessage.MeshSection(message.portalKey(), message.generation(), message.sectionX(), message.sectionY(),
            message.sectionZ(), message.revision(), message.backingState(), message.brick(),
            new SectionBiomes(palette, message.biomes().indices(), visuals));
    }

    public enum Mode {
        NONE, MISSING_KEYS, INVALID_ONE
    }
}
