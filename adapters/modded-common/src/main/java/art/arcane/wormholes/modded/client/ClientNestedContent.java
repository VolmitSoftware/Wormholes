package art.arcane.wormholes.modded.client;

import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.plate.PlateBox;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.aperture.ApertureDescriptor;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

import java.util.Objects;
import java.util.List;

public final class ClientNestedContent implements ClientPortalContent {
    private final ClientPlate plate;
    private final OpticTransform content;
    private final ClientPalette palette;
    private final ClientStateReflector reflector;
    private final Int2IntOpenHashMap reflectedIds;
    private final int[] cell;

    public ClientNestedContent(ClientPlate plate, OpticTransform transform, List<ApertureDescriptor> reflections, ClientPalette palette) {
        this.plate = Objects.requireNonNull(plate, "plate");
        this.content = transform.inverse();
        this.palette = Objects.requireNonNull(palette, "palette");
        this.reflector = new ClientStateReflector(reflections);
        this.reflectedIds = new Int2IntOpenHashMap(64);
        this.reflectedIds.defaultReturnValue(-1);
        this.cell = new int[3];
    }

    public ClientPlate plate() {
        return plate;
    }

    public OpticTransform content() {
        return content;
    }

    @Override
    public PlateBox cells() {
        return plate.cells();
    }

    @Override
    public int backingState() {
        return reflect(plate.backingState());
    }

    @Override
    public int paletteIdAt(int x, int y, int z) {
        content.cellInto(x, y, z, cell);
        return reflect(plate.paletteIdAt(cell[0], cell[1], cell[2]));
    }

    @Override
    public BlockEntitySample blockEntityAt(int x, int y, int z) {
        if (!reflector.identity()) {
            return null;
        }
        content.cellInto(x, y, z, cell);
        return plate.blockEntityAt(cell[0], cell[1], cell[2]);
    }

    private int reflect(int paletteId) {
        if (reflector.identity() || paletteId < ViewStreamLimits.RESERVED_PALETTE_IDS) {
            return paletteId;
        }
        int known = reflectedIds.get(paletteId);
        if (known >= 0) {
            return known;
        }
        int reflected = palette.localId(reflector.reflect(palette.state(paletteId)));
        reflectedIds.put(paletteId, reflected);
        return reflected;
    }
}
