package art.arcane.automator;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.core.SectionPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;

final class WormholesStatus {
    private static final Logger LOGGER = LoggerFactory.getLogger("InstanceAutomator");
    private static final String CLIENT_CLASS = "art.arcane.wormholes.modded.client.WormholesClient";
    private static final String RENDERER_CLASS = "art.arcane.wormholes.modded.client.render.ClientPortalRenderer";
    private static String previousError;

    private WormholesStatus() {
    }

    static void append(JsonObject state) {
        Class<?> clientClass;
        try {
            clientClass = Class.forName(CLIENT_CLASS, false, WormholesStatus.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
            return;
        }
        append(state, clientClass);
    }

    static void append(JsonObject state, Class<?> clientClass) {
        try {
            Object client = clientClass.getMethod("instance").invoke(null);
            if (client == null) {
                return;
            }
            Object line = clientClass.getMethod("debugLine").invoke(client);
            if (line instanceof String text) {
                state.addProperty("wormholes", text);
                previousError = null;
            } else {
                report(state, new ReflectiveOperationException("Wormholes debugLine did not return a string"));
            }
        } catch (ReflectiveOperationException failure) {
            report(state, failure);
        }
    }

    static RenderTarget portalTarget(int portalKey) {
        try {
            Class<?> rendererClass = Class.forName("art.arcane.wormholes.modded.client.render.ClientPortalRenderer");
            Object renderer = rendererClass.getMethod("instance").invoke(null);
            return portalTarget(renderer, portalKey);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read Wormholes portal render target " + portalKey, failure);
        }
    }

    static RenderTarget portalTarget(Object renderer, int portalKey) throws ReflectiveOperationException {
        if (renderer == null) {
            throw new IllegalStateException("Wormholes portal renderer is not initialized");
        }
        Field portalsField = renderer.getClass().getDeclaredField("portals");
        portalsField.setAccessible(true);
        Object entries = portalsField.get(renderer);
        if (!(entries instanceof Map<?, ?> portals)) {
            throw new IllegalStateException("Wormholes portal renderer has no portal map");
        }
        Object portal = portals.get(portalKey);
        if (portal == null) {
            throw new IllegalArgumentException("Wormholes portal " + portalKey + " does not exist");
        }
        Field targetField = portal.getClass().getDeclaredField("target");
        targetField.setAccessible(true);
        Object target = targetField.get(portal);
        if (!(target instanceof RenderTarget renderTarget)) {
            throw new IllegalStateException("Wormholes portal " + portalKey + " has no rendered target");
        }
        return renderTarget;
    }

    static JsonObject portalBlock(int portalKey, int x, int y, int z) {
        try {
            Class<?> clientClass = Class.forName(CLIENT_CLASS);
            Object client = clientClass.getMethod("instance").invoke(null);
            if (client == null) {
                throw new IllegalStateException("Wormholes client is not initialized");
            }
            Object session = clientClass.getMethod("session").invoke(client);
            JsonObject result = portalBlock(session, portalKey, x, y, z);
            appendPortalMesh(result, portalKey, x >> 4, y >> 4, z >> 4);
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot inspect Wormholes portal block " + portalKey, failure);
        }
    }

    private static void appendPortalMesh(JsonObject result, int portalKey, int sectionX, int sectionY, int sectionZ) {
        try {
            Class<?> rendererClass = Class.forName(RENDERER_CLASS);
            Object renderer = rendererClass.getMethod("instance").invoke(null);
            result.add("renderer", portalMesh(renderer, portalKey, sectionX, sectionY, sectionZ));
        } catch (ClassNotFoundException absent) {
            return;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            result.addProperty("rendererError", failure.toString());
        }
    }

    static JsonObject portalMesh(Object renderer, int portalKey, int sectionX, int sectionY, int sectionZ)
        throws ReflectiveOperationException {
        JsonObject result = new JsonObject();
        result.addProperty("present", renderer != null);
        if (renderer == null) {
            return result;
        }
        Map<?, ?> portals = (Map<?, ?>) field(renderer, "portals");
        Object portal = portals.get(portalKey);
        result.addProperty("portalPresent", portal != null);
        if (portal == null) {
            return result;
        }
        long key = SectionPos.asLong(sectionX, sectionY, sectionZ);
        Map<?, ?> sections = (Map<?, ?>) field(portal, "sections");
        Collection<?> dirty = (Collection<?>) field(portal, "dirty");
        Collection<?> building = (Collection<?>) field(portal, "building");
        Collection<?> evicted = (Collection<?>) field(portal, "evicted");
        Collection<?> drawn = (Collection<?>) field(portal, "drawSections");
        Object section = sections.get(key);
        boolean rendered = (Boolean) field(portal, "rendered");
        result.addProperty("active", (Boolean) field(portal, "active"));
        result.addProperty("portalRendered", rendered);
        result.addProperty("generation", (Number) field(portal, "generation"));
        result.addProperty("gpuSectionPresent", section != null);
        result.addProperty("dirty", dirty.contains(key));
        result.addProperty("building", building.contains(key));
        result.addProperty("evicted", evicted.contains(key));
        result.addProperty("selectedForDraw", section != null && drawn.contains(section));
        result.addProperty("gpuSections", sections.size());
        result.addProperty("dirtySections", dirty.size());
        result.addProperty("buildingSections", building.size());
        result.addProperty("evictedSections", evicted.size());
        result.addProperty("drawSections", drawn.size());
        Object scene = field(portal, "scene");
        Iterable<?> keys = (Iterable<?>) invoke(scene, "sectionKeys");
        int sceneSections = 0;
        for (Object ignored : keys) {
            sceneSections++;
        }
        result.addProperty("sceneSections", sceneSections);
        JsonObject layers = new JsonObject();
        boolean hasIndices = false;
        if (section != null) {
            Map<?, ?> meshes = (Map<?, ?>) field(section, "layers");
            for (Map.Entry<?, ?> entry : meshes.entrySet()) {
                Object mesh = entry.getValue();
                JsonObject layer = new JsonObject();
                Number indexCount = (Number) field(mesh, "indexCount");
                hasIndices |= indexCount.intValue() > 0;
                layer.addProperty("indexCount", indexCount);
                layer.addProperty("vertexBytes", (Number) invoke(field(mesh, "vertices"), "size"));
                layer.addProperty("indexBytes", (Number) invoke(field(mesh, "indices"), "size"));
                layers.add(entry.getKey().toString(), layer);
            }
        }
        result.addProperty("drawn", rendered && section != null && drawn.contains(section) && hasIndices);
        result.add("layers", layers);
        return result;
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static Object invoke(Object owner, String name) throws ReflectiveOperationException {
        Method method = owner.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(owner);
    }

    static JsonObject portalBlock(Object session, int portalKey, int x, int y, int z) throws ReflectiveOperationException {
        if (session == null) {
            throw new IllegalStateException("Wormholes client session is not initialized");
        }
        Object portal = session.getClass().getMethod("portal", int.class).invoke(session, portalKey);
        if (portal == null) {
            throw new IllegalArgumentException("Wormholes portal " + portalKey + " does not exist");
        }
        Object geometry = portal.getClass().getMethod("geometry").invoke(portal);
        Object meshes = session.getClass().getMethod("meshes").invoke(session);
        Object view = meshes.getClass().getMethod("view", int.class).invoke(meshes, portalKey);
        JsonObject result = new JsonObject();
        result.addProperty("portalKey", portalKey);
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        result.addProperty("sectionX", x >> 4);
        result.addProperty("sectionY", y >> 4);
        result.addProperty("sectionZ", z >> 4);
        result.addProperty("depth", (Number) geometry.getClass().getMethod("depthBlocks").invoke(geometry));
        result.addProperty("viewPresent", view != null);
        if (view == null) {
            result.addProperty("sectionPresent", false);
            return result;
        }
        result.addProperty("generation", (Number) view.getClass().getMethod("generation").invoke(view));
        Object bounds = view.getClass().getMethod("bounds").invoke(view);
        JsonObject boundsState = new JsonObject();
        for (String field : new String[] {"minX", "minY", "minZ", "sizeX", "sizeY", "sizeZ"}) {
            boundsState.addProperty(field, (Number) bounds.getClass().getMethod(field).invoke(bounds));
        }
        result.add("bounds", boundsState);
        Object section = view.getClass().getMethod("section", long.class).invoke(view, SectionPos.asLong(x >> 4, y >> 4, z >> 4));
        result.addProperty("sectionPresent", section != null);
        if (section != null) {
            int cell = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
            result.addProperty("cell", cell);
            result.addProperty("revision", (Number) section.getClass().getMethod("revision").invoke(section));
            Object state = section.getClass().getMethod("state", int.class).invoke(section, cell);
            result.addProperty("state", state == null ? null : state.toString());
            result.addProperty("skyLight", (Number) section.getClass().getMethod("light", boolean.class, int.class).invoke(section, true, cell));
            result.addProperty("blockLight", (Number) section.getClass().getMethod("light", boolean.class, int.class).invoke(section, false, cell));
            result.addProperty("blockEntity", section.getClass().getMethod("blockEntity", int.class).invoke(section, cell) != null);
        }
        return result;
    }

    private static void report(JsonObject state, ReflectiveOperationException failure) {
        String diagnostic = failure.toString();
        state.addProperty("wormholesError", diagnostic);
        if (!diagnostic.equals(previousError)) {
            LOGGER.error("Cannot read Wormholes client debug state", failure);
            previousError = diagnostic;
        }
    }
}
