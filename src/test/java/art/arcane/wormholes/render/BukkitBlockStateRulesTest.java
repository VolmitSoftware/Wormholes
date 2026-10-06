package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.frame.Rotation16;
import art.arcane.optics.math.Face;
import art.arcane.optics.state.BlockStateRules;
import art.arcane.optics.state.StateProperties;

final class BukkitBlockStateRulesTest {
    private static final String GOLDENS = "optics/src/test/resources/block-state-goldens.txt";
    private static final String[] HORIZONTAL = {"north", "south", "east", "west"};
    private static final String[] BOOLEANS = {"true", "false"};
    private static final String[] SIDES = {"north", "south", "east", "west", "up", "down"};
    private static final Face[] HORIZONTAL_CLOCKWISE = {Face.N, Face.E, Face.S, Face.W};
    private static final AxisPermutation NORTH_MIRROR = AxisPermutation.mirror(Frame.canonical(Face.N), QuarterTurn.DEGREES_0);
    private static final AxisPermutation QUARTER_TURN = AxisPermutation.between(Frame.canonical(Face.N), Frame.canonical(Face.E));
    private static final Map<String, Map<String, Set<String>>> ALLOWED = new HashMap<String, Map<String, Set<String>>>();

    private final BukkitProjectorBlocks blocks = new BukkitProjectorBlocks(material -> false);

    @BeforeAll
    static void registerStates() throws IOException {
        allow("minecraft:stone");
        allow("minecraft:oak_stairs", "facing", HORIZONTAL, "half", new String[] {"top", "bottom"},
            "shape", new String[] {"straight", "inner_left", "inner_right", "outer_left", "outer_right"}, "waterlogged", BOOLEANS);
        allow("minecraft:oak_door", "facing", HORIZONTAL, "half", new String[] {"upper", "lower"}, "hinge", new String[] {"left", "right"},
            "open", BOOLEANS, "powered", BOOLEANS);
        allow("minecraft:chest", "facing", HORIZONTAL, "type", new String[] {"single", "left", "right"}, "waterlogged", BOOLEANS);
        allow("minecraft:rail", "shape", new String[] {"north_south", "east_west", "ascending_east", "ascending_west", "ascending_north",
            "ascending_south", "south_east", "south_west", "north_west", "north_east"}, "waterlogged", BOOLEANS);
        allow("minecraft:cobblestone_wall", "north", new String[] {"none", "low", "tall"}, "south", new String[] {"none", "low", "tall"},
            "east", new String[] {"none", "low", "tall"}, "west", new String[] {"none", "low", "tall"}, "up", BOOLEANS, "waterlogged", BOOLEANS);
        allow("minecraft:redstone_wire", "north", new String[] {"none", "side", "up"}, "south", new String[] {"none", "side", "up"},
            "east", new String[] {"none", "side", "up"}, "west", new String[] {"none", "side", "up"}, "power", numbers(16));
        allow("minecraft:oak_sign", "rotation", numbers(16), "waterlogged", BOOLEANS);
        allow("minecraft:oak_slab", "type", new String[] {"top", "bottom", "double"}, "waterlogged", BOOLEANS);
        allow("minecraft:lantern", "hanging", BOOLEANS, "waterlogged", BOOLEANS);
        for (String mushroom : new String[] {"minecraft:red_mushroom_block", "minecraft:brown_mushroom_block", "minecraft:mushroom_stem"}) {
            allow(mushroom, SIDES[0], BOOLEANS, SIDES[1], BOOLEANS, SIDES[2], BOOLEANS, SIDES[3], BOOLEANS, SIDES[4], BOOLEANS, SIDES[5], BOOLEANS);
        }
        for (String[] vector : vectors()) {
            observe(vector[0]);
            observe(vector[3]);
        }
    }

    @Test
    void goldenVectorsRewriteBlockDataLikePaper() {
        withPaper(() -> {
            List<String[]> vectors = vectors();
            for (String[] vector : vectors) {
                BlockData source = Bukkit.createBlockData(vector[0]);
                AxisPermutation permutation = AxisPermutation.ofIndex(Integer.parseInt(vector[1]));
                BlockData rewritten = blocks.transform(source, permutation);
                String context = vector[0] + " via " + permutation;
                assertEquals(vector[3], rewritten.getAsString(), context);
                assertEquals(BlockStateRules.affects(blocks.properties(source)), blocks.requiresTransform(source), context);
                assertSame(rewritten, blocks.transform(source, permutation), context);
                assertEquals(vector[0], source.getAsString(), context);
            }
            assertTrue(vectors.size() >= 300);
        });
    }

