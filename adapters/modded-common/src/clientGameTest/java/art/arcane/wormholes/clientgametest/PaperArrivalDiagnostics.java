package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.ClientArrivalPreparation;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.sodium.SodiumArrivalSection;
import art.arcane.wormholes.modded.client.render.sodium.SodiumPortalRenderer;
import art.arcane.wormholes.modded.client.render.sodium.SodiumPortalSections;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.modded.mixin.client.ArrivalLevelAccess;
import art.arcane.wormholes.network.client.ArrivalChunkBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

final class PaperArrivalDiagnostics {
    private PaperArrivalDiagnostics() {
    }

    static String current() {
        ClientArrivalPreparation arrivals = WormholesClient.instance().arrivals();
        String summary = arrivals.diagnostics().toString();
        try {
            Object stage = field(arrivals, "stage");
            if (stage == null) {
                return summary;
            }
            ClientLevel level = (ClientLevel) field(stage, "level");
            ArrivalChunkBuffer buffer = (ArrivalChunkBuffer) field(stage, "buffer");
            summary += " chunks=" + buffer.receivedChunks() + " lights=" + ((ArrivalLevelAccess) level).wormholes$pendingLight().size()
                + " lightWork=" + level.getChunkSource().getLightEngine().hasLightWork();
            if (PaperArrivalDiagnostics.class.getClassLoader().getResource("net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer.class") == null) {
                return summary;
            }
            Object renderer = field(stage, "renderer");
            List<?> required = (List<?>) field(renderer, "required");
            RenderSectionManager sections = ((SodiumPortalRenderer) ((LevelRendererExtension) ClientWorldLoader.worldRenderer(level).renderer())
                .sodium$getWorldRenderer()).wormholes$sections();
            List<String> missing = new ArrayList<>();
            int missingCount = 0;
            for (Object value : required) {
                long key = (Long) value;
                RenderSection section = ((SodiumPortalSections) sections).wormholes$storage().getConsistent(key);
                if (section == null || !((SodiumArrivalSection) section).wormholes$terrainComplete()) {
                    missingCount++;
                    if (missing.size() < 16) {
                        missing.add(SectionPos.of(key) + ":" + (section == null ? "absent" : "update=" + section.getPendingUpdate()));
                    }
                }
            }
            return summary + " required=" + required.size() + " jobs=" + sections.getBuilder().getScheduledJobCount()
                + " workers=" + sections.getBuilder().getBusyThreadCount() + " missingCount=" + missingCount + " missing=" + missing;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to inspect arrival preparation", failure);
        }
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
