package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.network.client.Brick;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.render.client.ClientCellRules;
import art.arcane.wormholes.render.plate.PlateBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ClientViewApplyTest {
    private static final int GOLD_ID = 4;
    private static final int SIGN_ID = 5;
    private static final int PORTAL_KEY = ClientViewHarness.PORTAL_KEY;
    private static final int AIR_COLUMN_X = ClientViewHarness.AIR_COLUMN_X;
    private static final int REAL_AIR_Z = ClientViewHarness.REAL_AIR_Z;
    private static final double EYE_X = ClientViewHarness.EYE_X;
    private static final double EYE_Y = ClientViewHarness.EYE_Y;
    private static final double EYE_Z = ClientViewHarness.EYE_Z;
    private static BlockState STONE;
    private static BlockState DIRT;
    private static BlockState AIR;
    private static BlockState GOLD;

    @BeforeClass
    public static void bootstrap() {
        MinecraftTestBase.bootstrap();
        STONE = ClientViewHarness.STONE;
        DIRT = ClientViewHarness.DIRT;
        AIR = ClientViewHarness.AIR;
        GOLD = Blocks.GOLD_BLOCK.defaultBlockState();
    }

    @After
    public void deactivate() {
        ProjectionOverlay overlay = ProjectionOverlay.active();
        if (overlay != null) {
            ProjectionOverlay.deactivate(overlay);
        }
    }

    @Test
    public void streamedPlateIsSweptAndAppliedThroughTheOverlay() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        ProjectionOverlay overlay = harness.tick.overlay();
        assertTrue("cone applied nothing", overlay.size() > 0);
        int projectedAir = 0;
        for (long key : overlay.keys()) {
            int x = ProjectionCellKey.unpackX(key);
            int y = ProjectionCellKey.unpackY(key);
            int z = ProjectionCellKey.unpackZ(key);
            assertTrue("cell outside the plate " + x + "," + y + "," + z, harness.plateContains(x, y, z));
            assertTrue("cell in front of the portal " + z, z < 10);
            BlockState applied = harness.surface.state(x, y, z);
            if (x == AIR_COLUMN_X) {
                assertSame("mask air over real dirt at " + x + "," + y + "," + z, AIR, applied);
                projectedAir++;
            } else {
                assertSame("projected stone at " + x + "," + y + "," + z, STONE, applied);
            }
            assertFalse("real air column must keep the real state", x == AIR_COLUMN_X && z == REAL_AIR_Z);
        }
        assertTrue(projectedAir > 0);
        assertEquals(overlay.size(), harness.surface.changedCells());
        assertTrue(harness.tick.applier().appliedCells() >= overlay.size());
        assertEquals(1, harness.acks().size());
        assertEquals(harness.lastSeq, harness.acks().get(0).seq());
        assertTrue(harness.surface.lightSections > 0);
    }

    @Test
    public void theApplierKeepsTheSampleOfEveryProjectedBlockEntity() throws ClientViewProtocolException {
        ClientViewHarness.FakeSurface surface = new ClientViewHarness.FakeSurface();
        ProjectionOverlay overlay = new ProjectionOverlay(new Object());
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(SIGN_ID, "minecraft:oak_sign"))));
        ClientProjectionApplier applier = new ClientProjectionApplier(surface, overlay, palette);
        BlockEntitySample sample = new BlockEntitySample("minecraft:sign", new byte[] {10, 0, 0, 0});
        ClientPortalContent content = new ClientPortalContent() {
            @Override
            public PlateBox cells() {
                return new PlateBox(0, 64, 0, 200, 4, 200);
            }

            @Override
            public int backingState() {
                return ClientViewProtocol.PALETTE_AIR;
            }

            @Override
            public int paletteIdAt(int x, int y, int z) {
                return SIGN_ID;
            }

            @Override
            public BlockEntitySample blockEntityAt(int x, int y, int z) {
                return sample;
            }
        };
        ClientCellRules.Policy policy = ClientCellRules.Policy.of(ClientViewHarness.geometry(), ClientViewProtocol.PALETTE_AIR);
        long loaded = ProjectionCellKey.pack(1, 64, 1);
        long missing = ProjectionCellKey.pack(161, 64, 161);
        surface.unloaded.add(ChunkPos.pack(10, 10));

        assertTrue(applier.enter(loaded, PORTAL_KEY, content, policy, false));
        assertTrue(applier.enter(missing, PORTAL_KEY, content, policy, false));

        assertSame(sample, overlay.get(loaded).blockEntity());
        assertTrue(overlay.get(missing).pending());
        assertSame(sample, overlay.get(missing).blockEntity());
    }

    @Test
    public void leavingTheConeRevertsEveryCellToItsShadow() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        int applied = harness.tick.overlay().size();
        assertTrue(applied > 0);
        harness.tick(EYE_X, EYE_Y, 5.0D);
        assertEquals(0, harness.tick.overlay().size());
        assertEquals(0, harness.surface.changedCells());
        assertEquals(applied, harness.tick.applier().revertedCells());
    }

    @Test
    public void serverUpdatesUnderTheOverlayBecomeTheShadow() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        ProjectionOverlay overlay = harness.tick.overlay();
        long key = overlay.keys().getLong(0);
        BlockState gold = Blocks.GOLD_BLOCK.defaultBlockState();
        assertSame(overlay.get(key).projected(), overlay.serverState(key, gold));
        assertSame(gold, overlay.get(key).shadow());
        harness.tick(EYE_X, EYE_Y, 5.0D);
        assertSame(gold, harness.surface.state(ProjectionCellKey.unpackX(key), ProjectionCellKey.unpackY(key), ProjectionCellKey.unpackZ(key)));
    }

    @Test
    public void portalDropRevertsAndSessionResetDisablesTheSession() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        assertTrue(harness.tick.overlay().size() > 0);
        harness.receive(new ClientViewMessage.PortalDrop(PORTAL_KEY), ClientViewProtocol.FLAG_LAST);
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        assertEquals(0, harness.tick.overlay().size());
        assertEquals(0, harness.surface.changedCells());
        assertTrue(harness.session.portals().isEmpty());
        assertEquals(0, harness.session.plates().size());
        harness.stream();
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        assertTrue(harness.tick.overlay().size() > 0);
        harness.receive(new ClientViewMessage.SessionReset(ClientViewMessage.ResetReason.DISABLED), ClientViewProtocol.FLAG_LAST);
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        assertEquals(0, harness.surface.changedCells());
        assertEquals(ClientViewSession.State.VANILLA, harness.session.state());
        assertEquals(1, harness.session.resets());
    }

    @Test
    public void cellsInMissingChunksWaitForTheChunkAndApplyOnArrival() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.surface.unloaded.add(ChunkPos.pack(0, 0));
        harness.stream();
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        ProjectionOverlay overlay = harness.tick.overlay();
        assertTrue(overlay.pendingCells() > 0);
        for (long key : overlay.keys()) {
            int x = ProjectionCellKey.unpackX(key);
            int z = ProjectionCellKey.unpackZ(key);
            boolean inMissingChunk = (x >> 4) == 0 && (z >> 4) == 0;
            assertEquals(inMissingChunk, overlay.get(key).pending());
            if (inMissingChunk) {
                assertSame(DIRT, harness.surface.states.getOrDefault(key, DIRT));
            }
        }
        harness.surface.unloaded.clear();
        int reapplied = overlay.reapply(0, 0, harness.surface.sections());
        assertTrue(reapplied > 0);
        assertEquals(0, overlay.pendingCells());
        for (long key : overlay.keys()) {
            int x = ProjectionCellKey.unpackX(key);
            assertSame(x == AIR_COLUMN_X ? AIR : STONE, harness.surface.state(x, ProjectionCellKey.unpackY(key), ProjectionCellKey.unpackZ(key)));
        }
    }

    @Test
    public void plateRevisionChangesRewriteAppliedCellsInPlace() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        int before = harness.tick.overlay().size();
        harness.receive(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(GOLD_ID, "minecraft:gold_block"))), 0);
        harness.receive(new ClientViewMessage.PlatePatch(PORTAL_KEY, 1, 2, List.of(new ClientViewMessage.FullOp(ClientViewHarness.brick(0, GOLD_ID)),
            new ClientViewMessage.FullOp(ClientViewHarness.brick(1, GOLD_ID)), new ClientViewMessage.FullOp(ClientViewHarness.brick(2, GOLD_ID)),
            new ClientViewMessage.FullOp(ClientViewHarness.brick(3, GOLD_ID)))),
            ClientViewProtocol.FLAG_LAST);
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        ClientPortal portal = harness.session.portal(PORTAL_KEY);
        assertTrue(harness.tick.overlay().size() > before);
        assertEquals(portal.sweep().appliedCount(), harness.tick.overlay().size());
        for (long key : harness.tick.overlay().keys()) {
            BlockState state = harness.surface.state(ProjectionCellKey.unpackX(key), ProjectionCellKey.unpackY(key), ProjectionCellKey.unpackZ(key));
            assertSame(GOLD, state);
        }
        assertEquals(2, portal.plate().revision());
    }

    @Test
    public void aPatchProjectsConeCellsThatShowedTheRealState() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        int y = (int) Math.floor(EYE_Y);
        ClientPortal portal = harness.session.portal(PORTAL_KEY);
        assertTrue("the real air cell must lie in the cone", portal.sweep().applied(AIR_COLUMN_X, y, REAL_AIR_Z));
        assertNull(harness.tick.overlay().get(ProjectionCellKey.pack(AIR_COLUMN_X, y, REAL_AIR_Z)));
        assertSame(AIR, harness.surface.state(AIR_COLUMN_X, y, REAL_AIR_Z));
        assertEquals(ClientViewHarness.DESTINATION_BLOCK_LIGHT, harness.surface.blockLight(AIR_COLUMN_X, y, REAL_AIR_Z));
        int brick = ClientViewHarness.SECTIONS.index(AIR_COLUMN_X >> 4, y >> 4, REAL_AIR_Z >> 4);
        int cell = ClientViewProtocol.brickCellIndex(AIR_COLUMN_X, y, REAL_AIR_Z);
        harness.receive(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(GOLD_ID, "minecraft:gold_block"))), 0);
        harness.receive(new ClientViewMessage.PlatePatch(PORTAL_KEY, 1, 2,
            List.of(new ClientViewMessage.SparseOp(brick, new int[] {cell}, new int[] {GOLD_ID}))), ClientViewProtocol.FLAG_LAST);
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        assertSame(GOLD, harness.surface.state(AIR_COLUMN_X, y, REAL_AIR_Z));
        assertEquals(PORTAL_KEY, harness.tick.overlay().get(ProjectionCellKey.pack(AIR_COLUMN_X, y, REAL_AIR_Z)).portalKey());
        assertEquals(ClientViewHarness.DESTINATION_BLOCK_LIGHT, harness.surface.blockLight(AIR_COLUMN_X, y, REAL_AIR_Z));
    }

    @Test
    public void aNewPlateRevisionProjectsConeCellsThatShowedTheRealState() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        int y = (int) Math.floor(EYE_Y);
        assertSame(AIR, harness.surface.state(AIR_COLUMN_X, y, REAL_AIR_Z));
        harness.receive(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(GOLD_ID, "minecraft:gold_block"))), 0);
        List<Brick> bricks = new ArrayList<>();
        long[] hashes = new long[ClientViewHarness.SECTIONS.brickCount()];
        for (int index = 0; index < hashes.length; index++) {
            bricks.add(ClientViewHarness.brick(index, GOLD_ID));
            hashes[index] = 0x6000L + index;
        }
        harness.receive(new ClientViewMessage.PlateBegin(PORTAL_KEY, 2, ClientViewHarness.SECTIONS, ClientViewHarness.PLATE,
            ClientViewHarness.STONE_ID, hashes.length, hashes), 0);
        harness.receive(new ClientViewMessage.PlateBricks(PORTAL_KEY, 2, bricks), 0);
        harness.receive(new ClientViewMessage.PlateEnd(PORTAL_KEY, 2), ClientViewProtocol.FLAG_LAST);
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        assertEquals(2, harness.session.portal(PORTAL_KEY).plate().revision());
        assertSame(GOLD, harness.surface.state(AIR_COLUMN_X, y, REAL_AIR_Z));
        assertEquals(harness.session.portal(PORTAL_KEY).sweep().appliedCount(), harness.tick.overlay().size());
    }

    @Test
    public void aPatchReevaluatesTheConeCellsOfItsBricksOnly() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        int sectionY = (int) Math.floor(EYE_Y) >> 4;
        int brick = ClientViewHarness.SECTIONS.index(AIR_COLUMN_X >> 4, sectionY, REAL_AIR_Z >> 4);
        assertTrue("the cone must cover other bricks too", cellsOutsideSection(harness.tick.overlay(), AIR_COLUMN_X >> 4, sectionY, REAL_AIR_Z >> 4) > 0);
        long before = harness.tick.applier().appliedCells();
        harness.receive(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(GOLD_ID, "minecraft:gold_block"))), 0);
        harness.receive(new ClientViewMessage.PlatePatch(PORTAL_KEY, 1, 2,
            List.of(new ClientViewMessage.FullOp(ClientViewHarness.brick(brick, GOLD_ID)))), ClientViewProtocol.FLAG_LAST);
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        ClientPortal portal = harness.session.portal(PORTAL_KEY);
        int coneCells = 0;
        for (int x = AIR_COLUMN_X & ~15; x < (AIR_COLUMN_X & ~15) + 16; x++) {
            for (int y = sectionY << 4; y < (sectionY << 4) + 16; y++) {
                for (int z = REAL_AIR_Z & ~15; z < (REAL_AIR_Z & ~15) + 16; z++) {
                    if (portal.sweep().applied(x, y, z)) {
                        coneCells++;
                        assertSame("cone cell " + x + "," + y + "," + z, GOLD, harness.surface.state(x, y, z));
                    }
                }
            }
        }
        assertTrue(coneCells > 0);
        assertEquals(coneCells, harness.tick.applier().appliedCells() - before);
    }

    private static int cellsOutsideSection(ProjectionOverlay overlay, int sectionX, int sectionY, int sectionZ) {
        int outside = 0;
        for (long key : overlay.keys()) {
            if (ProjectionCellKey.unpackX(key) >> 4 != sectionX || ProjectionCellKey.unpackY(key) >> 4 != sectionY
                || ProjectionCellKey.unpackZ(key) >> 4 != sectionZ) {
                outside++;
            }
        }
        return outside;
    }
}