    @Test
    void propertiesReadEveryValueAndWritesIgnoreUnknownNamesAndUnsupportedValues() {
        withPaper(() -> {
            BlockData stairs = data("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
            StateProperties properties = blocks.properties(stairs);
            assertEquals(4, properties.size());
            assertEquals("north", properties.get("facing"));
            assertSame(StateProperties.EMPTY, blocks.properties(data("minecraft:stone")));

            BlockData written = blocks.withProperties(stairs, properties.with("facing", "up").with("half", "top").with("color", "red"));

            assertEquals("minecraft:oak_stairs[facing=north,half=top,shape=straight,waterlogged=false]", written.getAsString());
            assertSame(stairs, blocks.withProperties(stairs, properties));
            assertSame(stairs, blocks.withProperties(stairs, properties.with("facing", "down")));
        });
    }

    @Test
    void onlyBlocksCarryingRewrittenPropertiesRequireATransform() {
        withPaper(() -> {
            assertFalse(blocks.requiresTransform(data("minecraft:stone")));
            assertTrue(blocks.requiresTransform(data("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")));
            assertTrue(blocks.requiresTransform(data("minecraft:oak_slab[type=bottom,waterlogged=false]")));
            assertTrue(blocks.requiresTransform(data("minecraft:lantern[hanging=true,waterlogged=false]")));
            assertTrue(blocks.requiresTransform(data("minecraft:cobblestone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]")));
            assertTrue(blocks.requiresTransform(data("minecraft:redstone_wire[east=none,north=none,power=0,south=none,west=none]")));
            assertTrue(blocks.requiresTransform(data("minecraft:chest[facing=north,type=left,waterlogged=false]")));
        });
    }

    @Test
    void chiralBlocksSwapSidesOnlyWhenReflected() {
        withPaper(() -> {
            assertRewrite("minecraft:oak_stairs[facing=north,half=bottom,shape=inner_left,waterlogged=false]", NORTH_MIRROR,
                "minecraft:oak_stairs[facing=south,half=bottom,shape=inner_right,waterlogged=false]");
            assertRewrite("minecraft:oak_stairs[facing=north,half=bottom,shape=outer_left,waterlogged=false]", QUARTER_TURN,
                "minecraft:oak_stairs[facing=east,half=bottom,shape=outer_left,waterlogged=false]");
            assertRewrite("minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]", NORTH_MIRROR,
                "minecraft:oak_door[facing=south,half=lower,hinge=right,open=false,powered=false]");
            assertRewrite("minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]", QUARTER_TURN,
                "minecraft:oak_door[facing=east,half=lower,hinge=left,open=false,powered=false]");
            assertRewrite("minecraft:chest[facing=north,type=left,waterlogged=false]", NORTH_MIRROR,
                "minecraft:chest[facing=south,type=right,waterlogged=false]");
            assertRewrite("minecraft:chest[facing=north,type=single,waterlogged=false]", NORTH_MIRROR,
                "minecraft:chest[facing=south,type=single,waterlogged=false]");
            assertRewrite("minecraft:rail[shape=south_east,waterlogged=false]", NORTH_MIRROR, "minecraft:rail[shape=north_east,waterlogged=false]");
        });
    }

    @Test
    void wallHeightsAndRedstoneConnectionsFollowTheFrame() {
        withPaper(() -> {
            assertRewrite("minecraft:cobblestone_wall[east=tall,north=low,south=none,up=true,waterlogged=false,west=none]", QUARTER_TURN,
                "minecraft:cobblestone_wall[east=low,north=none,south=tall,up=true,waterlogged=false,west=none]");
            assertRewrite("minecraft:redstone_wire[east=side,north=up,power=0,south=none,west=none]", QUARTER_TURN,
                "minecraft:redstone_wire[east=up,north=none,power=0,south=side,west=none]");
        });
    }

    @Test
    void signRotationsFollowEveryQuarterTurnMirrorAndTilt() {
        withPaper(() -> {
            for (int fromIndex = 0; fromIndex < HORIZONTAL_CLOCKWISE.length; fromIndex++) {
                for (int toIndex = 0; toIndex < HORIZONTAL_CLOCKWISE.length; toIndex++) {
                    AxisPermutation permutation = AxisPermutation.between(Frame.canonical(HORIZONTAL_CLOCKWISE[fromIndex]),
                        Frame.canonical(HORIZONTAL_CLOCKWISE[toIndex]));
                    for (int rotation = 0; rotation < 16; rotation++) {
                        assertRewrite(sign(rotation), permutation, sign(Rotation16.rotate(rotation, toIndex - fromIndex)));
                    }
                }
            }
            assertRewrite(sign(9), QUARTER_TURN, sign(13));
            assertRewrite(sign(9), NORTH_MIRROR, sign(15));
            assertRewrite(sign(9), AxisPermutation.mirror(Frame.canonical(Face.E), QuarterTurn.DEGREES_0), sign(7));
            assertRewrite(sign(9), AxisPermutation.mirror(Frame.canonical(Face.N), QuarterTurn.DEGREES_180), sign(1));
            assertRewrite(sign(9), AxisPermutation.between(Frame.canonical(Face.N), Frame.canonical(Face.U)), sign(9));
        });
    }

    @Test
    void mushroomFacesFollowEveryMirrorAndEveryFramePair() {
        withPaper(() -> {
            for (String mushroom : new String[] {"minecraft:red_mushroom_block", "minecraft:brown_mushroom_block", "minecraft:mushroom_stem"}) {
                for (Face normal : Face.values()) {
                    for (QuarterTurn turns : QuarterTurn.values()) {
                        assertMushroomFaces(mushroom, AxisPermutation.mirror(Frame.canonical(normal), turns));
                    }
                }
            }
            for (Face from : Face.values()) {
                for (Face to : Face.values()) {
                    assertMushroomFaces("minecraft:red_mushroom_block", AxisPermutation.between(Frame.canonical(from), Frame.canonical(to)));
                }
            }
        });
    }

    @Test
    void floorMirrorsFlipHalvesAttachmentsAndClimbingRails() {
        withPaper(() -> {
            AxisPermutation floorMirror = AxisPermutation.mirror(Frame.canonical(Face.U), QuarterTurn.DEGREES_0);
            assertRewrite("minecraft:oak_slab[type=bottom,waterlogged=false]", floorMirror, "minecraft:oak_slab[type=top,waterlogged=false]");
            assertRewrite("minecraft:lantern[hanging=true,waterlogged=false]", floorMirror, "minecraft:lantern[hanging=false,waterlogged=false]");
            assertRewrite("minecraft:oak_stairs[facing=north,half=bottom,shape=inner_left,waterlogged=false]", floorMirror,
                "minecraft:oak_stairs[facing=north,half=top,shape=inner_left,waterlogged=false]");
            assertRewrite("minecraft:rail[shape=ascending_north,waterlogged=false]", floorMirror,
                "minecraft:rail[shape=ascending_south,waterlogged=false]");
        });
    }

    private void assertMushroomFaces(String mushroom, AxisPermutation permutation) {
        for (int mask = 0; mask < 64; mask++) {
            BlockData original = data(mushroom(mushroom, mask));
            StateProperties projected = blocks.properties(blocks.transform(original, permutation));
            for (Face face : Face.values()) {
                assertEquals(Boolean.toString((mask & (1 << face.ordinal())) != 0), projected.get(side(permutation.face(face))),
                    mushroom + " " + permutation + " mask " + mask + " " + face);
            }
            assertEquals(mushroom(mushroom, mask), original.getAsString());
        }
    }

    private void assertRewrite(String source, AxisPermutation permutation, String expected) {
        assertEquals(expected, blocks.transform(data(source), permutation).getAsString(), source + " via " + permutation);
    }

    private static String sign(int rotation) {
        return "minecraft:oak_sign[rotation=" + rotation + ",waterlogged=false]";
    }

    private static String mushroom(String key, int mask) {
        TreeMap<String, String> values = new TreeMap<String, String>();
        for (Face face : Face.values()) {
            values.put(side(face), Boolean.toString((mask & (1 << face.ordinal())) != 0));
        }
        return format(key, values);
    }

    private static String side(Face face) {
        return switch (face) {
            case N -> "north";
            case S -> "south";
            case E -> "east";
            case W -> "west";
            case U -> "up";
            case D -> "down";
        };
    }

    private static String[] numbers(int count) {
        String[] values = new String[count];
        for (int index = 0; index < count; index++) {
            values[index] = Integer.toString(index);
        }
        return values;
    }

    private static void allow(String key, Object... properties) {
        Map<String, Set<String>> allowed = ALLOWED.computeIfAbsent(key, ignored -> new HashMap<String, Set<String>>());
        for (int index = 0; index < properties.length; index += 2) {
            allowed.computeIfAbsent((String) properties[index], ignored -> new HashSet<String>()).addAll(List.of((String[]) properties[index + 1]));
        }
    }

    private static void observe(String state) {
        Map<String, Set<String>> allowed = ALLOWED.computeIfAbsent(key(state), ignored -> new HashMap<String, Set<String>>());
        for (Map.Entry<String, String> entry : values(state).entrySet()) {
            allowed.computeIfAbsent(entry.getKey(), ignored -> new HashSet<String>()).add(entry.getValue());
        }
    }

    private static List<String[]> vectors() throws IOException {
        Path goldens = goldens();
        assertNotNull(goldens, GOLDENS);
        List<String[]> vectors = new ArrayList<String[]>();
        for (String line : Files.readAllLines(goldens, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                vectors.add(line.split("\t"));
            }
        }
        return vectors;
    }

    private static Path goldens() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(GOLDENS);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        return null;
    }

