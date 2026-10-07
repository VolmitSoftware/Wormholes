package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.scan.ProjectorSample;
import art.arcane.optics.volume.LodPolicy;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.plate.PlateCell;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.plate.ViewPlateBuilder;
import art.arcane.optics.plate.ViewPlateKey;
import art.arcane.optics.view.ContentView;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;

final class PlateExposurePropertyTest {
    private static final Face[] HORIZONTAL = {Face.N, Face.S, Face.E, Face.W};

    record Exposure(int occludedCells, int backingCells, int blockCells, int hiddenStates) {
    }

    static Exposure check(ViewPlate<String> plate, EncodedPlate encoded, SessionPalette palette) {
        BlockBox box = plate.box();
        Set<String> visibleStates = new HashSet<String>();
        Set<String> hiddenStates = new HashSet<String>();
        int occluded = 0;
        int backing = 0;
        int block = 0;
        for (long key : plate.cellKeys()) {
            PlateCell<String> cell = plate.cell(key);
            switch (cell.kind()) {
                case OCCLUDED -> {
                    occluded++;
                    hiddenStates.add(cell.sourceData());
                }
                case BACKING_BLOCK -> {
                    backing++;
                    hiddenStates.add(cell.sourceData());
                }
                case BLOCK -> {
                    block++;
                    visibleStates.add(cell.data());
                }
                default -> {
                }
            }
        }
        Set<Integer> referenced = new HashSet<Integer>();
        for (int index = 0; index < encoded.brickCount(); index++) {
            Brick brick = encoded.brick(index);
            int[] cells = BrickCodec.unpack(brick);
            int baseX = encoded.sections().sectionX(index) << 4;
            int baseY = encoded.sections().sectionY(index) << 4;
            int baseZ = encoded.sections().sectionZ(index) << 4;
            for (int i = 0; i < cells.length; i++) {
                int x = baseX + ViewStreamLimits.brickCellX(i);
                int y = baseY + ViewStreamLimits.brickCellY(i);
                int z = baseZ + ViewStreamLimits.brickCellZ(i);
                PlateCell<String> source = box.index(x, y, z) < 0 ? null : plate.cell(CellKeys.pack(x, y, z));
                int id = cells[i];
                if (source != null && source.kind() == ProjectorSample.Kind.OCCLUDED) {
                    assertEquals(ViewStreamLimits.PALETTE_OCCLUDED, id, "occluded cell " + x + "," + y + "," + z + " leaked a state");
                } else if (source != null && source.kind() == ProjectorSample.Kind.BACKING_BLOCK) {
                    assertEquals(ViewStreamLimits.PALETTE_BACKING, id, "backing cell " + x + "," + y + "," + z + " leaked a state");
                } else if (id >= ViewStreamLimits.RESERVED_PALETTE_IDS) {
                    referenced.add(id);
                }
            }
            if (brick.hasBlockEntities()) {
                for (Brick.BlockEntityCell entity : brick.blockEntities()) {
                    int x = baseX + ViewStreamLimits.brickCellX(entity.cellIndex());
                    int y = baseY + ViewStreamLimits.brickCellY(entity.cellIndex());
                    int z = baseZ + ViewStreamLimits.brickCellZ(entity.cellIndex());
                    PlateCell<String> source = plate.cell(CellKeys.pack(x, y, z));
                    assertEquals(ProjectorSample.Kind.BLOCK, source.kind(), "block entity on a non-visible cell");
                }
            }
        }
        for (int id : referenced) {
            String state = palette.state(id);
            assertTrue(visibleStates.contains(state), "brick references " + state + " which no visible cell carries");
        }
        String backingState = palette.state(encoded.backingState());
        Set<Integer> advertised = new HashSet<Integer>();
        for (int id : encoded.referencedIds()) {
            advertised.add(id);
        }
        referenced.add(encoded.backingState());
        assertEquals(referenced, advertised, "referencedIds must be exactly the ids the bricks and backing state use");
        hiddenStates.removeAll(visibleStates);
        hiddenStates.remove(backingState);
        for (String hidden : hiddenStates) {
            int id = palette.lookup(hidden);
            assertTrue(id < 0 || !advertised.contains(id), "hidden-only state " + hidden + " would be advertised to the client");
        }
        return new Exposure(occluded, backing, block, hiddenStates.size());
    }

