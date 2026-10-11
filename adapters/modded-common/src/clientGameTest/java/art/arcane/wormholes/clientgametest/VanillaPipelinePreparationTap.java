package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class VanillaPipelinePreparationTap {
    private static final boolean PROFILE = Boolean.getBoolean("wormholes.clientgametest.profileVanillaPipelines");
    private static final AtomicLong SHADER_COMPILES = new AtomicLong();
    private static final AtomicLong PROGRAM_LINKS = new AtomicLong();
    private static final AtomicLong PROGRAM_LINK_ROUTES = new AtomicLong();
    private static final AtomicLong FRAMES = new AtomicLong();
    private static final List<Preparation> PREPARATIONS = new ArrayList<>();
    private static final Map<Object, NativeClosure> WATCHED_PROGRAMS = new IdentityHashMap<>();
    private static BankAccess readyBank;
    private static BankAccess assignedBank;
    private static long resourceCompletions;

    private VanillaPipelinePreparationTap() {
    }

    public static RendererAccess renderer() {
        return (RendererAccess) (Object) ClientPortalRenderer.instance();
    }

    public static boolean ready() {
        BankAccess bank = renderer().bank();
        return bank != null && bank.prepared() && !bank.closed() && readyBank == bank;
    }

    public static BankAccess assignedBank() {
        return assignedBank;
    }

    public static void bankAssigned(BankAccess bank) {
        assignedBank = bank;
    }

    public static long frames() {
        return FRAMES.get();
    }

    public static void renderedFrame() {
        FRAMES.incrementAndGet();
    }

    public static Counts counts() {
        return new Counts(SHADER_COMPILES.get(), PROGRAM_LINKS.get(), PROGRAM_LINK_ROUTES.get());
    }

    public static long shaderCompileStarted() {
        SHADER_COMPILES.incrementAndGet();
        return PROFILE ? System.nanoTime() : 0L;
    }

    public static long programLinkStarted() {
        PROGRAM_LINKS.incrementAndGet();
        return PROFILE ? System.nanoTime() : 0L;
    }

    public static void programLinkRoute() {
        PROGRAM_LINK_ROUTES.incrementAndGet();
    }

    public static void nativeCallFinished(String operation, long started) {
        if (started == 0L) {
            return;
        }
        NativeCompileEvent event = new NativeCompileEvent();
        event.operation = operation;
        event.nativeNanos = System.nanoTime() - started;
        event.frame = frames();
        event.commit();
    }

    public static synchronized void prepared(BankAccess bank, Counts before, long started, long finished) {
        Snapshot snapshot = snapshot(bank);
        for (Product product : snapshot.products()) {
            WATCHED_PROGRAMS.putIfAbsent(product.program(), new NativeClosure(0L, false));
        }
        Counts cost = counts().minus(before);
        long generation = resourceCompletions + 1L;
        PREPARATIONS.add(new Preparation(snapshot, cost, renderer().portalCount()));
        System.out.println("Vanilla bank preparation generation=" + generation + " prepareBeginNanos=" + started
            + " prepareEndNanos=" + finished + " prepareWallNanos=" + (finished - started) + " fixedNativeProducts=16"
            + " nativeShaderCompiles=" + cost.shaderCompiles() + " nativeProgramLinks=" + cost.programLinks()
            + " nativeProgramLinkRoutes=" + cost.programLinkRoutes());
        if (PROFILE) {
            PreparationEvent event = new PreparationEvent();
            event.generation = generation;
            event.prepareBeginNanos = started;
            event.prepareEndNanos = finished;
            event.wallNanos = finished - started;
            event.shaderCompiles = cost.shaderCompiles();
            event.programLinks = cost.programLinks();
            event.programLinkRoutes = cost.programLinkRoutes();
            event.fixedNativeProducts = snapshot.products().size();
            event.commit();
        }
    }

    public static synchronized void resourcesReady(BankAccess bank) {
        readyBank = bank;
        resourceCompletions++;
    }

    public static synchronized long resourceCompletions() {
        return resourceCompletions;
    }

    public static synchronized void programClosed(Object program, int programId) {
        NativeClosure previous = WATCHED_PROGRAMS.get(program);
        if (previous == null) {
            return;
        }
        boolean deleted = !GL20C.glIsProgram(programId)
            || GL20C.glGetProgrami(programId, GL20C.GL_DELETE_STATUS) == GL11C.GL_TRUE;
        WATCHED_PROGRAMS.put(program, new NativeClosure(previous.calls() + 1L, previous.deleted() || deleted));
    }

    public static Snapshot initialSnapshot(Minecraft client) {
        assertHidden(client);
        require(client.level == null && client.player == null, "initial preparation must precede world and portal creation");
        require(renderer().portalCount() == 0, "initial resource warm has portal scenes");
        require(ready(), "native resource completion did not publish a prepared fixed bank");
        Snapshot snapshot = snapshot(renderer().bank());
        verifyPreparation(snapshot.bank());
        return snapshot;
    }

    public static Snapshot snapshot(BankAccess bank) {
        require(bank != null && bank.prepared() && !bank.closed(), "fixed bank is absent, unprepared or closed");
        require(bank.descriptorCount() == 16 && bank.compiledCount() == 16, "fixed bank must contain 12 terrain and four auxiliary products");
        List<Product> products = new ArrayList<>(16);
        Set<Object> descriptors = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> compiled = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> programs = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Key key : Key.values()) {
            Product product = bank.product(key);
            require(product.key() == key && product.descriptor() != null && product.compiled() != null && product.program() != null,
                "missing descriptor, compiled handle or native program for " + key);
            require(product.live() && product.programId() > 0 && GL20C.glIsProgram(product.programId())
                && GL20C.glGetProgrami(product.programId(), GL20C.GL_LINK_STATUS) == GL11C.GL_TRUE,
                "fixed product is not a live linked OpenGL program: " + key);
            require(descriptors.add(product.descriptor()) && compiled.add(product.compiled()) && programs.add(product.program()),
                "fixed variants share descriptor, compiled handle or native program identity: " + key);
            require(bank.containsDescriptor(product.descriptor()), "fixed product is outside the private descriptor bank: " + key);
            products.add(product);
        }
        return new Snapshot(bank, List.copyOf(products));
    }

    public static void verifyGetters(Snapshot expected, String phase) {
        require(renderer().bank() == expected.bank(), phase + " replaced the retained bank");
        stable(expected, snapshot(expected.bank()), phase);
        for (boolean rgss : new boolean[]{false, true, false}) {
            for (boolean reflected : new boolean[]{false, true, false}) {
                for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                    verifyGetter(expected, Key.terrain(layer, reflected, rgss), phase);
                }
            }
        }
        for (Key key : new Key[]{Key.COMPOSITE, Key.COMPOSITE_SHAPE, Key.FEATHER, Key.LAYER}) {
            verifyGetter(expected, key, phase);
        }
        requireNoClosures(expected, phase);
        System.out.println("Vanilla prepared bank phase=" + phase + " variants=16 nativeGetterCompiles=0 nativeGetterLinks=0 frame=" + frames());
    }

    public static void stable(Snapshot expected, Snapshot actual, String phase) {
        require(expected.bank() == actual.bank(), phase + " replaced the fixed bank");
        for (Product previous : expected.products()) {
            Product next = actual.product(previous.key());
            require(previous.descriptor() == next.descriptor() && previous.compiled() == next.compiled()
                && previous.program() == next.program() && previous.programId() == next.programId(),
                phase + " changed descriptor, compiled handle or native program identity: " + previous.key());
        }
    }

    public static void verifyReload(Snapshot previous, Snapshot replacement, long completionsBefore, Counts costBefore) {
        require(resourceCompletions() > completionsBefore && readyBank == replacement.bank(), "resource reload did not finish the native readiness hook");
        require(previous.bank() != replacement.bank(), "real resource reload retained the old bank");
        require(previous.bank().closed() && !previous.bank().prepared() && previous.bank().compiledCount() == 0,
            "resource reload did not close and empty the old private compiled bank");
        for (Product old : previous.products()) {
            Product next = replacement.product(old.key());
            require(old.descriptor() != next.descriptor() && old.compiled() != next.compiled() && old.program() != next.program(),
                "resource reload reused an old native product: " + old.key());
            NativeClosure closure = closure(old.program());
            require(closure != null && closure.calls() == 1L && closure.deleted(),
                "resource reload must delete each old actual native program exactly once: " + old.key());
        }
        verifyPreparation(replacement.bank());
        Counts cost = counts().minus(costBefore);
        require(cost.shaderCompiles() > 0L && cost.programLinks() >= 16L && cost.programLinkRoutes() >= 16L,
            "real resource reload did not compile and link the replacement native bank");
        System.out.println("Vanilla resource reload fixedProducts=16 nativeShaderCompiles=" + cost.shaderCompiles()
            + " nativeProgramLinks=" + cost.programLinks() + " oldNativeProgramsClosed=16");
    }

    public static void assertHidden(Minecraft client) {
        require(Boolean.parseBoolean(System.getProperty("automator.hidden", "true")), "hidden renderer mode is disabled");
        require(NativeTestWindow.hiddenAndUnfocused(client) && NativeTestWindow.mouseReleased(client),
            "native GPU window must remain hidden, unfocused and mouse-released");
    }

    public static synchronized void finish() {
        PREPARATIONS.clear();
        WATCHED_PROGRAMS.clear();
    }

    public static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void verifyGetter(Snapshot expected, Key key, String phase) {
        Counts before = counts();
        expected.bank().verifyGetter(expected.product(key));
        Counts cost = counts().minus(before);
        require(cost.shaderCompiles() == 0L && cost.programLinks() == 0L && cost.programLinkRoutes() == 0L,
            phase + " getter performs actual native shader compilation or program linking: " + key + " " + cost);
    }

    private static synchronized void verifyPreparation(BankAccess bank) {
        for (Preparation preparation : PREPARATIONS) {
            if (preparation.snapshot().bank() == bank) {
                require(preparation.portals() == 0, "resource preparation requires no portal scenes");
                Counts cost = preparation.cost();
                require(cost.shaderCompiles() > 0L && cost.programLinks() >= 16L && cost.programLinkRoutes() >= 16L,
                    "fixed native bank was not fully compiled and linked during resource preparation");
                stable(preparation.snapshot(), snapshot(bank), "resource-preparation");
                return;
            }
        }
        throw new AssertionError("no observed native lifecycle preparation for the retained bank");
    }

    private static synchronized void requireNoClosures(Snapshot snapshot, String phase) {
        for (Product product : snapshot.products()) {
            NativeClosure closure = WATCHED_PROGRAMS.get(product.program());
            require(closure != null && closure.calls() == 0L, phase + " closed a retained native program: " + product.key());
        }
    }

    private static synchronized NativeClosure closure(Object program) {
        return WATCHED_PROGRAMS.get(program);
    }

    public interface RendererAccess {
        BankAccess bank();

        int portalCount();
    }

    public interface BankAccess {
        boolean prepared();

        boolean closed();

        int descriptorCount();

        int compiledCount();

        boolean containsDescriptor(Object descriptor);

        Product product(Key key);

        void verifyGetter(Product expected);
    }

    public enum Key {
        SOLID, CUTOUT, TRANSLUCENT,
        SOLID_REFLECTED, CUTOUT_REFLECTED, TRANSLUCENT_REFLECTED,
        SOLID_RGSS, CUTOUT_RGSS, TRANSLUCENT_RGSS,
        SOLID_REFLECTED_RGSS, CUTOUT_REFLECTED_RGSS, TRANSLUCENT_REFLECTED_RGSS,
        COMPOSITE, COMPOSITE_SHAPE, FEATHER, LAYER;

        public boolean terrain() {
            return ordinal() < COMPOSITE.ordinal();
        }

        public boolean reflected() {
            return switch (this) {
                case SOLID_REFLECTED, CUTOUT_REFLECTED, TRANSLUCENT_REFLECTED,
                    SOLID_REFLECTED_RGSS, CUTOUT_REFLECTED_RGSS, TRANSLUCENT_REFLECTED_RGSS -> true;
                default -> false;
            };
        }

        public boolean rgss() {
            return terrain() && ordinal() >= SOLID_RGSS.ordinal();
        }

        public ChunkSectionLayer layer() {
            return switch (this) {
                case SOLID, SOLID_REFLECTED, SOLID_RGSS, SOLID_REFLECTED_RGSS -> ChunkSectionLayer.SOLID;
                case CUTOUT, CUTOUT_REFLECTED, CUTOUT_RGSS, CUTOUT_REFLECTED_RGSS -> ChunkSectionLayer.CUTOUT;
                case TRANSLUCENT, TRANSLUCENT_REFLECTED, TRANSLUCENT_RGSS, TRANSLUCENT_REFLECTED_RGSS -> ChunkSectionLayer.TRANSLUCENT;
                default -> throw new IllegalStateException("Auxiliary pipeline has no terrain layer: " + this);
            };
        }

        public static Key terrain(ChunkSectionLayer layer, boolean reflected, boolean rgss) {
            for (Key key : values()) {
                if (key.terrain() && key.layer() == layer && key.reflected() == reflected && key.rgss() == rgss) {
                    return key;
                }
            }
            throw new AssertionError("Terrain layer is outside the fixed vanilla bank: " + layer);
        }
    }

    public record Product(Key key, Object descriptor, Object compiled, Object program, int programId, boolean live) {
    }

    public record Snapshot(BankAccess bank, List<Product> products) {
        public Product product(Key key) {
            return products.get(key.ordinal());
        }
    }

    public record Counts(long shaderCompiles, long programLinks, long programLinkRoutes) {
        public Counts minus(Counts previous) {
            return new Counts(shaderCompiles - previous.shaderCompiles, programLinks - previous.programLinks,
                programLinkRoutes - previous.programLinkRoutes);
        }
    }

    private record Preparation(Snapshot snapshot, Counts cost, int portals) {
    }

    private record NativeClosure(long calls, boolean deleted) {
    }

    @Name("wormholes.VanillaPipelinePreparation")
    @Label("Fixed vanilla portal bank resource preparation")
    @StackTrace(false)
    private static final class PreparationEvent extends Event {
        private long generation;
        private long prepareBeginNanos;
        private long prepareEndNanos;
        private long wallNanos;
        private long shaderCompiles;
        private long programLinks;
        private long programLinkRoutes;
        private int fixedNativeProducts;
    }

    @Name("wormholes.VanillaPipelineNativeCompile")
    @Label("Vanilla portal native GL compile or link")
    @StackTrace(false)
    private static final class NativeCompileEvent extends Event {
        @Label("Native operation")
        private String operation;
        @Label("Native duration in nanoseconds")
        private long nativeNanos;
        @Label("Rendered frame")
        private long frame;
    }
}