    private static BlockData data(String state) {
        return Bukkit.createBlockData(state);
    }

    private static String key(String state) {
        int open = state.indexOf('[');
        return open < 0 ? state : state.substring(0, open);
    }

    private static TreeMap<String, String> values(String state) {
        TreeMap<String, String> values = new TreeMap<String, String>();
        int open = state.indexOf('[');
        if (open < 0) {
            return values;
        }
        for (String pair : state.substring(open + 1, state.length() - 1).split(",")) {
            String[] parts = pair.split("=", 2);
            values.put(parts[0], parts[1]);
        }
        return values;
    }

    private static String format(String key, TreeMap<String, String> values) {
        if (values.isEmpty()) {
            return key;
        }
        StringBuilder builder = new StringBuilder(key).append('[');
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (builder.charAt(builder.length() - 1) != '[') {
                builder.append(',');
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.append(']').toString();
    }

    private static BlockData parse(String state) {
        String key = key(state);
        Map<String, Set<String>> allowed = ALLOWED.get(key);
        TreeMap<String, String> values = values(state);
        if (allowed == null || !allowed.keySet().equals(values.keySet())) {
            throw new IllegalArgumentException("Could not parse data: " + state);
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!allowed.get(entry.getKey()).contains(entry.getValue())) {
                throw new IllegalArgumentException("Could not parse data: " + state);
            }
        }
        return paperData(format(key, values));
    }

