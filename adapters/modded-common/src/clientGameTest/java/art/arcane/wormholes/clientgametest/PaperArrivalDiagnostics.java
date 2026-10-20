package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.ClientArrivalPreparation;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.sodium.SodiumArrivalSection;
import art.arcane.wormholes.modded.client.render.sodium.SodiumPortalRenderer;
import art.arcane.wormholes.modded.client.render.sodium.SodiumPortalSections;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.modded.mixin.client.ArrivalLevelAccess;
import art.arcane.wormholes.network.client.ArrivalChunkBuffer;
import art.arcane.wormholes.network.client.ArrivalMessage;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class PaperArrivalDiagnostics {
    private PaperArrivalDiagnostics() {
    }

    static boolean readyAt(String dimension, double x) {
        ClientArrivalPreparation arrivals = WormholesClient.instance().arrivals();
        if (arrivals.diagnostics().readiness() != ArrivalMessage.Readiness.RENDER) {
            return false;
        }
        try {
            Object stage = field(arrivals, "stage");
            if (stage == null) {
                return false;
            }
            ArrivalMessage.Prepare prepare = (ArrivalMessage.Prepare) field(stage, "prepare");
            return prepare.world().dimension().equals(dimension) && prepare.arrival().x() == x;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to inspect arrival destination", failure);
        }
    }

    static String current() {
        ClientArrivalPreparation arrivals = WormholesClient.instance().arrivals();
        String summary = arrivals.diagnostics() + iris();
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

    private static String iris() {
        if (PaperArrivalDiagnostics.class.getClassLoader().getResource("net/irisshaders/iris/Iris.class") == null) {
            return "";
        }
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Object dimension = iris.getMethod("getCurrentDimension").invoke(null);
            Class<?> settingsType = Class.forName("art.arcane.wormholes.modded.client.render.iris.IrisWorldSettings");
            Method capture = settingsType.getDeclaredMethod("capture");
            capture.setAccessible(true);
            Object current = capture.invoke(null);
            Class<?> pipelinesType = Class.forName("art.arcane.wormholes.modded.client.render.iris.IrisPipelines");
            Field settings = pipelinesType.getDeclaredField("SETTINGS");
            settings.setAccessible(true);
            Map<?, ?> retained = (Map<?, ?>) settings.get(null);
            String key = dimension == null ? null : dimension.getClass().getMethod("getNamespace").invoke(dimension)
                + ":" + dimension.getClass().getMethod("getName").invoke(dimension);
            Object expected = retained.get(key);
            List<String> changes = new ArrayList<>();
            for (RecordComponent component : settingsType.getRecordComponents()) {
                Object actual = field(current, component.getName());
                Object previous = expected == null ? null : field(expected, component.getName());
                if (!Objects.equals(previous, actual)) {
                    changes.add(component.getName() + ":" + summary(previous) + "->" + summary(actual));
                }
            }
            return " irisDimension=" + key + " irisRetained=" + (expected != null) + " irisChanges=" + changes;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to inspect arrival shader settings", failure);
        }
    }

    private static String summary(Object value) {
        return value instanceof Map<?, ?> map ? "map(" + map.size() + "," + map.hashCode() + ")" : String.valueOf(value);
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
