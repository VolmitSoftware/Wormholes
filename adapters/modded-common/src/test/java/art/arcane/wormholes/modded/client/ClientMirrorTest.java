package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftProjectedBlockStates;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.BrickCodec;
import art.arcane.optics.stream.BrickLightSource;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.wormholes.network.client.ClientViewCodec;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ClientViewProtocolException;
import art.arcane.optics.stream.PlateSectionBox;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.DirectionMapping;
import art.arcane.optics.frame.PortalCoordMap;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.client.ClientSweep;
import art.arcane.optics.plate.PlateBox;
import art.arcane.optics.math.Face;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntUnaryOperator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ClientMirrorTest {
    private static final int MIRROR_KEY = 1;
    private static final int CHILD_KEY = 2;
    private static final int GOLD_ID = 3;
    private static final int PATCHED_BLOCK_LIGHT = 11;
    private static final int MIRROR_Z = 10;
    private static final double EYE_X = 1.5D;
    private static final double EYE_Y = 65.5D;
    private static final double EYE_Z = 15.5D;
    private static final PlateBox CHILD_PLATE = new PlateBox(-8, 56, 14, 19, 19, 8);
    private static final PlateSectionBox CHILD_SECTIONS = PlateSectionBox.snap(CHILD_PLATE);
    private static BlockState STONE;
    private static BlockState AIR;
    private static BlockState GOLD;
    private static BlockState DIAMOND;

    @BeforeClass
    public static void bootstrap() {
        MinecraftTestBase.bootstrap();
        STONE = Blocks.STONE.defaultBlockState();
        AIR = Blocks.AIR.defaultBlockState();
        GOLD = Blocks.GOLD_BLOCK.defaultBlockState();
        DIAMOND = Blocks.DIAMOND_BLOCK.defaultBlockState();
    }

    @After
    public void deactivate() {
        ProjectionOverlay overlay = ProjectionOverlay.active();
        if (overlay != null) {
            ProjectionOverlay.deactivate(overlay);
        }
    }

    @Test
    public void mirrorCellsReflectTheShadowOfTheSourceWithoutPlateBytes() throws ClientViewProtocolException {
        Harness harness = new Harness();
        harness.surface.set(1, 65, 13, GOLD);
        harness.surface.set(0, 64, 12, Blocks.OAK_STAIRS.defaultBlockState().rotate(Rotation.CLOCKWISE_90));
        harness.surface.set(2, 66, 12, Blocks.OAK_STAIRS.defaultBlockState().rotate(Rotation.CLOCKWISE_180));
        harness.receive(new ClientViewMessage.Portal(MIRROR_KEY, 1, mirror(0, List.of())), ViewStreamLimits.FLAG_LAST);
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        ClientPortal portal = harness.session.portal(MIRROR_KEY);
        assertNotNull(harness.tick.mirror(MIRROR_KEY));
        assertNull("a client mirror has no plate", portal.plate());
        assertEquals(0, harness.session.plates().size());
        assertTrue("the mirror cone is empty", portal.sweep().appliedCount() > 0);
        int reflectedGold = harness.assertReflection(portal);
        assertTrue("the gold block in front of the mirror is not reflected", reflectedGold > 0);
    }

    @Test
    public void rainOverAClientMirrorFallsInItsReflectedAir() throws ClientViewProtocolException {
        Harness harness = new Harness();
        harness.receive(new ClientViewMessage.Portal(MIRROR_KEY, 1, mirror(0, List.of())), 0);
        harness.receive(new ClientViewMessage.Atmosphere(MIRROR_KEY, 6000L, 0.8F, 0.0F, ClientViewMessage.Atmosphere.FLAG_WEATHER),
            ViewStreamLimits.FLAG_LAST);
        for (int i = 0; i < ClientAtmosphere.WEATHER_BURST_TICKS * 40 && harness.tick.atmosphere().weatherParticles() == 0L; i++) {
            harness.tick(EYE_X, EYE_Y, EYE_Z);
        }
        assertNull(harness.session.portal(MIRROR_KEY).plate());
        assertTrue("no rain fell in the reflected air", harness.tick.atmosphere().weatherParticles() > 0L);
    }

    @Test
    public void mirrorReflectsThePreProjectionShadowAndFollowsBlockChanges() throws ClientViewProtocolException {
        Harness harness = new Harness();
        harness.receive(new ClientViewMessage.Portal(MIRROR_KEY, 1, mirror(0, List.of())), ViewStreamLimits.FLAG_LAST);
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        ClientPortal portal = harness.session.portal(MIRROR_KEY);
        assertEquals(0, harness.assertReflection(portal));
        int[] display = harness.anyAppliedCell(portal);
        int[] source = source(display[0], display[1], display[2]);
        harness.surface.set(source[0], source[1], source[2], GOLD);
        harness.tick.blockChanged(harness.level, source[0], source[1], source[2]);
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        assertSame(GOLD, harness.surface.state(display[0], display[1], display[2]));
        long sourceKey = CellKeys.pack(source[0], source[1], source[2]);
        harness.tick.overlay().enter(sourceKey, DIAMOND, GOLD, 99, false);
        harness.surface.set(source[0], source[1], source[2], DIAMOND);
        harness.tick.blockChanged(harness.level, source[0], source[1], source[2]);
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        assertSame("the mirror must reflect the shadow under another projection", GOLD,
            harness.surface.state(display[0], display[1], display[2]));
        harness.tick.overlay().exit(sourceKey);
        harness.surface.set(source[0], source[1], source[2], AIR);
        harness.tick.chunkReloaded(source[0] >> 4, source[2] >> 4);
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        assertSame(AIR, harness.surface.state(display[0], display[1], display[2]));
        assertEquals(0, harness.assertReflection(portal));
    }

    @Test
    public void droppingTheMirrorRevertsEveryReflectedCell() throws ClientViewProtocolException {
        Harness harness = new Harness();
        harness.surface.set(1, 65, 13, GOLD);
        harness.receive(new ClientViewMessage.Portal(MIRROR_KEY, 1, mirror(0, List.of())), ViewStreamLimits.FLAG_LAST);
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        assertTrue(harness.tick.overlay().size() > 0);
        harness.receive(new ClientViewMessage.PortalDrop(MIRROR_KEY), ViewStreamLimits.FLAG_LAST);
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        assertEquals(0, harness.tick.overlay().size());
        assertEquals(0, harness.surface.changedCells());
        assertNull(harness.tick.mirror(MIRROR_KEY));
    }

    @Test
    public void withoutTheClientMirrorCapabilityNothingIsDrawnLocally() throws ClientViewProtocolException {
        Harness harness = new Harness(ClientViewHarness.PLATE_CAPS & ~ViewStreamCapability.CLIENT_MIRROR.mask());
        harness.receive(new ClientViewMessage.Portal(MIRROR_KEY, 1, mirror(0, List.of())), ViewStreamLimits.FLAG_LAST);
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        assertNull(harness.tick.mirror(MIRROR_KEY));
        assertEquals(0, harness.tick.overlay().size());
    }

    @Test
    public void aPortalInsideTheMirrorShowsItsChildPlateInsteadOfAnEmptyAperture() throws ClientViewProtocolException {
        Harness harness = new Harness();
        harness.streamNested();
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        ClientNestedViews nested = harness.tick.nestedViews();
        assertTrue("the nested child displays nothing", nested.cells(CHILD_KEY) > 0);
        ClientPortal mirror = harness.session.portal(MIRROR_KEY);
        int nestedGold = 0;
        LongArrayList applied = new LongArrayList();
        mirror.sweep().appliedKeys(applied);
        for (int index = 0; index < applied.size(); index++) {
            long key = applied.getLong(index);
            int x = CellKeys.unpackX(key);
            int y = CellKeys.unpackY(key);
            int z = CellKeys.unpackZ(key);
            if (nested.displays(CHILD_KEY, x, y, z)) {
                assertSame("nested cell " + x + "," + y + "," + z, GOLD, harness.surface.state(x, y, z));
                assertEquals(CHILD_KEY, harness.tick.overlay().get(key).portalKey());
                nestedGold++;
            }
        }
        assertTrue("no nested cell lies inside the mirror cone", nestedGold > 0);

        harness.receive(new ClientViewMessage.PortalDrop(CHILD_KEY), ViewStreamLimits.FLAG_LAST);
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        assertEquals(0, nested.cells(CHILD_KEY));
        assertEquals(0, harness.assertReflection(mirror));
    }

    @Test
    public void nestedCellsTakeTheChildPlateLightOfTheirContentCell() throws ClientViewProtocolException {
        Harness harness = new Harness();
        harness.streamNested();
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        assertTrue("no nested cell was checked", harness.assertNestedLight(ClientMirrorTest::contentLight) > 0);
    }

    @Test
    public void nestedCellsFollowAChildPlatePatchOfTheirLight() throws ClientViewProtocolException {
        Harness harness = new Harness();
        harness.streamNested();
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        List<ClientViewMessage.PatchOp> ops = new ArrayList<>();
        for (int index = 0; index < CHILD_SECTIONS.brickCount(); index++) {
            ops.add(new ClientViewMessage.FullOp(childBrick(index, z -> PATCHED_BLOCK_LIGHT)));
        }
        harness.receive(new ClientViewMessage.PlatePatch(CHILD_KEY, 1, 2, ops), ViewStreamLimits.FLAG_LAST);
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        assertEquals(2, harness.session.portal(CHILD_KEY).plate().revision());
        assertTrue("no nested cell was checked", harness.assertNestedLight(z -> PATCHED_BLOCK_LIGHT) > 0);
    }

    @Test
    public void theMirrorBoxCoversTheDisplaySideOnly() {
        ApertureDescriptor geometry = mirror(0, List.of());
        PlateBox box = ClientMirrorBuilder.displayBox(geometry);
        assertTrue(box.minZ() + box.sizeZ() - 1 < MIRROR_Z);
        assertTrue(box.minZ() >= MIRROR_Z - 1 - geometry.depthBlocks());
        assertFalse(box.cells() == 0L);
    }

    private static int[] source(int x, int y, int z) {
        ApertureDescriptor geometry = mirror(0, List.of());
        Frame frame = geometry.frame();
        double[] out = new double[3];
        Vec3d origin = geometry.apertureArea().center();
        PortalCoordMap.mirrorDisplayToSourcePointInto(x + 0.5D, y + 0.5D, z + 0.5D, origin.getX(), origin.getY(), origin.getZ(), frame,
            geometry.mirrorQuarterTurns(), out);
        return new int[] {(int) Math.floor(out[0]), (int) Math.floor(out[1]), (int) Math.floor(out[2])};
    }

    private static ApertureDescriptor mirror(int recursionDepth, List<ApertureDescriptor> nested) {
        boolean[] open = new boolean[9];
        Arrays.fill(open, true);
        return new ApertureDescriptor(0, 64, MIRROR_Z, Face.S.ordinal(), true, 0, true, 3, 3,
            ApertureDescriptor.apertureMask(3, 3, open), 0.0F, 0.0F, 0.0F, 8, recursionDepth,
            ApertureDescriptor.BLACKOUT_OFF, 0, ApertureDescriptor.MASK_AIR_PROJECT, 0, 0,
            ApertureDescriptor.KIND_FRAME, 0.0D, 0, 0L, nested);
    }

    private static ApertureDescriptor child() {
        boolean[] open = new boolean[9];
        Arrays.fill(open, true);
        return new ApertureDescriptor(0, 64, 13, Face.S.ordinal(), false, 0, false, 3, 3,
            ApertureDescriptor.apertureMask(3, 3, open), 0.0F, 0.0F, 0.0F, 8, 0,
            ApertureDescriptor.BLACKOUT_OFF, 0, ApertureDescriptor.MASK_AIR_PROJECT, 0, 0,
            ApertureDescriptor.KIND_RTP, 0.0D, MIRROR_KEY, 7L, List.of());
    }

    private static int contentLight(int z) {
        return z & 15;
    }

    private static Brick childBrick(int brickIndex, IntUnaryOperator blockLightByZ) {
        int baseX = CHILD_SECTIONS.sectionX(brickIndex) << 4;
        int baseY = CHILD_SECTIONS.sectionY(brickIndex) << 4;
        int baseZ = CHILD_SECTIONS.sectionZ(brickIndex) << 4;
        int[] cells = new int[ViewStreamLimits.BRICK_CELLS];
        for (int cellIndex = 0; cellIndex < cells.length; cellIndex++) {
            int x = baseX + ViewStreamLimits.brickCellX(cellIndex);
            int y = baseY + ViewStreamLimits.brickCellY(cellIndex);
            int z = baseZ + ViewStreamLimits.brickCellZ(cellIndex);
            boolean inside = x >= CHILD_PLATE.minX() && x < CHILD_PLATE.minX() + CHILD_PLATE.sizeX()
                && y >= CHILD_PLATE.minY() && y < CHILD_PLATE.minY() + CHILD_PLATE.sizeY()
                && z >= CHILD_PLATE.minZ() && z < CHILD_PLATE.minZ() + CHILD_PLATE.sizeZ();
            cells[cellIndex] = inside ? GOLD_ID : ViewStreamLimits.PALETTE_AIR;
        }
        Brick brick = BrickCodec.pack(brickIndex, cells);
        if (brick.isEmpty()) {
            return brick;
        }
        byte[] block = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        for (int cellIndex = 0; cellIndex < ViewStreamLimits.BRICK_CELLS; cellIndex++) {
            BrickLightSource.setNibble(block, cellIndex, blockLightByZ.applyAsInt(baseZ + ViewStreamLimits.brickCellZ(cellIndex)));
        }
        return brick.withLight(block, new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES]);
    }

    private static final class Harness {
        private final WormholesClientConfig config;
        private final ClientViewSession session;
        private final ClientViewReceiver receiver;
        private final ClientViewTick tick;
        private final WorldSurface surface;
        private final Object level;
        private int seq;

        private Harness() {
            this(ClientViewHarness.PLATE_CAPS);
        }

        private Harness(long acceptedCaps) {
            config = new WormholesClientConfig();
            config.normalize();
            session = new ClientViewSession(config, new ClientPalette(BuiltInRegistries.BLOCK), 1, "test");
            session.accept(new ClientViewMessage.Accept(1, acceptedCaps, 20, ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 7L, 8));
            receiver = new ClientViewReceiver(session);
            tick = new ClientViewTick(session, receiver, config, new ClientViewStats());
            List<ClientViewMessage> sent = new ArrayList<>();
            tick.sender(sent::add);
            surface = new WorldSurface();
            level = new Object();
            tick.attach(level, surface, new ClientViewHarness.FakeScene());
        }

        private void receive(ClientViewMessage message, int flags) throws ClientViewProtocolException {
            receiver.receive(ClientViewCodec.encodeS2C(message, ++seq, flags), null);
            assertEquals("decode failed for " + message.type(), 0L, receiver.decodeFailures());
        }

        private void streamNested() throws ClientViewProtocolException {
            ApertureDescriptor child = child();
            receive(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(GOLD_ID, "minecraft:gold_block"))), 0);
            receive(new ClientViewMessage.Portal(MIRROR_KEY, 1, mirror(2, List.of(child))), 0);
            receive(new ClientViewMessage.Portal(CHILD_KEY, 1, child), 0);
            streamChildPlate();
        }

        private void streamChildPlate() throws ClientViewProtocolException {
            Brick[] bricks = new Brick[CHILD_SECTIONS.brickCount()];
            for (int index = 0; index < bricks.length; index++) {
                bricks[index] = childBrick(index, ClientMirrorTest::contentLight);
            }
            long[] hashes = new long[bricks.length];
            for (int index = 0; index < hashes.length; index++) {
                hashes[index] = 0x7000L + index;
            }
            receive(new ClientViewMessage.PlateBegin(CHILD_KEY, 1, CHILD_SECTIONS, CHILD_PLATE, GOLD_ID, bricks.length, hashes), 0);
            receive(new ClientViewMessage.PlateBricks(CHILD_KEY, 1, Arrays.asList(bricks)), 0);
            receive(new ClientViewMessage.PlateEnd(CHILD_KEY, 1), ViewStreamLimits.FLAG_LAST);
        }

        private void tick(double eyeX, double eyeY, double eyeZ) {
            tick.tick(eyeX, eyeY, eyeZ, 0.0D, 0.0D, 0.0D, System.currentTimeMillis());
        }

        private int assertNestedLight(IntUnaryOperator blockLightByContentZ) {
            ClientNestedViews nested = tick.nestedViews();
            LongArrayList applied = new LongArrayList();
            session.portal(MIRROR_KEY).sweep().appliedKeys(applied);
            int checked = 0;
            for (int index = 0; index < applied.size(); index++) {
                long key = applied.getLong(index);
                int x = CellKeys.unpackX(key);
                int y = CellKeys.unpackY(key);
                int z = CellKeys.unpackZ(key);
                if (!nested.displays(CHILD_KEY, x, y, z)) {
                    continue;
                }
                assertSame("nested cell " + x + "," + y + "," + z, GOLD, surface.state(x, y, z));
                int[] content = source(x, y, z);
                assertEquals("block light of nested cell " + x + "," + y + "," + z + " showing " + content[0] + "," + content[1] + "," + content[2],
                    blockLightByContentZ.applyAsInt(content[2]), surface.blockLight(x, y, z));
                checked++;
            }
            return checked;
        }

        private int[] anyAppliedCell(ClientPortal portal) {
            LongArrayList applied = new LongArrayList();
            portal.sweep().appliedKeys(applied);
            assertFalse(applied.isEmpty());
            long key = applied.getLong(applied.size() / 2);
            return new int[] {CellKeys.unpackX(key), CellKeys.unpackY(key), CellKeys.unpackZ(key)};
        }

        private int assertReflection(ClientPortal portal) {
            ApertureDescriptor geometry = portal.geometry();
            DirectionMapping mapping = DirectionMapping.mirror(geometry.frame(), geometry.mirrorQuarterTurns(), new double[3]);
            LongArrayList applied = new LongArrayList();
            ClientSweep sweep = portal.sweep();
            sweep.appliedKeys(applied);
            assertEquals(sweep.appliedCount(), applied.size());
            int gold = 0;
            for (int index = 0; index < applied.size(); index++) {
                long key = applied.getLong(index);
                int x = CellKeys.unpackX(key);
                int y = CellKeys.unpackY(key);
                int z = CellKeys.unpackZ(key);
                int[] source = source(x, y, z);
                ProjectionOverlay.Entry sourceEntry = tick.overlay().get(CellKeys.pack(source[0], source[1], source[2]));
                BlockState shadow = sourceEntry != null ? sourceEntry.shadow() : surface.state(source[0], source[1], source[2]);
                BlockState expected = MinecraftProjectedBlockStates.transform(shadow, mapping);
                BlockState shown = surface.state(x, y, z);
                if (expected.isAir()) {
                    assertTrue("reflected air must carve the real " + surface.real(x, y, z) + " at " + x + "," + y + "," + z,
                        shown.isAir());
                } else {
                    assertSame("reflection at " + x + "," + y + "," + z + " of " + source[0] + "," + source[1] + "," + source[2], expected, shown);
                    if (expected == GOLD) {
                        gold++;
                    }
                }
            }
            return gold;
        }
    }

    private static final class WorldSurface implements ClientViewSurface {
        private final Long2ObjectOpenHashMap<BlockState> real = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<BlockState> written = new Long2ObjectOpenHashMap<>();
        private ClientLightPatches patches;

        private void set(int x, int y, int z, BlockState state) {
            real.put(CellKeys.pack(x, y, z), state);
        }

        private BlockState real(int x, int y, int z) {
            BlockState state = real.get(CellKeys.pack(x, y, z));
            if (state != null) {
                return state;
            }
            return z < MIRROR_Z || y < 64 ? STONE : AIR;
        }

        @Override
        public boolean chunkLoaded(int chunkX, int chunkZ) {
            return true;
        }

        @Override
        public BlockState state(int x, int y, int z) {
            BlockState state = written.get(CellKeys.pack(x, y, z));
            return state == null ? real(x, y, z) : state;
        }

        @Override
        public void write(int x, int y, int z, BlockState state) {
            long key = CellKeys.pack(x, y, z);
            if (state == real(x, y, z)) {
                written.remove(key);
            } else {
                written.put(key, state);
            }
        }

        @Override
        public void blockEntity(int x, int y, int z, BlockEntitySample sample) {
        }

        @Override
        public void attachLight(ClientLightPatches attached) {
            patches = attached;
        }

        @Override
        public void detachLight(ClientLightPatches attached) {
            if (patches == attached) {
                patches = null;
            }
        }

        @Override
        public void lightChanged(int sectionX, int sectionY, int sectionZ, int boundaryMask) {
        }

        @Override
        public int skyDarken() {
            return 0;
        }

        private int blockLight(int x, int y, int z) {
            int patched = patches == null ? ClientLightPatches.NO_LIGHT : patches.value(false, x, y, z, 0);
            return Math.max(0, patched);
        }

        @Override
        public void flush() {
        }

        private int changedCells() {
            return written.size();
        }
    }
}