    private static BlockData paperData(String canonical) {
        InvocationHandler handler = new PaperData(canonical);
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] {BlockData.class}, handler);
    }

    private static void withPaper(ThrowingBody body) {
        synchronized (Bukkit.class) {
            Field serverField;
            Object previous;
            try {
                serverField = Bukkit.class.getDeclaredField("server");
                serverField.setAccessible(true);
                previous = serverField.get(null);
                serverField.set(null, paperServer());
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("cannot install the Paper stand-in", error);
            }
            try {
                body.run();
            } catch (IOException error) {
                throw new AssertionError(error);
            } finally {
                try {
                    serverField.set(null, previous);
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError("cannot restore the previous server", error);
                }
            }
        }
    }

    private static Server paperServer() {
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "createBlockData" -> args != null && args.length == 1 && args[0] instanceof String state
                    ? parse(state)
                    : unsupported(method);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                case "toString", "getName" -> "PaperBlockDataServer";
                default -> unsupported(method);
            });
    }

    private static Object unsupported(Method method) {
        throw new UnsupportedOperationException(method.getName());
    }

    @FunctionalInterface
    private interface ThrowingBody {
        void run() throws IOException;
    }

    private record PaperData(String canonical) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getAsString", "toString" -> canonical;
                case "getMaterial" -> Material.matchMaterial(key(canonical));
                case "clone" -> proxy;
                case "hashCode" -> Integer.valueOf(canonical.hashCode());
                case "equals" -> Boolean.valueOf(args[0] != null && Proxy.isProxyClass(args[0].getClass())
                    && Proxy.getInvocationHandler(args[0]) instanceof PaperData other && other.canonical.equals(canonical));
                default -> unsupported(method);
            };
        }
    }
}