    static ViewPlate<String> randomPlate(SyntheticWorld world, Random random, boolean blockEntities) {
        Face local = HORIZONTAL[random.nextInt(4)];
        Face remote = HORIZONTAL[random.nextInt(4)];
        int lx = random.nextInt(200);
        int lz = random.nextInt(200);
        int rx = 400 + random.nextInt(600);
        int rz = 400 + random.nextInt(600);
        int surface = world.surface(rx, rz);
        int ry = surface == Integer.MIN_VALUE ? 64 : surface + 1;
        int width = 1 + random.nextInt(3);
        int height = 2 + random.nextInt(2);
        ApertureCells geometry = new ApertureCells();
        boolean alongX = local == Face.N || local == Face.S;
        geometry.setArea(alongX
            ? new Box(lx, lx + width - 0.001D, 64, 64 + height - 0.001D, lz, lz + 0.999D)
            : new Box(lx, lx + 0.999D, 64, 64 + height - 0.001D, lz, lz + width - 0.001D));
        ViewPlateKey key = new ViewPlateKey(UUID.nameUUIDFromBytes(("p" + lx + lz).getBytes()), world, random.nextBoolean(), 0, 0L);
        ViewPlateBuilder.Request<String, String, ContentView<String, String>> request =
            new ViewPlateBuilder.Request<String, String, ContentView<String, String>>(key, geometry, world,
                Frame.canonical(local), Frame.canonical(remote), lx + 0.4995D, 64.4995D, lz + 0.5005D,
                rx + 0.4995D, ry + 1.4995D, rz + 0.4995D, false, 0, 12 + random.nextInt(20), 4 + random.nextInt(8), 0.75D, true,
                SyntheticWorld.AIR, random.nextBoolean() ? LodPolicy.NONE : new LodPolicy(true, 8, 12), blockEntities, 0L, 0L, 0L,
                SyntheticBlocks.INSTANCE);
        return ViewPlateBuilder.build(request);
    }

    @Test
    void noOccludedOrBackingCellEverCarriesAStateAcrossRandomPlates() {
        Random random = new Random(0x0CC1DEDL);
        int occluded = 0;
        int backing = 0;
        int hiddenOnly = 0;
        for (int round = 0; round < 40; round++) {
            SessionPalette palette = new SessionPalette();
            PlateStreamEncoder<String> encoder = new PlateStreamEncoder<String>(palette, state -> state);
            SyntheticWorld world = new SyntheticWorld(1000L + round);
            ViewPlate<String> plate = randomPlate(world, random, false);
            EncodedPlate encoded = encoder.encode(plate, null, false);
            Exposure exposure = check(plate, encoded, palette);
            occluded += exposure.occludedCells();
            backing += exposure.backingCells();
            hiddenOnly += exposure.hiddenStates();
            assertEquals(encoded.referencedIds().length + ViewStreamLimits.RESERVED_PALETTE_IDS, palette.size(),
                "a fresh palette must hold exactly the reserved ids plus the states this plate advertises");
        }
        assertTrue(hiddenOnly > 0, "random plates should contain states that exist only under buried cells");
        assertTrue(occluded > 1000, "random plates carried too few occluded cells to be meaningful: " + occluded);
        assertTrue(backing > 100, "random plates carried too few backing cells to be meaningful: " + backing);
    }

    @Test
    void theDomePlateSetHoldsTheExposureProperty() {
        SyntheticWorld world = new SyntheticWorld(DomePlateSizeTest.WORLD_SEED);
        List<ViewPlate<String>> plates = DomePlates.build(world, DomePlateSizeTest.PLACEMENT_SEED, false);
        SessionPalette palette = new SessionPalette();
        PlateStreamEncoder<String> encoder = new PlateStreamEncoder<String>(palette, state -> state);
        int hiddenOnly = 0;
        for (ViewPlate<String> plate : plates) {
            Exposure exposure = check(plate, encoder.encode(plate, null, false), palette);
            hiddenOnly += exposure.hiddenStates();
        }
        assertTrue(hiddenOnly > 0, "the dome set should contain states that exist only under buried cells");
    }
}
