package art.arcane.automator;

import com.google.gson.JsonObject;

public final class PortalBlockStatusTest {
    public static void main(String[] args) throws ReflectiveOperationException {
        JsonObject present = WormholesStatus.portalBlock(new Session(), 1, -1, -17, -16);
        require(present.get("sectionPresent").getAsBoolean(), "Negative section key must find the received section");
        require(present.get("cell").getAsInt() == 3855, "Negative block coordinates must retain section-local cell order");
        require(present.get("state").getAsString().equals("minecraft:gold_block"), "Received cell must expose its exact state");
        require(present.get("generation").getAsInt() == 3 && present.get("revision").getAsInt() == 8, "Generation and revision must be reported");
        require(present.get("depth").getAsInt() == 160 && present.getAsJsonObject("bounds").get("minY").getAsInt() == -40,
            "Portal depth and advertised bounds must be reported");
        require(present.get("skyLight").getAsInt() == 12 && present.get("blockLight").getAsInt() == 7,
            "Received light values must be reported");
        JsonObject absent = WormholesStatus.portalBlock(new Session(), 1, 0, 10, 0);
        require(!absent.get("sectionPresent").getAsBoolean() && !absent.has("state"), "Unreceived sections must not masquerade as air");
        System.out.println("Portal cell inspection preserves negative coordinates and distinguishes unreceived sections");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static final class Session {
        public Portal portal(int key) { return new Portal(); }
        public Meshes meshes() { return new Meshes(); }
    }

    public static final class Portal {
        public Geometry geometry() { return new Geometry(160); }
    }

    public record Geometry(int depthBlocks) {
    }

    public static final class Meshes {
        public View view(int key) { return new View(); }
    }

    public static final class View {
        public int generation() { return 3; }
        public Bounds bounds() { return new Bounds(-160, -40, -160, 320, 160, 320); }
        public Section section(long key) { return key == -2L ? new Section() : null; }
    }

    public record Bounds(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
    }

    public static final class Section {
        public int revision() { return 8; }
        public String state(int cell) { return cell == 3855 ? "minecraft:gold_block" : "minecraft:air"; }
        public int light(boolean sky, int cell) { return sky ? 12 : 7; }
        public Object blockEntity(int cell) { return null; }
    }
}
