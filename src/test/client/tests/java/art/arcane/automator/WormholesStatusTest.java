package art.arcane.automator;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WormholesStatusTest {
    public static void main(String[] args) throws ReflectiveOperationException {
        JsonObject absent = new JsonObject();
        WormholesStatus.append(absent);
        require(absent.isEmpty(), "Absent mod must not add a status or an error");
        JsonObject present = new JsonObject();
        WormholesStatus.append(present, PresentClient.class);
        require(present.get("wormholes").getAsString().equals("portals=2 meshes=17"), "Present mod must expose its exact debug line");
        require(!present.has("wormholesError"), "Successful lookup must not report an error");
        JsonObject uninitialized = new JsonObject();
        WormholesStatus.append(uninitialized, UninitializedClient.class);
        require(uninitialized.isEmpty(), "Uninitialized mod must omit status");
        JsonObject broken = new JsonObject();
        WormholesStatus.append(broken, BrokenClient.class);
        require(!broken.has("wormholes") && broken.get("wormholesError").getAsString().contains("InvocationTargetException"),
            "Reflection failures must be observable in state");
        try {
            WormholesStatus.portalTarget(new TestRenderer(), 6);
            throw new AssertionError("Unknown portal must fail");
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage().contains("6 does not exist"), "Missing portal must identify its key");
        }
        try {
            WormholesStatus.portalTarget(new TestRenderer(), 7);
            throw new AssertionError("Unrendered portal must fail");
        } catch (IllegalStateException expected) {
            require(expected.getMessage().contains("7 has no rendered target"), "Missing target must identify its key");
        }
        JsonObject mesh = WormholesStatus.portalMesh(new TestRenderer(), 7, -1, -2, -1);
        require(mesh.get("gpuSectionPresent").getAsBoolean() && mesh.get("drawn").getAsBoolean(),
            "Uploaded negative-coordinate section selected in a rendered portal must report drawn");
        require(mesh.get("dirty").getAsBoolean() && !mesh.get("building").getAsBoolean() && !mesh.get("evicted").getAsBoolean(),
            "Mesh scheduling flags must refer to the requested section");
        require(mesh.get("sceneSections").getAsInt() == 3 && mesh.get("gpuSections").getAsInt() == 1
            && mesh.get("buildingSections").getAsInt() == 1 && mesh.get("evictedSections").getAsInt() == 1,
            "Received, uploaded, pending and evicted totals must remain separate");
        JsonObject solid = mesh.getAsJsonObject("layers").getAsJsonObject("SOLID");
        require(solid.get("indexCount").getAsInt() == 36 && solid.get("vertexBytes").getAsLong() == 768
            && solid.get("indexBytes").getAsLong() == 72, "GPU diagnostics must preserve actual index counts and buffer byte sizes");
        JsonObject missingMesh = WormholesStatus.portalMesh(new TestRenderer(), 7, 0, 0, 0);
        require(!missingMesh.get("gpuSectionPresent").getAsBoolean() && !missingMesh.get("drawn").getAsBoolean()
            && missingMesh.getAsJsonObject("layers").isEmpty(), "Unmeshed sections must never report GPU geometry or drawing");
        TestRenderer notRendered = new TestRenderer();
        notRendered.portals.get(7).rendered = false;
        JsonObject staleDrawList = WormholesStatus.portalMesh(notRendered, 7, -1, -2, -1);
        require(staleDrawList.get("selectedForDraw").getAsBoolean() && !staleDrawList.get("drawn").getAsBoolean(),
            "A stale draw list must not imply the portal rendered this frame");
        TestRenderer emptyMesh = new TestRenderer();
        emptyMesh.portals.get(7).sections.get(-2L).layers.get("SOLID").indexCount = 0;
        require(!WormholesStatus.portalMesh(emptyMesh, 7, -1, -2, -1).get("drawn").getAsBoolean(),
            "An empty uploaded mesh must not imply geometry was drawn");
        require(!WormholesStatus.portalMesh(new TestRenderer(), 6, 0, 0, 0).get("portalPresent").getAsBoolean(),
            "Missing renderer portals must remain distinct from missing sections");
        require(!WormholesStatus.portalMesh(null, 7, 0, 0, 0).get("present").getAsBoolean(),
            "Absent optional renderers must be reportable");
        System.out.println("Optional Wormholes status, absent mod and reflection diagnostics passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class TestRenderer {
        private final Map<Integer, TestPortal> portals = Map.of(7, new TestPortal());
    }

    private static final class TestPortal {
        private final Object target = null;
        private final Map<Long, TestSection> sections = Map.of(-2L, new TestSection());
        private final Set<Long> dirty = Set.of(-2L);
        private final Set<Long> building = Set.of(1L);
        private final Set<Long> evicted = Set.of(2L);
        private final List<TestSection> drawSections = List.copyOf(sections.values());
        private final TestScene scene = new TestScene();
        private final boolean active = true;
        private final int generation = 3;
        private boolean rendered = true;
    }

    private static final class TestSection {
        private final Map<String, TestMesh> layers = Map.of("SOLID", new TestMesh());
    }

    private static final class TestMesh {
        private int indexCount = 36;
        private final TestBuffer vertices = new TestBuffer(768L);
        private final TestBuffer indices = new TestBuffer(72L);
    }

    private record TestBuffer(long size) {
    }

    private static final class TestScene {
        public Iterable<Long> sectionKeys() {
            return List.of(-2L, 1L, 2L);
        }
    }

    public static final class PresentClient {
        public static PresentClient instance() {
            return new PresentClient();
        }

        public String debugLine() {
            return "portals=2 meshes=17";
        }
    }

    public static final class UninitializedClient {
        public static UninitializedClient instance() {
            return null;
        }
    }

    public static final class BrokenClient {
        public static BrokenClient instance() {
            throw new IllegalStateException("test status failure");
        }
    }
}
